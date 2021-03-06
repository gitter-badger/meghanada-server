package meghanada.session;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import meghanada.compiler.CompileResult;
import meghanada.completion.JavaCompletion;
import meghanada.completion.JavaVariableCompletion;
import meghanada.completion.LocalVariable;
import meghanada.config.Config;
import meghanada.location.Location;
import meghanada.location.LocationSearcher;
import meghanada.parser.JavaSource;
import meghanada.project.Project;
import meghanada.project.ProjectDependency;
import meghanada.project.gradle.GradleProject;
import meghanada.project.maven.MavenProject;
import meghanada.project.meghanada.MeghanadaProject;
import meghanada.reflect.CandidateUnit;
import meghanada.utils.ClassNameUtils;
import meghanada.utils.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Session {

    private static final String PROJECT_CACHE = "project.dat";
    private static final String MVN_PROJECT_FILE = "pom.xml";
    private static final String GRADLE_PROJECT_FILE = "build.gradle";

    private static final Logger log = LogManager.getLogger(Session.class);

    private static final Pattern SWITCH_TEST_RE = Pattern.compile("Test.java", Pattern.LITERAL);
    private static final Pattern SWITCH_JAVA_RE = Pattern.compile(".java", Pattern.LITERAL);

    private final Project currentProject;
    private final LoadingCache<File, JavaSource> sourceCache;
    private final SessionEventBus sessionEventBus;
    private JavaCompletion completion;
    private JavaVariableCompletion variableCompletion;
    private LocationSearcher locationSearcher;
    private Deque<Location> jumpDecHistory = new ArrayDeque<>(16);
    private boolean started;

    private Session(final Project currentProject) {
        this.currentProject = currentProject;
        this.sourceCache = CacheBuilder.newBuilder()
                .expireAfterAccess(15, TimeUnit.MINUTES)
                .build(new JavaSourceLoader());

        this.sessionEventBus = new SessionEventBus(this);
        this.started = false;
        this.locationSearcher = new LocationSearcher(currentProject.getAllSources(), this.sourceCache);
    }

    public static Session createSession(String root) throws IOException {
        return createSession(new File(root));
    }

    private static Session createSession(File root) throws IOException {
        root = root.getCanonicalFile();
        final Project project = findProject(root);
        if (project == null) {
            throw new IllegalArgumentException("Project Not Found");
        }
        return new Session(project);
    }

    private static Project findProject(File dir) throws IOException {
        while (true) {

            log.debug("finding project from '{}' ...", dir);
            if (dir.getPath().equals("/")) {
                return null;
            }

            // challenge
            File gradle = new File(dir, GRADLE_PROJECT_FILE);
            File mvn = new File(dir, MVN_PROJECT_FILE);
            File meghanada = new File(dir, Config.MEGHANADA_CONF_FILE);

            if (gradle.exists()) {
                log.debug("find gradle project {}", gradle);
                return loadProject(dir, GRADLE_PROJECT_FILE);
            } else if (mvn.exists()) {
                log.debug("find mvn project {}", mvn);
                return loadProject(dir, MVN_PROJECT_FILE);
            } else if (meghanada.exists()) {
                log.debug("find meghanada project {}", meghanada);
                return loadProject(dir, Config.MEGHANADA_CONF_FILE);
            }

            File parent = dir.getParentFile();
            if (parent == null) {
                return null;
            }
            dir = dir.getParentFile();
        }
    }

    private static Project loadProject(final File projectRoot, final String targetFile) throws IOException {

        System.setProperty("project.root", projectRoot.getCanonicalPath());

        final Config config = Config.load();
        final String projectSettingDir = config.getProjectSettingDir();
        final File settingFile = new File(projectSettingDir);
        if (!settingFile.exists()) {
            settingFile.mkdirs();
        }
        final String id = FileUtils.findProjectID(projectRoot, targetFile);

        log.debug("project ID={}", id);
        if (config.useFastBoot()) {

            final File projectCache = new File(projectSettingDir, PROJECT_CACHE);

            if (projectCache.exists()) {
                final Project tempProject = Session.readProjectCache(projectCache);
                if (tempProject != null && tempProject.getId().equals(id)) {
                    tempProject.setId(id);
                    log.debug("load from cache project={}", tempProject);
                    return tempProject.mergeFromProjectConfig();
                }
            }
        }

        Project project;
        if (targetFile.equals(GRADLE_PROJECT_FILE)) {
            project = new GradleProject(projectRoot);
        } else if (targetFile.equals(MVN_PROJECT_FILE)) {
            project = new MavenProject(projectRoot);
        } else {
            project = new MeghanadaProject(projectRoot);
        }
        project.setId(id);

        final Project parsed = project.parseProject();
        if (config.useFastBoot()) {
            final File projectCache = new File(projectSettingDir, PROJECT_CACHE);
            Session.writeProjectCache(parsed, projectCache);
        }
        return parsed.mergeFromProjectConfig();
    }

    public static Collection<File> getSystemJars() throws IOException {
        final String javaHome = Config.load().getJavaHomeDir();
        File jvmDir = new File(javaHome);
        return Files.walk(jvmDir.toPath())
                .map(Path::toFile)
                .filter(f -> f.getName().endsWith(".jar") && !f.getName().endsWith("policy.jar"))
                .collect(Collectors.toList());
    }

    public static boolean clearCache() throws IOException {
        final String projectSettingDir = Config.load().getProjectSettingDir();
        log.debug("clear cache projectCacheDir:{}", projectSettingDir);
        FileUtils.deleteFile(new File(projectSettingDir), false);
        return true;
    }

    private static void writeProjectCache(final Project project, final File cacheFile) {
        final Kryo kryo = new Kryo();
        try (final Output out = new Output(new FileOutputStream(cacheFile))) {
            kryo.writeClassAndObject(out, project);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Project readProjectCache(final File cacheFile) {
        final Kryo kryo = new Kryo();
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());

        try (final Input input = new Input(new FileInputStream(cacheFile))) {
            final Object o = kryo.readClassAndObject(input);
            log.debug("load project={}", o);
            if (o != null) {
                return (Project) o;
            }
            return null;
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void setupSubscribes() throws IOException {
        // subscribe file watch
        this.sessionEventBus.subscribeFileWatch();
        this.sessionEventBus.subscribeParse();
        this.sessionEventBus.subscribeCompile();
        this.sessionEventBus.subscribeCache();
    }

    public Session start() throws IOException {
        if (this.started) {
            return this;
        }

        this.setupSubscribes();
        log.debug("session start");
        Set<File> sources = this.currentProject.getSourceDirectories();
        this.sessionEventBus.requestFileWatch(new ArrayList<>(sources));
        this.sessionEventBus.requestClassCache();
        log.debug("session started");
        this.started = true;
        return this;
    }

    public void shutdown(int timeout) {
        log.debug("session shutdown ...");

        this.sessionEventBus.shutdown(timeout);

        log.debug("session shutdown done");
    }

    public Project getCurrentProject() {
        return currentProject;
    }

    private JavaCompletion getCompletion() {
        if (this.completion == null) {
            this.completion = new JavaCompletion(this.sourceCache);
        }
        return this.completion;
    }

    public JavaVariableCompletion getVariableCompletion() {
        if (this.variableCompletion == null) {
            this.variableCompletion = new JavaVariableCompletion(this.sourceCache);
        }
        return variableCompletion;
    }

    public synchronized Collection<? extends CandidateUnit> completionAt(String path, int line, int column, String prefix) throws IOException, ClassNotFoundException, ExecutionException {
        // java file only
        File file = normalize(path);
        if (!JavaSource.isJavaFile(file)) {
            return Collections.emptyList();
        }
        return getCompletion().completionAt(file, line, column, prefix);
    }

    public synchronized LocalVariable localVariable(String path, int line) throws ExecutionException {
        // java file only
        File file = normalize(path);
        if (!JavaSource.isJavaFile(file)) {
            return new LocalVariable("", Collections.emptyList());
        }
        return getVariableCompletion().localVariable(file, line);
    }

    public synchronized boolean addImport(String path, String fqcn) throws ExecutionException {
        // java file only
        File file = normalize(path);
        if (!JavaSource.isJavaFile(file)) {
            return false;
        }

        JavaSource source = parseJavaSource(file);
        source.importClass.put(ClassNameUtils.getSimpleName(fqcn), fqcn);
        return true;
    }

    public synchronized List<String> optimizeImport(String path) throws ExecutionException {
        // java file only
        File file = normalize(path);
        if (!JavaSource.isJavaFile(file)) {
            return Collections.emptyList();
        }

        JavaSource source = parseJavaSource(file);
        return source.optimizeImports();
    }

    public synchronized Map<String, List<String>> searchMissingImport(String path) throws ExecutionException {
        // java file only
        File file = normalize(path);
        if (!JavaSource.isJavaFile(file)) {
            return Collections.emptyMap();
        }

        JavaSource source = parseJavaSource(file);
        return source.searchMissingImport();
    }

    public synchronized String getImplementTemplate(String fqcn) {
        return "";
    }

    private JavaSource parseJavaSource(final File file) throws ExecutionException {
        return this.sourceCache.get(file);
    }

    public synchronized boolean parseFile(final String path) throws ExecutionException {
        // java file only
        final File file = normalize(path);
        if (!JavaSource.isJavaFile(file)) {
            return false;
        }
        parseJavaSource(file);
        return true;
    }

    public synchronized CompileResult compileFile(final String path) throws IOException {
        // java file only
        final File file = normalize(path);
        return currentProject.compileFile(file, true);
    }

    public synchronized CompileResult compileProject() throws IOException {
        final Project prj = currentProject;
        CompileResult result = prj.compileJava(false);
        if (result.isSuccess()) {
            result = prj.compileTestJava(false);
        }
        return result;
    }

    public Collection<File> getDependentJars() {
        return currentProject.getDependencies()
                .stream()
                .map(ProjectDependency::getFile)
                .collect(Collectors.toList());
    }

    public File getOutputDirectory() {
        return currentProject.getOutputDirectory();
    }

    public File getTestOutputDirectory() {
        return currentProject.getTestOutputDirectory();
    }

    private File normalize(String src) {
        File file = new File(src);
        if (!file.isAbsolute()) {
            file = new File(currentProject.getProjectRoot(), src);
        }
        return file;
    }

    private File normalizeFile(File file) {
        if (!file.isAbsolute()) {
            file = new File(currentProject.getProjectRoot(), file.getPath());
        }
        return file;
    }

    public InputStream runJUnit(String test) throws IOException {
        return currentProject.runJUnit(test);
    }

    public String switchTest(String path) throws IOException {
        Project project = currentProject;
        String root = null;
        Set<File> roots;
        boolean isTest;

        if (path.endsWith("Test.java")) {
            // test -> src
            roots = project.getTestSourceDirectories();
            isTest = true;
        } else {
            // src -> test
            roots = project.getSourceDirectories();
            isTest = false;
        }

        for (File file : roots) {
            String rootPath = file.getCanonicalPath();
            if (path.startsWith(rootPath)) {
                root = rootPath;
                break;
            }
        }
        if (root == null) {
            return null;
        }

        String switchPath = path.substring(root.length());

        if (isTest) {
            switchPath = SWITCH_TEST_RE.matcher(switchPath).replaceAll(Matcher.quoteReplacement(".java"));
            // to src
            for (File srcRoot : project.getSourceDirectories()) {
                final File srcFile = new File(srcRoot, switchPath);
                if (srcFile.exists()) {
                    return srcFile.getCanonicalPath();
                }
            }

        } else {
            switchPath = SWITCH_JAVA_RE.matcher(switchPath).replaceAll(Matcher.quoteReplacement("Test.java"));
            // to test
            for (File srcRoot : project.getTestSourceDirectories()) {
                final File testFile = new File(srcRoot, switchPath);
                if (testFile.exists()) {
                    return testFile.getCanonicalPath();
                }
            }
        }

        return null;
    }

    String createJunitFile(String path) throws IOException, ExecutionException {
        Project project = currentProject;
        String root = null;
        Set<File> roots = project.getSourceDirectories();

        for (File file : roots) {
            String rootPath = file.getCanonicalPath();
            if (path.startsWith(rootPath)) {
                root = rootPath;
                break;
            }
        }
        if (root == null) {
            return null;
        }

        String switchPath = path.substring(root.length());
        switchPath = switchPath.replace(".java", "Test.java");
        int srcSize = project.getTestSourceDirectories().size();
        // to test
        for (File srcRoot : project.getTestSourceDirectories()) {
            String srcRootPath = srcRoot.getCanonicalPath();
            if (srcSize > 1 && srcRootPath.contains(Project.DEFAULT_PATH)) {
                // skip default root
                continue;
            }
            File testFile = new File(srcRoot, switchPath);
            if (testFile.exists()) {
                return testFile.getCanonicalPath();
            } else {
                // create Junit
                this.createTestFile(path, testFile);
                return testFile.getCanonicalPath();
            }
        }
        return null;
    }

    private void createTestFile(String path, File testFile) throws IOException, ExecutionException {
        JavaSource javaSource = this.parseJavaSource(new File(path));
        String pkg = javaSource.getPkg();
        String testName = testFile.getName().replace(".java", "");

        String sb = "package " + pkg + ";\n"
                + "\n"
                + "import org.junit.After;\n"
                + "import org.junit.Before;\n"
                + "import org.junit.Test;\n"
                + "\n"
                + "import static org.junit.Assert.assertEquals;\n"
                + "\n"
                + "public class " + testName + " {\n"
                + "\n"
                + "    @Before\n"
                + "    public void setUp() throws Exception {\n"
                + "\n"
                + "    }\n"
                + "\n"
                + "    @After\n"
                + "    public void tearDown() throws Exception {\n"
                + "\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void test() throws Exception {\n"
                + "        assertEquals(1, 1);\n"
                + "    }\n"
                + "}\n";
        com.google.common.io.Files.write(sb, testFile, Charset.forName("UTF-8"));
    }

    public synchronized Location jumpDeclaration(final String path, final int line, final int column, final String symbol) throws ExecutionException {
        Location location = locationSearcher.searchDeclaration(new File(path), line, column, symbol);
        if (location != null) {
            Location backLocation = new Location(path, line, column);
            this.jumpDecHistory.addLast(backLocation);
        }
        // return same point
        // return new Location(path, line, column);
        return location;
    }

    public synchronized Location backDeclaration() {
        return this.jumpDecHistory.pollLast();
    }

    public LoadingCache<File, JavaSource> getSourceCache() {
        return sourceCache;
    }

    public InputStream runTask(List<String> args) throws Exception {
        return currentProject.runTask(args);
    }

}
