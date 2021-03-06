package meghanada.parser;

import com.github.javaparser.Range;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import meghanada.reflect.CandidateUnit;
import meghanada.reflect.FieldDescriptor;

public class Variable {

    final String name;
    private final String fqcn;
    private final String parent;
    private final Range range;
    private boolean declaration;

    Variable(final String parent, final String name, final Range range, final String fqcn) {
        this.parent = parent;
        this.name = name;
        this.range = range;
        this.fqcn = fqcn;
    }

    Variable(final String parent, final String name, final Range range, final String fqcn, final boolean declaration) {
        this.parent = parent;
        this.name = name;
        this.range = range;
        this.fqcn = fqcn;
        this.declaration = declaration;
    }

    public boolean contains(final int column) {
        final int start = this.range.begin.column;
        final int end = this.range.begin.column;
        return start <= column && column <= end;
    }

    public String getFQCN() {
        return fqcn;
    }

    public CandidateUnit toCandidateUnit() {
        return FieldDescriptor.createVar(this.parent, this.name, this.fqcn);
    }

    public boolean isDeclaration() {
        return declaration;
    }

    public int getLine() {
        return this.range.begin.line;
    }

    public Range getRange() {
        return range;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Variable variable = (Variable) o;
        return declaration == variable.declaration
                && Objects.equal(name, variable.name)
                && Objects.equal(fqcn, variable.fqcn)
                && Objects.equal(parent, variable.parent)
                && Objects.equal(range, variable.range);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name, fqcn, parent, range, declaration);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("fqcn", fqcn)
                .add("parent", parent)
                .add("range", range)
                .add("declaration", declaration)
                .toString();
    }
}
