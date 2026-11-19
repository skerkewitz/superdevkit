package dampf.check;

import dampf.ast.Children;
import dampf.ast.Expr;
import dampf.ast.Function;
import dampf.ast.Program;
import dampf.ast.Stmt;
import dampf.frontend.CompileError;
import dampf.frontend.SourcePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete call graph of the program. dampf can build it completely because there are no
 * free function pointers, and it must be free of cycles: static frames (every local at a fixed
 * address) are only correct without recursion.
 */
public final class CallGraph {
    /** A call from one function to another, at a place in the source. */
    public record Edge(Function callee, SourcePos pos) {}

    private final Map<Function, List<Edge>> calls = new LinkedHashMap<>();

    private CallGraph() {}

    public static CallGraph of(Program program) {
        CallGraph g = new CallGraph();
        for (Function f : program.functions()) {
            List<Edge> edges = new ArrayList<>();
            if (f.isDefined()) {
                collect(f.body(), edges);
            }
            g.calls.put(f, edges);
        }
        return g;
    }

    public List<Edge> callsFrom(Function f) {
        return calls.getOrDefault(f, List.of());
    }

    /** Stops with an error naming the cycle, e.g. {@code update_bubble → pop_chain → update_bubble}. */
    public void checkNoRecursion() throws CompileError {
        Map<Function, Integer> state = new HashMap<>(); // absent: new, 1: on the current path, 2: done
        for (Function f : calls.keySet()) {
            List<Function> path = new ArrayList<>();
            CompileError e = visit(f, state, path);
            if (e != null) {
                throw e;
            }
        }
    }

    private CompileError visit(Function f, Map<Function, Integer> state, List<Function> path) {
        Integer s = state.get(f);
        if (s != null && s == 2) {
            return null;
        }
        state.put(f, 1);
        path.add(f);
        for (Edge e : callsFrom(f)) {
            Integer cs = state.get(e.callee());
            if (cs != null && cs == 1) {
                List<Function> cycle = new ArrayList<>(path.subList(path.indexOf(e.callee()), path.size()));
                cycle.add(e.callee());
                String names = String.join(" → ", cycle.stream().map(Function::name).toList());
                return new CompileError(e.pos(), "recursion is not supported: " + names);
            }
            CompileError err = visit(e.callee(), state, path);
            if (err != null) {
                return err;
            }
        }
        path.removeLast();
        state.put(f, 2);
        return null;
    }

    private static void collect(Stmt s, List<Edge> out) {
        Children.exprsOf(s).forEach(e -> collect(e, out));
        Children.stmtsOf(s).forEach(x -> collect(x, out));
    }

    private static void collect(Expr e, List<Edge> out) {
        Children.of(e).forEach(x -> collect(x, out));
        if (e instanceof Expr.Call c) {
            out.add(new Edge(c.function(), c.pos()));
        }
    }
}
