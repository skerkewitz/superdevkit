package dampf.lir;

import java.util.ArrayList;
import java.util.List;

/**
 * A piece of assembly as LIR: top-level items (directives, data, comments) and functions. Built
 * line by line with {@link #add}: {@code .proc} opens a function, {@code .endproc} closes it, and
 * in between the lines are cut into basic blocks. Every label starts a block, and every branch,
 * jump or return ends one; comments and directives between them belong to the block after them
 * (so a block may start with comments before its label).
 */
public final class LUnit {
    private final List<LItem> items = new ArrayList<>();
    private LFunction function;
    private LBlock block;

    public List<LItem> items() {
        return items;
    }

    /** The functions, in order. */
    public List<LFunction> functions() {
        List<LFunction> out = new ArrayList<>();
        for (LItem i : items) {
            if (i instanceof LFunction f) {
                out.add(f);
            }
        }
        return out;
    }

    public void add(LItem item) {
        if (function == null) {
            if (item instanceof LDirective d && d.kind() == LDirective.Kind.PROC) {
                function = new LFunction(d);
                items.add(function);
                block = null;
            } else if (item instanceof LInstr) {
                throw new IllegalArgumentException("an instruction outside .proc: " + item);
            } else {
                items.add(item);
            }
            return;
        }
        switch (item) {
            case LDirective d when d.kind() == LDirective.Kind.ENDPROC -> {
                function.close(d);
                function = null;
                block = null;
            }
            case LDirective d when d.kind() == LDirective.Kind.PROC ->
                    throw new IllegalArgumentException(".proc inside .proc " + function.name());
            case LLabel l -> {
                if (block == null || block.hasCode() || block.label() != null) {
                    block = function.newBlock();
                }
                block.add(l);
            }
            case LInstr in -> {
                if (block == null) {
                    block = function.newBlock();
                }
                block.add(in);
                if (in.endsBlock()) {
                    block = null;
                }
            }
            case LFunction f -> throw new IllegalArgumentException("a function inside .proc " + function.name());
            default -> {
                if (block == null) {
                    block = function.newBlock();
                }
                block.add(item);
            }
        }
    }

    /** Appends everything of another unit (which must not be inside a function). */
    public void addAll(LUnit other) {
        if (other.function != null) {
            throw new IllegalStateException("a unit inside .proc " + other.function.name());
        }
        if (function != null) {
            throw new IllegalStateException("a unit added inside .proc " + function.name());
        }
        items.addAll(other.items);
    }

    /** Whether a function is open (between .proc and .endproc). */
    public boolean inFunction() {
        return function != null;
    }
}
