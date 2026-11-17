package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the rules of MIR. Run after the lowering and after every pass, so a broken pass is caught
 * where it breaks something, not three levels later as a wrong result.
 *
 * <ul>
 * <li>Blocks: φs first, exactly one terminator, at the end; every block reachable from the entry;
 *     the predecessor lists agree with the terminators.</li>
 * <li>φs: one operand for each predecessor.</li>
 * <li>Types: the operands of each instruction have the types it needs; returns match the function;
 *     calls match their callee.</li>
 * <li>SSA: every value is defined before it is used, in a block that dominates the use (for a φ
 *     operand: dominates the end of the predecessor it comes from).</li>
 * </ul>
 */
public final class Verifier {
    private final MModule module;
    private final List<String> errors = new ArrayList<>();

    private Verifier(MModule module) {
        this.module = module;
    }

    /** All violations, empty if the module is fine. */
    public static List<String> check(MModule module) {
        Verifier v = new Verifier(module);
        for (MFunction f : module.functions()) {
            v.function(f);
        }
        return v.errors;
    }

    /** Throws if the module breaks a rule. */
    public static void verify(MModule module) {
        List<String> e = check(module);
        if (!e.isEmpty()) {
            throw new IllegalStateException("MIR is broken:\n  " + String.join("\n  ", e.subList(0, Math.min(20, e.size()))));
        }
    }

    private void error(MFunction f, Block b, Instr i, String message) {
        f.number();
        errors.add(f.name() + " " + (b == null ? "" : b.name() + ": ") + (i == null ? "" : Printer.instr(i) + ": ") + message);
    }

