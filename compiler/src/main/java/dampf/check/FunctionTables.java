package dampf.check;

import dampf.ast.Children;
import dampf.ast.Expr;
import dampf.ast.Function;
import dampf.ast.Init;
import dampf.ast.Program;
import dampf.ast.Stmt;
import dampf.ast.Type;
import dampf.ast.Variable;
import dampf.frontend.CompileError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Function pointers in constant tables (ADR-6F0R668G, docs/subset.md "Function pointers"). A
 * function pointer exists only as an entry of a const object in ROM, set to a function by its
 * initializer, and is only ever called straight from there ({@link Expr.TableCall}). So the
 * functions a call can reach are known at compile time: the entries of the table that the
 * callee's place can be, and the call graph stays complete.
 */
public final class FunctionTables {
    private final Map<String, Variable> globals = new HashMap<>();
    private final Map<String, Init> inits = new HashMap<>();

    public FunctionTables(Program program) {
        for (Variable v : program.globals()) {
            globals.put(v.label(), v);
        }
        program.initialValues().forEach((v, i) -> inits.put(v.label(), i));
    }

    /**
     * The functions a call through {@code callee} can reach, in the order of the table, each once.
     * Every place the callee's index and field chain can name is a function entry, as the front
     * end checked.
     */
    public List<Function> targets(Expr callee) {
        Variable root = root(callee);
        Variable table = globals.getOrDefault(root.label(), root);
        Set<Long> offsets = new TreeSet<>();
        offsets(callee, table, offsets);
        Init init = inits.get(table.label());
        Set<Function> out = new LinkedHashSet<>();
        if (init != null) {
            for (Init.Item i : init.items()) {
                if (i.function() != null && offsets.contains((long) i.offset())) {
                    out.add(i.function());
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("a call through " + table.name() + " reaches no function");
        }
        return new ArrayList<>(out);
    }

    /** The variable a callee chain starts at. */
    public static Variable root(Expr callee) {
        return switch (callee) {
            case Expr.Var v -> v.variable();
            case Expr.Index i -> root(i.base());
            case Expr.Member m -> root(m.base());
            default -> throw new IllegalStateException("not a table entry: " + callee);
        };
    }

    /** Whether e is an entry of a constant table: an index and field chain on a const object in ROM. */
    public static boolean isTableEntry(Expr e) {
        return switch (e) {
            case Expr.Var v -> v.variable().inRom();
            case Expr.Index i -> i.base().type() instanceof Type.Array && isTableEntry(i.base());
            case Expr.Member m -> isTableEntry(m.base());
            default -> false;
        };
    }

    /** The byte offsets in the table that e can name: every element for an index that is not a constant. */
    private static void offsets(Expr e, Variable table, Set<Long> out) {
        switch (e) {
            case Expr.Var v -> out.add(0L);
            case Expr.Index i -> {
                Set<Long> base = new TreeSet<>();
                offsets(i.base(), table, base);
                long size = i.type().size();
                Type bt = i.base() instanceof Expr.Var v ? table.type() : i.base().type();
                int length = bt instanceof Type.Array a ? a.length() : -1;
                Long k = i.index() instanceof Expr.Const c ? c.value() : null;
                for (long b : base) {
                    if (k != null) {
                        out.add(b + k * size);
                    } else {
                        for (int n = 0; n < length; n++) {
                            out.add(b + n * size);
                        }
                    }
                }
            }
            case Expr.Member m -> {
                Set<Long> base = new TreeSet<>();
                offsets(m.base(), table, base);
                for (long b : base) {
                    out.add(b + m.field().offset());
                }
            }
            default -> throw new IllegalStateException("not a table entry: " + e);
        }
    }

    /**
     * The byte offsets of the function pointers in an object of type t (at {@code base}); used to
     * check that a table sets every one.
     */
    public static void pointerOffsets(Type t, long base, List<Long> out) {
        switch (t) {
            case Type.Pointer p when p.target() instanceof Type.Func -> out.add(base);
            case Type.Array a -> {
                for (int n = 0; n < a.length(); n++) {
                    pointerOffsets(a.element(), base + (long) n * a.element().size(), out);
                }
            }
            case Type.Struct s -> {
                for (Type.Field f : s.def().fields()) {
                    pointerOffsets(f.type(), base + f.offset(), out);
                }
            }
            default -> { }
        }
    }

    /**
     * Stops at the first function pointer used as anything but the callee of a call from its
     * table: read into a value, compared, converted, assigned, dereferenced.
     */
    public static void checkUses(Program program) throws CompileError {
        for (Function f : program.functions()) {
            if (f.isDefined()) {
                check(f.body());
            }
        }
    }

    private static void check(Stmt s) throws CompileError {
        for (Expr e : Children.exprsOf(s)) {
            check(e);
        }
        for (Stmt c : Children.stmtsOf(s)) {
            check(c);
        }
    }

    private static void check(Expr e) throws CompileError {
        if (e instanceof Expr.TableCall c) {
            // the callee itself is the one allowed use; its indexes are ordinary values
            checkChain(c.callee());
            for (Expr a : c.args()) {
                check(a);
            }
            return;
        }
        if (Type.isFunctionPointer(e.type()) || e.type() instanceof Type.Func) {
            throw new CompileError(e.pos(), "a function pointer can only be called, straight from its constant table "
                    + "(table[i](...)); it cannot be read, compared, converted or stored");
        }
        for (Expr c : Children.of(e)) {
            check(c);
        }
    }

    private static void checkChain(Expr e) throws CompileError {
        switch (e) {
            case Expr.Index i -> {
                checkChain(i.base());
                check(i.index());
            }
            case Expr.Member m -> checkChain(m.base());
            default -> { }
        }
    }
}
