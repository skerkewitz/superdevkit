package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parameter ranges from the call sites: an integer parameter lies in the join of the ranges its
 * argument has at every call ({@link Value.Param#known}). dampf sees the whole program and C
 * functions are only called directly, so the calls in the module are all there are. A function
 * without calls (the entry function) keeps whole ranges.
 *
 * <p>Callers are handled before callees (the subset has no recursion, so the call graph has no
 * cycles): a caller's own parameter ranges are known when its arguments are evaluated. A range
 * the parameter already has stays as far as it holds too (both are facts about every call).
 */
public final class ParamRanges {
    private ParamRanges() {}

    /** Returns whether any parameter got a narrower range. */
    public static boolean run(MModule m) {
        Map<String, MFunction> byName = new HashMap<>();
        for (MFunction f : m.functions()) {
            byName.put(f.name(), f);
        }
        Map<MFunction, List<Instr.Call>> sites = new LinkedHashMap<>();
        Map<MFunction, Set<MFunction>> callees = new HashMap<>();
        Map<Instr.Call, MFunction> callerOf = new HashMap<>();
        for (MFunction f : m.functions()) {
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Call c && byName.get(c.callee) instanceof MFunction g) {
                        sites.computeIfAbsent(g, k -> new ArrayList<>()).add(c);
                        callerOf.put(c, f);
                        callees.computeIfAbsent(f, k -> new HashSet<>()).add(g);
                    }
                }
            }
        }
        // callers first: the reverse of a postorder over the call edges
        List<MFunction> post = new ArrayList<>();
        Set<MFunction> seen = new HashSet<>();
        for (MFunction f : m.functions()) {
            postorder(f, callees, seen, post);
        }
        Map<MFunction, Ranges> ranges = new HashMap<>();
        boolean changed = false;
        for (int n = post.size() - 1; n >= 0; n--) {
            MFunction f = post.get(n);
            List<Instr.Call> calls = sites.getOrDefault(f, List.of());
            if (calls.isEmpty()) {
                continue;
            }
            for (int k = 0; k < f.params().size(); k++) {
                Value.Param p = f.params().get(k);
                if (!Ranges.isInt(p.type())) {
                    continue;
                }
                Ranges.Range r = null;
                for (Instr.Call c : calls) {
                    MFunction caller = callerOf.get(c);
                    Ranges rc = ranges.computeIfAbsent(caller, Ranges::new);
                    Ranges.Range a = rc.at(c.op(k), c.block());
                    if (a == null) {
                        continue;   // the call is never reached
                    }
                    r = a.join(r);
                }
                if (r == null) {
                    continue;
                }
                if (p.known != null) {
                    Ranges.Range both = r.meet(p.known);
                    r = both != null ? both : p.known;
                }
                Ranges.Range all = Ranges.full(p.type());
                if (r.equals(all) || r.equals(p.known)) {
                    continue;
                }
                p.known = r;
                changed = true;
            }
        }
        return changed;
    }

    private static void postorder(MFunction f, Map<MFunction, Set<MFunction>> callees, Set<MFunction> seen,
                                  List<MFunction> out) {
        if (!seen.add(f)) {
            return;
        }
        for (MFunction g : callees.getOrDefault(f, Set.of())) {
            postorder(g, callees, seen, out);
        }
        out.add(f);
    }
}
