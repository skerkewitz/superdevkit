package dampf;

import dampf.asm.AsmFunction;
import dampf.ast.Children;
import dampf.ast.Expr;
import dampf.ast.Function;
import dampf.ast.Program;
import dampf.ast.Stmt;
import dampf.ast.Type;
import dampf.ast.Variable;
import dampf.emu.AsmCheck;
import dampf.frontend.CompileError;
import dampf.frontend.SourcePos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The functions of a program written in assembly (DAMPF_ASM, or {@code --replace} on the command
 * line, which wins): where their files are, what {@link AsmFunction} found in them, and the data
 * for the check against their C bodies ({@link AsmCheck}). See docs/hints.md, "DAMPF_ASM".
 */
final class AsmFunctions {
    /** One assembly function: the C function, its file (as found, and as named in messages), its text, the analysis. */
    record Asm(Function function, Path file, String name, String text, AsmFunction.Result result) {
        /** The symbols of its parameters' places, in order. */
        List<String> paramSymbols() {
            return function.params().stream().map(Variable::label).toList();
        }
    }

    private AsmFunctions() {}

    /** Finds, reads and analyses the program's assembly functions (by C name, in the program's order). */
    static Map<String, Asm> collect(Program program, Compiler.Options o, Function entry) throws CompileError, IOException {
        for (String name : o.replacements().keySet()) {
            Function f = program.function(name);
            if (f == null || !f.isDefined()) {
                throw new CompileError(new SourcePos(o.sources().getFirst().toString(), 1, 1),
                        "--replace " + name + ": there is no function '" + name + "' to replace");
            }
        }
        Map<String, Asm> out = new LinkedHashMap<>();
        Set<Path> seen = new HashSet<>();
        for (Function f : program.functions()) {
            if (!f.isDefined()) {
                continue;
            }
            Path file;
            String shown;
            SourcePos at = f.definitionPos();
            if (o.replacements().containsKey(f.name())) {
                file = o.replacements().get(f.name());
                shown = file.toString();
            } else if (f.asmFile() != null) {
                Path dir = Path.of(at.file()).toAbsolutePath().getParent();
                file = dir == null ? Path.of(f.asmFile()) : dir.resolve(f.asmFile());
                Path parent = Path.of(at.file()).getParent();
                shown = parent == null ? f.asmFile() : parent.resolve(f.asmFile()).toString();
            } else {
                continue;
            }
            check(f, entry, at);
            if (!Files.isRegularFile(file)) {
                throw new CompileError(at, "the assembly of '" + f.name() + "', " + shown + ", does not exist"
                        + (o.replacements().containsKey(f.name()) ? "" : " (DAMPF_ASM names it relative to the C file)"));
            }
            String text = Files.readString(file);
            AsmFunction.Spec spec = new AsmFunction.Spec(f.name(), f.label(), params(f), otherFrames(program, f));
            AsmFunction.Result r = AsmFunction.analyse(shown, text, spec, o.fastRom());
            out.put(f.name(), new Asm(f, file.toAbsolutePath().normalize(), shown, seen.add(file.toAbsolutePath().normalize()) ? text : null, r));
        }
        return out;
    }

    private static List<AsmFunction.Param> params(Function f) {
        List<AsmFunction.Param> l = new ArrayList<>();
        for (Variable p : f.params()) {
            l.add(new AsmFunction.Param(p.label(), AsmFunction.paramBytes(p.type().size())));
        }
        return l;
    }

    /** What an assembly function can be: not the entry, integers and pointers in and out. */
    private static void check(Function f, Function entry, SourcePos at) throws CompileError {
        if (f == entry) {
            throw new CompileError(at, "the entry function '" + f.name() + "' cannot be written in assembly");
        }
        if (!(f.returnType() instanceof Type.Void || f.returnType() instanceof Type.Int || f.returnType() instanceof Type.Pointer)) {
            throw new CompileError(at, "'" + f.name() + "' returns " + f.returnType()
                    + "; a function written in assembly returns an integer, a pointer or nothing");
        }
        for (Variable p : f.params()) {
            if (!(p.type() instanceof Type.Int || p.type() instanceof Type.Pointer)) {
                throw new CompileError(p.pos(), "the parameter '" + p.name() + "' is " + p.type()
                        + "; a function written in assembly takes integers and pointers");
            }
            if (p.label().equals(f.label() + AsmFunction.LOCALS)) {
                throw new CompileError(p.pos(), "the parameter '" + p.name() + "' has the name of the locals' symbol "
                        + p.label() + " of an assembly function; rename it");
            }
        }
    }

