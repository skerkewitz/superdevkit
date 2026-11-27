package dampf.mir;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Drops the functions the program cannot reach, and the globals only they used (dead-code
 * elimination over the whole program). The roots are the entry, the interrupt handlers and what
 * hand-written assembly names; from them a function reaches what it calls and every symbol whose
 * address it takes, and a global what its initial contents point to. What is left out takes
 * neither ROM nor RAM: a library's functions that a game does not call (the console API's
 * optional layers, the debug calls, save RAM), and the variables only they use.
 *
 * <p>Run before the optimizer, so a function that is called stays in the module even where every
 * call is inlined: its body is compiled and timed for the report (budgets, the C guide's
 * measurements). After the optimizer, {@link #needCode} tells which of them need code of their
 * own in the ROM. A global that no function names at all stays: a test or a debugger may read it
 * by its name.
 */
public final class Reachable {
    private Reachable() {}

    /** Removes from {@code m} what {@code roots} do not reach; returns how many functions and globals went. */
    public static int run(MModule m, Collection<String> roots) {
        Set<String> reached = reached(m, roots);
        Set<String> usedByDropped = new HashSet<>();
        for (MFunction f : m.functions()) {
            if (!reached.contains(f.name())) {
                for (Block b : f.blocks()) {
                    for (Instr i : b.instrs()) {
                        for (Value v : i.operands()) {
                            if (v instanceof Value.GlobalAddr g) {
                                usedByDropped.add(g.symbol());
                            }
                        }
                    }
                }
            }
        }
        int before = m.functions().size() + m.globals().size();
        m.functions().removeIf(f -> !reached.contains(f.name()));
        m.globals().removeIf(g -> !reached.contains(g.symbol()) && usedByDropped.contains(g.symbol()));
        return before - m.functions().size() - m.globals().size();
    }

    /**
     * The functions of {@code m} that need code of their own in the ROM, after the optimizer: those
     * the roots, and every global the module keeps, reach through calls and taken addresses. The
     * others are inlined at every call (or their calls went with dead code): their bodies are still
     * compiled and timed, so the timing report and DAMPF_BUDGET keep them (ADR-6FVMCY48),
     * but no code of theirs goes into the ROM.
     */
    public static Set<String> needCode(MModule m, Collection<String> roots) {
        Set<String> all = new HashSet<>(roots);
        for (MModule.Global g : m.globals()) {
            all.add(g.symbol());   // what stays in the ROM may point to a function
        }
        Set<String> reached = reached(m, all);
        Set<String> out = new HashSet<>();
        for (MFunction f : m.functions()) {
            if (reached.contains(f.name())) {
                out.add(f.name());
            }
        }
        return out;
    }

    /** Every symbol {@code roots} reach through calls, taken addresses and the globals' initial contents. */
    static Set<String> reached(MModule m, Collection<String> roots) {
        Map<String, MFunction> functions = new HashMap<>();
        for (MFunction f : m.functions()) {
            functions.put(f.name(), f);
        }
        Map<String, MModule.Global> globals = new HashMap<>();
        for (MModule.Global g : m.globals()) {
            globals.put(g.symbol(), g);
        }
        Set<String> reached = new HashSet<>();
        Deque<String> work = new ArrayDeque<>(roots);
        while (!work.isEmpty()) {
            String s = work.pop();
            if (!reached.add(s)) {
                continue;
            }
            MFunction f = functions.get(s);
            if (f != null) {
                for (Block b : f.blocks()) {
                    for (Instr i : b.instrs()) {
                        if (i instanceof Instr.Call c) {
                            work.push(c.callee);
                        }
                        for (Value v : i.operands()) {
                            if (v instanceof Value.GlobalAddr g) {
                                work.push(g.symbol());
                            }
                        }
                    }
                }
            }
            MModule.Global g = globals.get(s);
            if (g != null) {
                for (MModule.Datum d : g.init()) {
                    if (d.symbol() != null) {
                        work.push(d.symbol());
                    }
                }
            }
        }
        return reached;
    }
}