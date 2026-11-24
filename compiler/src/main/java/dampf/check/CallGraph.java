package dampf.check;

import dampf.ast.Children;
import dampf.ast.Expr;
import dampf.ast.Function;
import dampf.ast.Interrupt;
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

    /**
     * The interrupt context of every function an interrupt handler reaches (DAMPF_INTERRUPT); the
     * functions the entry reaches, and those nobody calls, are the main code's and not in the map.
     * Each context gets frames, a direct page and scratch registers of its own (ADR-6FM9TXV4), so a
     * function reached from two of them is an error, as are a handler that C code calls, a second
     * handler of the same interrupt and an entry function marked as a handler.
     */
    public Map<Function, Interrupt> contexts(Function entry) throws CompileError {
        if (entry.interrupt() != null) {
            throw new CompileError(entry.definitionPos(), "the entry function '" + entry.name()
                    + "' cannot be an interrupt handler (DAMPF_INTERRUPT)");
        }
        Map<Interrupt, Function> handlers = new java.util.EnumMap<>(Interrupt.class);
        for (Function f : calls.keySet()) {
            if (f.interrupt() == null) {
                continue;
            }
            Function first = handlers.putIfAbsent(f.interrupt(), f);
            if (first != null) {
                throw new CompileError(f.definitionPos(), "'" + f.name() + "' is a second " + f.interrupt().name()
                        + " handler; the first is '" + first.name() + "' at " + first.definitionPos());
            }
        }
        for (Function f : calls.keySet()) {
            for (Edge e : callsFrom(f)) {
                if (e.callee().interrupt() != null) {
                    throw new CompileError(e.pos(), "'" + e.callee().name() + "' is " + e.callee().interrupt().handler()
                            + " (DAMPF_INTERRUPT): the hardware calls it, C code cannot");
                }
            }
        }
        Map<Function, Edge> mainReach = reach(entry);
        Map<Function, Interrupt> out = new LinkedHashMap<>();
        Map<Interrupt, Map<Function, Edge>> reached = new java.util.EnumMap<>(Interrupt.class);
        for (Map.Entry<Interrupt, Function> h : handlers.entrySet()) {
            Map<Function, Edge> mine = reach(h.getValue());
            reached.put(h.getKey(), mine);
            for (Function f : mine.keySet()) {
                String other = null;
                List<Function> otherPath = null;
                if (mainReach.containsKey(f)) {
                    other = "the main code";
                    otherPath = path(f, mainReach);
                } else if (out.containsKey(f)) {
                    other = out.get(f).handler();
                    otherPath = path(f, reached.get(out.get(f)));
                }
                if (other != null) {
                    Edge last = mine.get(f);
                    throw new CompileError(last.pos(), "'" + f.name() + "' is called from two contexts, " + other + " ("
                            + names(otherPath) + ") and " + h.getKey().handler() + " (" + names(path(f, mine))
                            + "); a function belongs to one context, so each needs a copy of its own (docs/subset.md, \"Interrupt handlers\")");
                }
                out.put(f, h.getKey());
            }
        }
        return out;
    }

    /** Every function root reaches, root included, with the edge it was first reached by (null for root). */
    private Map<Function, Edge> reach(Function root) {
        Map<Function, Edge> seen = new LinkedHashMap<>();
        seen.put(root, null);
        java.util.ArrayDeque<Function> work = new java.util.ArrayDeque<>(List.of(root));
        Map<Function, Function> parent = new HashMap<>();
        while (!work.isEmpty()) {
            Function f = work.poll();
            for (Edge e : callsFrom(f)) {
                if (!seen.containsKey(e.callee())) {
                    seen.put(e.callee(), e);
                    parent.put(e.callee(), f);
                    work.add(e.callee());
                }
            }
        }
        parents.put(root, parent);
        return seen;
    }

    private final Map<Function, Map<Function, Function>> parents = new HashMap<>();

    /** The way from the root of {@code reached} to f. */
    private List<Function> path(Function f, Map<Function, Edge> reached) {
        Function root = reached.entrySet().stream().filter(e -> e.getValue() == null).findFirst().orElseThrow().getKey();
        Map<Function, Function> parent = parents.get(root);
        List<Function> p = new ArrayList<>();
        for (Function x = f; x != null; x = parent.get(x)) {
            p.addFirst(x);
        }
        return p;
    }

    private static String names(List<Function> path) {
        return String.join(" → ", path.stream().map(Function::name).toList());
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
