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
 * <p>Run before the optimizer: a function that is called keeps its body even where every call is
 * inlined, so the timing report still has it (budgets, the C guide's measurements). A global that
 * no function names at all stays: a test or a debugger may read it by its name.
 */
public final class Reachable {
    private Reachable() {}

    /** Removes from {@code m} what {@code roots} do not reach; returns how many functions and globals went. */
    public static int run(MModule m, Collection<String> roots) {
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
}
