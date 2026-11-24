package dampf.mir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pointer compression across calls: a pointer parameter that every call fills with a pointer
 * into the same global object becomes a 16-bit offset into that object. The callee adds the
 * object's address back at its start ({@code ptradd @object, offset}), so indexed addressing
 * applies there, and the callers pass two bytes instead of four.
 *
 * <p>Like {@link PointerOffsets} it relies on C's rule that a pointer stays within its object (or
 * just past its end): the offset lies between 0 and the object's size, which the parameter
 * carries ({@link Value.Param#known}). Callers are handled before callees, so a pointer the caller
 * itself got as a compressed parameter counts as pointing into the object too.
 */
public final class PointerParams {
    private PointerParams() {}

    /** Returns whether anything changed. */
    public static boolean run(MModule m) {
        boolean changed = false;
        Map<String, List<Instr.Call>> calls = new java.util.HashMap<>();
        for (MFunction f : m.functions()) {
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Call c) {
                        calls.computeIfAbsent(c.callee, k -> new ArrayList<>()).add(c);
                    }
                }
            }
        }
        java.util.Set<String> tables = m.tableFunctions();
        for (MFunction f : m.functions()) {
            List<Instr.Call> sites = calls.getOrDefault(f.name(), List.of());
            if (sites.isEmpty() || tables.contains(f.name())) {
                continue;   // the entry function, never called, or reached through a table, whose calls pass pointers
            }
            for (int k = 0; k < f.params().size(); k++) {
                Value.Param old = f.params().get(k);
                if (old.type() != MType.PTR) {
                    continue;
                }
                Value.GlobalAddr root = null;
                boolean ok = true;
                for (Instr.Call c : sites) {
                    Value.GlobalAddr r = rootOf(m, c.op(k));
                    if (r == null || root != null && !root.equals(r)) {
                        ok = false;
                        break;
                    }
                    root = r;
                }
                if (!ok || root == null) {
                    continue;
                }
                Value.Param offset = new Value.Param(old.name(), MType.I16);
                offset.known = new Ranges.Range(0, size(m, root));
                f.params().set(k, offset);
                Block entry = f.entry();
                Instr first = entry.instrs().getFirst();
                Instr ptr = new Instr.PtrAdd(root, offset, f.pos());
                entry.insertBefore(first, ptr);
                for (Block b : f.blocks()) {
                    for (Instr i : b.instrs()) {
                        for (int j = 0; j < i.operands().size(); j++) {
                            if (i.op(j) == old) {
                                i.setOp(j, ptr);
                            }
                        }
                    }
                }
                for (Instr.Call c : sites) {
                    c.setOp(k, offsetOf(c.op(k), c));
                }
                changed = true;
            }
        }
        return changed;
    }

    /** The global object a pointer points into: the address itself, or ptradds of it. */
    private static Value.GlobalAddr rootOf(MModule m, Value v) {
        while (v instanceof Instr.PtrAdd p) {
            v = p.op(0);
        }
        if (v instanceof Value.GlobalAddr g && m.globals().stream().anyMatch(x -> x.symbol().equals(g.symbol()))) {
            return g;
        }
        return null;
    }

    private static int size(MModule m, Value.GlobalAddr g) {
        return m.globals().stream().filter(x -> x.symbol().equals(g.symbol())).findFirst().orElseThrow().size();
    }

    /** The 16-bit offset of a pointer (a chain of ptradds from the object), computed before {@code at}. */
    private static Value offsetOf(Value v, Instr at) {
        if (v instanceof Value.GlobalAddr) {
            return new Value.Const(MType.I16, 0);
        }
        Instr.PtrAdd p = (Instr.PtrAdd) v;
        Value base = offsetOf(p.op(0), at);
        Value k = p.op(1);
        if (k.type() == MType.I32) {
            if (k instanceof Value.Const c) {
                k = new Value.Const(MType.I16, c.bits());
            } else {
                Instr t = new Instr.Cast(Instr.CastKind.TRUNC, k, MType.I16, at.pos());
                at.block().insertBefore(at, t);
                k = t;
            }
        }
        if (base instanceof Value.Const b0 && b0.bits() == 0) {
            return k;
        }
        Instr sum = new Instr.Bin(Instr.BinOp.ADD, base, k, at.pos());
        at.block().insertBefore(at, sum);
        return sum;
    }
}
