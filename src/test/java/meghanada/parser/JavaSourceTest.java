package meghanada.parser;

import meghanada.GradleTestBase;
import meghanada.reflect.asm.CachedASMReflector;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static meghanada.config.Config.timeIt;
import static org.junit.Assert.assertEquals;

public class JavaSourceTest extends GradleTestBase {

    @BeforeClass
    public static void beforeClass() throws Exception {
        GradleTestBase.setupReflector();
        CachedASMReflector cachedASMReflector = CachedASMReflector.getInstance();
        cachedASMReflector.addDirectory(getOutputDir());
        cachedASMReflector.createClassIndexes();
    }

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testOptimizeImports1() throws Exception {
        List<String> optimizeImports = timeIt(() -> {
            JavaParser parser = new JavaParser();
            JavaSource source = parser.parse(new File("./src/test/resources/MissingImport1.java"));
            return source.optimizeImports();
        });
        System.out.println(optimizeImports);
        assertEquals(3, optimizeImports.size());
    }

    @Test
    public void testSearchMissingImport1() throws Exception {
        JavaSource source = timeIt(() -> {
            JavaParser parser = new JavaParser();
            return parser.parse(new File("./src/test/resources/MissingImport2.java"));
        });
        Map<String, List<String>> listMap = source.searchMissingImport();
        System.out.println(listMap);
        assertEquals(11, listMap.size());
    }
}