    private void function(MFunction f) {
        if (f.blocks().isEmpty()) {
            error(f, null, null, "no blocks");
            return;
        }
        Set<Block> own = new HashSet<>(f.blocks());
        // structure
        for (Block b : f.blocks()) {
            List<Instr> is = b.instrs();
            if (is.isEmpty() || !is.getLast().isTerminator()) {
                error(f, b, null, "does not end with a terminator");
            }
            boolean phis = true;
            for (int k = 0; k < is.size(); k++) {
                Instr i = is.get(k);
                if (i.block() != b) {
                    error(f, b, i, "belongs to another block");
                }
                if (i.isTerminator() && k != is.size() - 1) {
                    error(f, b, i, "terminator in the middle of the block");
                }
                if (i instanceof Instr.Phi) {
                    if (!phis) {
                        error(f, b, i, "phi after other instructions");
                    }
                } else {
                    phis = false;
                }
                for (Block s : i.successors()) {
                    if (!own.contains(s)) {
                        error(f, b, i, "jumps to a block of another function");
                    }
                }
            }
        }
        // predecessors
        Map<Block, List<Block>> preds = new HashMap<>();
        for (Block b : f.blocks()) {
            preds.put(b, new ArrayList<>());
        }
        for (Block b : f.blocks()) {
            Instr t = b.terminator();
            if (t != null) {
                for (Block s : t.successors()) {
                    preds.get(s).add(b);
                }
            }
        }
        for (Block b : f.blocks()) {
            if (!sameMultiset(preds.get(b), b.preds())) {
                error(f, b, null, "predecessor list " + b.preds() + " does not match the terminators " + preds.get(b));
            }
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Phi p && !sameMultiset(p.from, b.preds())) {
                    error(f, b, i, "phi operands come from " + p.from + ", the predecessors are " + b.preds());
                }
            }
        }
        // reachability and dominators
        Map<Block, Set<Block>> dom = dominators(f);
        for (Block b : f.blocks()) {
            if (!dom.containsKey(b)) {
                error(f, b, null, "unreachable");
            }
        }
        // types and SSA
        Map<Instr, Integer> index = new HashMap<>();
        for (Block b : f.blocks()) {
            for (int k = 0; k < b.instrs().size(); k++) {
                index.put(b.instrs().get(k), k);
            }
        }
        Set<Value.Param> params = new HashSet<>(f.params());
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                types(f, b, i);
                for (int k = 0; k < i.operands().size(); k++) {
                    Value v = i.op(k);
                    if (v instanceof Value.Param p && !params.contains(p)) {
                        error(f, b, i, "uses a parameter of another function");
                    }
                    if (v instanceof Instr d) {
                        if (!index.containsKey(d)) {
                            error(f, b, i, "uses an instruction that is not in the function");
                            continue;
                        }
                        Block use = i instanceof Instr.Phi p ? p.from.get(k) : b;
                        boolean ok;
                        if (d.block() == use && !(i instanceof Instr.Phi)) {
                            ok = index.get(d) < index.get(i);
                        } else {
                            Set<Block> doms = dom.get(use);
                            ok = doms != null && doms.contains(d.block());
                        }
                        if (!ok) {
                            error(f, b, i, "uses " + d + " where its definition does not dominate the use");
                        }
                    }
                }
            }
        }
    }

    private void types(MFunction f, Block b, Instr i) {
        switch (i) {
            case Instr.Bin x -> {
                if (!isInt(x.type()) || x.op(0).type() != x.type() || x.op(1).type() != x.type()) {
                    error(f, b, i, "operands must both have the result's integer type");
                }
            }
            case Instr.Cmp c -> {
                MType t = c.op(0).type();
                if (c.op(1).type() != t || !(isInt(t) || t == MType.PTR)) {
                    error(f, b, i, "operands must have the same integer or pointer type");
                }
            }
            case Instr.Cast c -> {
                MType from = c.op(0).type();
                MType to = c.type();
                boolean ok = switch (c.kind) {
                    case ZEXT, SEXT -> (isInt(from) || from == MType.I1) && isInt(to) && from.bits() < to.bits();
                    case TRUNC -> isInt(from) && (isInt(to) || to == MType.I1) && from.bits() > to.bits();
                    case PTRTOINT -> from == MType.PTR && to == MType.I32;
                    case INTTOPTR -> from == MType.I32 && to == MType.PTR;
                };
                if (!ok) {
                    error(f, b, i, "invalid " + c.kind + " from " + from + " to " + to);
                }
            }
            case Instr.PtrAdd p -> {
                if (p.op(0).type() != MType.PTR || !(p.op(1).type() == MType.I32 || p.op(1).type() == MType.I16)) {
                    error(f, b, i, "ptradd needs a pointer and an i32 or i16 offset");
                }
            }
            case Instr.Load l -> {
                if (l.op(0).type() != MType.PTR || !(isInt(l.type()) || l.type() == MType.PTR)) {
                    error(f, b, i, "load needs a pointer and an integer or pointer type");
                }
            }
            case Instr.Store s -> {
                MType t = s.op(1).type();
                if (s.op(0).type() != MType.PTR || !(isInt(t) || t == MType.PTR)) {
                    error(f, b, i, "store needs a pointer and an integer or pointer value");
                }
            }
            case Instr.Copy c -> {
                if (c.op(0).type() != MType.PTR || c.op(1).type() != MType.PTR || c.size <= 0) {
                    error(f, b, i, "copy needs two pointers and a size");
                }
            }
            case Instr.Call c -> {
                MFunction callee = module.function(c.callee);
                if (callee == null) {
                    error(f, b, i, "calls an unknown function");
                } else if (callee.result() != c.type() || callee.params().size() != c.operands().size()) {
                    error(f, b, i, "does not match " + c.callee + "'s signature");
                } else {
                    for (int k = 0; k < c.operands().size(); k++) {
                        if (c.op(k).type() != callee.params().get(k).type()) {
                            error(f, b, i, "argument " + (k + 1) + " has the wrong type");
                        }
                    }
                }
            }
            case Instr.Phi p -> {
                for (Value v : p.operands()) {
                    if (v.type() != p.type()) {
                        error(f, b, i, "phi operand " + v + " has type " + v.type());
                    }
                }
            }
            case Instr.Branch br -> {
                if (br.op(0).type() != MType.I1) {
                    error(f, b, i, "branch condition must be i1");
                }
            }
            case Instr.Ret r -> {
                MType t = r.operands().isEmpty() ? MType.VOID : r.op(0).type();
                if (t != f.result()) {
                    error(f, b, i, "returns " + t + " from a function returning " + f.result());
                }
            }
            case Instr.Switch s -> {
                if (!isInt(s.op(0).type())) {
                    error(f, b, i, "switch on a non-integer");
                }
            }
            case Instr.Jump j -> { }
        }
    }

    private static boolean isInt(MType t) {
        return t == MType.I8 || t == MType.I16 || t == MType.I32;
    }

    private static boolean sameMultiset(List<Block> a, List<Block> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<Block> rest = new ArrayList<>(b);
        for (Block x : a) {
            if (!rest.remove(x)) {
                return false;
            }
        }
        return true;
    }

    /** Dominator sets of the reachable blocks (the simple iterative algorithm; functions are small). */
    static Map<Block, Set<Block>> dominators(MFunction f) {
        List<Block> reachable = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        List<Block> work = new ArrayList<>(List.of(f.entry()));
        while (!work.isEmpty()) {
            Block b = work.removeLast();
            if (seen.add(b)) {
                reachable.add(b);
                Instr t = b.terminator();
                if (t != null) {
                    work.addAll(t.successors());
                }
            }
        }
        Map<Block, Set<Block>> dom = new HashMap<>();
        for (Block b : reachable) {
            dom.put(b, new HashSet<>(reachable));
        }
        dom.put(f.entry(), new HashSet<>(Set.of(f.entry())));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Block b : reachable) {
                if (b == f.entry()) {
                    continue;
                }
                Set<Block> d = null;
                for (Block p : b.preds()) {
                    if (!dom.containsKey(p)) {
                        continue; // an unreachable predecessor
                    }
                    if (d == null) {
                        d = new HashSet<>(dom.get(p));
                    } else {
                        d.retainAll(dom.get(p));
                    }
                }
                if (d == null) {
                    d = new HashSet<>();
                }
                d.add(b);
                if (!d.equals(dom.get(b))) {
                    dom.put(b, d);
                    changed = true;
                }
            }
        }
        return dom;
    }
}
