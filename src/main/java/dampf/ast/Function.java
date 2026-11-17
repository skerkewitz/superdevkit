package dampf.ast;

import dampf.frontend.SourcePos;
import java.util.ArrayList;
import java.util.List;

/** A function. Its body is set once the definition has been read (a prototype may come first). */
public final class Function {
    private final String name;
    private final String label;
    private final Type returnType;
    private final List<Type> paramTypes;
    private final SourcePos pos;
    private final List<Variable> params = new ArrayList<>();
    private final List<Variable> locals = new ArrayList<>();
    private Stmt.Block body;
    private SourcePos definitionPos;

    public Function(String name, String label, Type returnType, List<Type> paramTypes, SourcePos pos) {
        this.name = name;
        this.label = label;
        this.returnType = returnType;
        this.paramTypes = List.copyOf(paramTypes);
        this.pos = pos;
    }

    public String name() {
        return name;
    }

    public String label() {
        return label;
    }

    public Type returnType() {
        return returnType;
    }

    public List<Type> paramTypes() {
        return paramTypes;
    }

    /** Where it was first declared. */
    public SourcePos pos() {
        return pos;
    }

    public List<Variable> params() {
        return params;
    }

    /** Locals and temporaries of the static frame. */
    public List<Variable> locals() {
        return locals;
    }

    public Stmt.Block body() {
        return body;
    }

    public SourcePos definitionPos() {
        return definitionPos;
    }

    public boolean isDefined() {
        return body != null;
    }

    public void define(List<Variable> params, Stmt.Block body, SourcePos definitionPos) {
        this.params.addAll(params);
        this.body = body;
        this.definitionPos = definitionPos;
    }

    @Override
    public String toString() {
        return name;
    }
}
