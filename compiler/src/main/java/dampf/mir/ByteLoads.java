package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The integer part of an 8.8 fixed-point value read as a byte (catalogue E): a 16-bit load whose
 * only use shifts it right by 8 or more becomes a byte load of its high byte (little endian: the
 * address plus one), widened again. On the 65816 that saves the XBA and the mask the shift costs
 * ({@code PX(b->x)} is {@code b->x >> 8} all over the game). Volatile loads stay as they are:
 * a hardware register may care how it is read.
 */
public final class ByteLoads {
    private ByteLoads() {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Map<Instr, Integer> uses = DeadCode.useCounts(f);
        Map<Instr, Value> replaced = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : List.copyOf(b.instrs())) {
                if (!(i instanceof Instr.Bin s) || s.type() != MType.I16
                        || s.op != Instr.BinOp.LSHR && s.op != Instr.BinOp.ASHR
                        || !(s.op(1) instanceof Value.Const k) || k.bits() < 8 || k.bits() > 15
                        || !(s.op(0) instanceof Instr.Load l) || l.isVolatile || l.type() != MType.I16
                        || uses.getOrDefault(l, 0) != 1 || l.block() == null) {
                    continue;
                }
                Instr high = new Instr.PtrAdd(l.op(0), new Value.Const(MType.I16, 1), l.pos());
                Instr byteLoad = new Instr.Load(MType.I8, high, false, l.pos());
                Instr wide = new Instr.Cast(s.op == Instr.BinOp.LSHR ? Instr.CastKind.ZEXT : Instr.CastKind.SEXT,
                        byteLoad, MType.I16, s.pos());
                l.block().insertBefore(l, high);
                l.block().insertBefore(l, byteLoad);
                l.block().insertBefore(l, wide);
                Value result = wide;
                if (k.bits() > 8) {
                    Instr rest = new Instr.Bin(s.op, wide, new Value.Const(MType.I16, k.bits() - 8), s.pos());
                    s.block().insertBefore(s, rest);
                    result = rest;
                }
                replaced.put(s, result);
            }
        }
        DeadCode.replaceUses(f, replaced);
        return !replaced.isEmpty();
    }
}
