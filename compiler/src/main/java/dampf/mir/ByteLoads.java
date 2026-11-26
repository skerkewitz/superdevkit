package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 16-bit value read for its bytes (catalogue E). A 16-bit load whose uses shift it right by 8 or
 * more, or take its low byte (a truncation to 8 bits), and which has at least one such shift,
 * becomes byte loads: of its high byte (little endian: the address plus one), widened again, for
 * the shifts, and of its low byte for the truncations, both where the load was ({@link Schedule}
 * moves each to its use). On the 65816 that
 * saves the XBA and the mask the shift costs ({@code PX(b->x)} is {@code b->x >> 8} all over the
 * game), and a value written to a register in two halves ({@code sdevkit_reg_write_twice}: the low
 * byte, then the high one) is two byte loads in a run of 8-bit code instead of a 16-bit load kept
 * in X while the accumulator changes its width. Volatile loads stay as they are: a hardware
 * register may care how it is read.
 */
public final class ByteLoads {
    private ByteLoads() {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Map<Instr.Load, List<Instr>> users = new LinkedHashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Load l && !l.isVolatile && l.type() == MType.I16) {
                    users.putIfAbsent(l, new ArrayList<>());
                }
            }
        }
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    if (v instanceof Instr.Load l && users.containsKey(l)) {
                        users.get(l).add(i);
                    }
                }
            }
        }
        Map<Instr, Value> replaced = new HashMap<>();
        for (Map.Entry<Instr.Load, List<Instr>> e : users.entrySet()) {
            Instr.Load l = e.getKey();
            List<Instr> use = e.getValue();
            if (l.block() == null || use.isEmpty() || !use.stream().allMatch(u -> isShift(u, l) || isLowByte(u, l))
                    || use.stream().noneMatch(u -> isShift(u, l))) {
                continue;
            }
            Instr high = new Instr.PtrAdd(l.op(0), new Value.Const(MType.I16, 1), l.pos());
            Instr highLoad = new Instr.Load(MType.I8, high, false, l.pos());
            l.block().insertBefore(l, high);
            l.block().insertBefore(l, highLoad);
            Instr lowLoad = null;
            for (Instr u : use) {
                if (isLowByte(u, l)) {
                    if (lowLoad == null) {
                        lowLoad = new Instr.Load(MType.I8, l.op(0), false, l.pos());
                        l.block().insertBefore(l, lowLoad);
                    }
                    replaced.put(u, lowLoad);
                    continue;
                }
                Instr.Bin s = (Instr.Bin) u;
                long k = ((Value.Const) s.op(1)).bits();
                Instr wide = new Instr.Cast(s.op == Instr.BinOp.LSHR ? Instr.CastKind.ZEXT : Instr.CastKind.SEXT,
                        highLoad, MType.I16, s.pos());
                l.block().insertBefore(l, wide);
                Value result = wide;
                if (k > 8) {
                    Instr rest = new Instr.Bin(s.op, wide, new Value.Const(MType.I16, k - 8), s.pos());
                    s.block().insertBefore(s, rest);
                    result = rest;
                }
                replaced.put(s, result);
            }
        }
        DeadCode.replaceUses(f, replaced);
        return !replaced.isEmpty();
    }

    /** A shift right of the load by 8 to 15. */
    private static boolean isShift(Instr u, Instr.Load l) {
        return u instanceof Instr.Bin s && s.type() == MType.I16 && (s.op == Instr.BinOp.LSHR || s.op == Instr.BinOp.ASHR)
                && s.op(0) == l && s.op(1) != l && s.op(1) instanceof Value.Const k && k.bits() >= 8 && k.bits() <= 15;
    }

    /** The load's low byte. */
    private static boolean isLowByte(Instr u, Instr.Load l) {
        return u instanceof Instr.Cast c && c.kind == Instr.CastKind.TRUNC && c.type() == MType.I8 && c.op(0) == l;
    }
}
