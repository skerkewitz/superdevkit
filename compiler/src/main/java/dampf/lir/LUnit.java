package dampf.lir;

import java.util.ArrayList;
import java.util.List;

/**
 * A piece of assembly as LIR: top-level items (directives, data, comments) and functions. Built
 * line by line with {@link #add}: {@code .proc} opens a function, {@code .endproc} closes it, and
 * in between the lines are cut into basic blocks ({@link LFunction#append}).
 */
public final class LUnit {
    private final List<LItem> items = new ArrayList<>();
    private LFunction function;

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
            }
            case LDirective d when d.kind() == LDirective.Kind.PROC ->
                    throw new IllegalArgumentException(".proc inside .proc " + function.name());
            default -> function.append(item);
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