    /** The symbols of the places of every other function's frame (the naive code generator's frames have them). */
    private static Set<String> otherFrames(Program program, Function f) {
        Set<String> s = new HashSet<>();
        for (Function g : program.functions()) {
            if (g != f && g.isDefined()) {
                g.params().forEach(v -> s.add(v.label()));
                g.locals().stream().filter(v -> v.kind() != Variable.Kind.STATIC_LOCAL).forEach(v -> s.add(v.label()));
                s.add(g.label() + AsmFunction.LOCALS);
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ the check against the C body

    /** The parameters for {@link AsmCheck}: sizes, signedness, and the range of a leading DAMPF_RANGE. */
    static List<AsmCheck.Param> checkParams(Function f) {
        Map<Variable, long[]> ranges = new LinkedHashMap<>();
        for (Stmt s : f.body().statements()) {
            if (!(s instanceof Stmt.Range r)) {
                break;   // only hints before the first statement hold for the parameter as passed
            }
            if (r.variable().kind() == Variable.Kind.PARAM) {
                ranges.putIfAbsent(r.variable(), new long[] {r.lo(), r.hi()});
            }
        }
        List<AsmCheck.Param> out = new ArrayList<>();
        for (Variable p : f.params()) {
            if (p.type() instanceof Type.Pointer) {
                out.add(new AsmCheck.Param(p.name(), 4, false, true, 0, 0));
                continue;
            }
            Type.Int t = (Type.Int) p.type();
            long lo = t.signed() ? -(1L << (t.bits() - 1)) : 0;
            long hi = t.signed() ? (1L << (t.bits() - 1)) - 1 : (1L << t.bits()) - 1;
            long[] r = ranges.get(p);
            if (r != null) {
                lo = Math.max(lo, r[0]);
                hi = Math.min(hi, r[1]);
            }
            out.add(new AsmCheck.Param(p.name(), t.size(), t.signed(), false, lo, hi));
        }
        return out;
    }

    /** The globals in RAM the C body reads or writes, directly or in the functions it calls. */
    static Set<Variable> globalsOf(Function f) {
        Set<Variable> out = new HashSet<>();
        visit(f, out, new HashSet<>());
        return out;
    }

    private static void visit(Function f, Set<Variable> out, Set<Function> seen) {
        if (!seen.add(f) || !f.isDefined()) {
            return;
        }
        visit(f.body(), out, seen);
    }

    private static void visit(Stmt s, Set<Variable> out, Set<Function> seen) {
        for (Expr e : Children.exprsOf(s)) {
            visit(e, out, seen);
        }
        for (Stmt c : Children.stmtsOf(s)) {
            visit(c, out, seen);
        }
    }

    private static void visit(Expr e, Set<Variable> out, Set<Function> seen) {
        if (e instanceof Expr.Var v && (v.variable().kind() == Variable.Kind.GLOBAL || v.variable().kind() == Variable.Kind.STATIC_LOCAL)) {
            out.add(v.variable());
        }
        if (e instanceof Expr.Call c) {
            visit(c.function(), out, seen);
        }
        for (Expr c : Children.of(e)) {
            visit(c, out, seen);
        }
    }

    /** Whether a value of the type holds a pointer (its random bytes would point anywhere, also at I/O registers). */
    static boolean holdsPointer(Type t) {
        return switch (t) {
            case Type.Pointer p -> true;
            case Type.Array a -> holdsPointer(a.element());
            case Type.Struct s -> s.def().fields().stream().anyMatch(x -> holdsPointer(x.type()));
            default -> false;
        };
    }
}
