package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pointer compression within a function: a pointer φ whose values all point into the same global
 * object or frame slot (it starts at the object and moves by ptradds, like {@code o = oamMemory;
 * ... o += 4}) becomes the object's address plus a 16-bit offset φ. The 4-byte pointer, its copies
 * and its additions go; loads and stores through it index the object with X.
 *
 * <p>This relies on C's rule that a pointer stays within its object (or just past its end),
 * which dampf already relies on for 16-bit pointer offsets (docs/memory-map.md): the offset is then
 * always between 0 and the object's size, and the new φ carries that range
 * ({@link Instr.Phi#known}), which indexed addressing needs (the index must not be negative).
 */
public final class PointerOffsets {
    /** A pointer value's object: a GlobalAddr or SlotAddr; CONFLICT if it can point elsewhere. */
    private static final Value CONFLICT = new Value.Undef(MType.PTR);

    private final MModule module;
    private final Map<Value, Value> root = new IdentityHashMap<>();
    private final Map<Value, Value> offset = new IdentityHashMap<>();

    private PointerOffsets(MModule module) {
        this.module = module;
    }

    /** Returns whether anything changed. */
    public static boolean run(MModule m, MFunction f) {
        PointerOffsets p = new PointerOffsets(m);
        List<Instr.Phi> phis = new ArrayList<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Phi phi && phi.type() == MType.PTR) {
                    phis.add(phi);
                }
            }
        }
        if (phis.isEmpty()) {
            return false;
        }
        p.roots(phis);
        Map<Instr, Value> replaced = new HashMap<>();
        for (Instr.Phi phi : phis) {
            Value r = p.root.get(phi);
            if (r == null || r == CONFLICT) {
                continue;
            }
            Value off = p.offsetOf(phi, r);
            Block b = phi.block();
            Instr first = firstNonPhi(b);
            Instr ptr = new Instr.PtrAdd(r, off, phi.pos());
            b.insertBefore(first, ptr);
            replaced.put(phi, ptr);
        }
        DeadCode.replaceUses(f, replaced);
        return !replaced.isEmpty();
    }

    private static Instr firstNonPhi(Block b) {
        for (Instr i : b.instrs()) {
            if (!(i instanceof Instr.Phi)) {
                return i;
            }
        }
        throw new IllegalStateException("block " + b + " has only φs");
    }

    /** The object each pointer φ points into, by iterating until nothing changes. */
    private void roots(List<Instr.Phi> phis) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Instr.Phi phi : phis) {
                Value r = null;
                for (Value v : phi.operands()) {
                    Value o = rootOf(v);
                    if (o == null) {
                        continue;   // not known yet (another φ), or any value at all (undef)
                    }
                    r = r == null || r.equals(o) ? o : CONFLICT;
                }
                if (r != null && !r.equals(root.get(phi))) {
                    root.put(phi, r);
                    changed = true;
                }
            }
        }
    }

    private Value rootOf(Value v) {
        return switch (v) {
            case Value.GlobalAddr g -> module.globals().stream().anyMatch(x -> x.symbol().equals(g.symbol())) ? g : CONFLICT;
            case Value.SlotAddr s -> s;
            case Value.Undef u -> null;
            case Instr.PtrAdd p -> {
                Value r = rootOf(p.op(0));
                yield r == null ? null : r;
            }
            case Instr.Phi p when p.type() == MType.PTR -> root.get(p);
            default -> CONFLICT;
        };
    }

    private int size(Value r) {
        return switch (r) {
            case Value.GlobalAddr g -> module.globals().stream().filter(x -> x.symbol().equals(g.symbol()))
                    .findFirst().orElseThrow().size();
            case Value.SlotAddr s -> s.slot().size();
            default -> throw new IllegalStateException("no object: " + r);
        };
    }

    /** The 16-bit offset of pointer v (into r), as a value available where v is. */
    private Value offsetOf(Value v, Value r) {
        Value known = offset.get(v);
        if (known != null) {
            return known;
        }
        Value result = switch (v) {
            case Value.GlobalAddr g -> new Value.Const(MType.I16, 0);
            case Value.SlotAddr s -> new Value.Const(MType.I16, 0);
            case Value.Undef u -> new Value.Undef(MType.I16);
            case Instr.PtrAdd p -> {
                Value base = offsetOf(p.op(0), r);
                Value k = p.op(1);
                if (k.type() == MType.I32) {
                    k = k instanceof Value.Const c ? new Value.Const(MType.I16, c.bits())
                            : insert(p, new Instr.Cast(Instr.CastKind.TRUNC, k, MType.I16, p.pos()));
                }
                yield base instanceof Value.Const b0 && b0.bits() == 0 ? k : insert(p, new Instr.Bin(Instr.BinOp.ADD, base, k, p.pos()));
            }
            case Instr.Phi p -> {
                Instr.Phi o = p.block().add(new Instr.Phi(MType.I16, p.pos()));
                o.known = new Ranges.Range(0, size(r));   // within the object, or just past its end
                offset.put(v, o);   // before the operands: a loop leads back here
                for (int k = 0; k < p.from.size(); k++) {
                    o.add(p.from.get(k), offsetOf(p.op(k), r));
                }
                yield o;
            }
            default -> throw new IllegalStateException("not based on " + r + ": " + v);
        };
        offset.put(v, result);
        return result;
    }

    private static Instr insert(Instr at, Instr i) {
        at.block().insertBefore(at, i);
        return i;
    }
}
