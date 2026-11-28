package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A load from a constant object (a {@code const} global or static local with its initializer,
 * which lies in ROM) at a constant place within it is the value of its initializer there:
 * {@code tab[3]}, {@code things[1].x}, {@code grid[r][c]} once inlining has made r and c
 * constants. gcc does the same; the object cannot change, since C does not allow a store to it
 * and the ROM would not take one. Not for a volatile load, a place outside the object (left to
 * the code as it is), bytes that hold an address, or a pointer-typed load.
 */
public final class ConstLoads {
    private ConstLoads() {}

    /** Returns whether a load was replaced. */
    public static boolean run(MModule m, MFunction f) {
        Map<String, MModule.Global> consts = new HashMap<>();
        for (MModule.Global g : m.globals()) {
            if (g.readOnly()) {
                consts.put(g.symbol(), g);
            }
        }
        if (consts.isEmpty()) {
            return false;
        }
        Map<Instr, Value> replaced = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Load l && !l.isVolatile && integer(l.type())) {
                    Value v = folded(l, consts);
                    if (v != null) {
                        replaced.put(l, v);
                    }
                }
            }
        }
        DeadCode.replaceUses(f, replaced);
        return !replaced.isEmpty();
    }

    private static boolean integer(MType t) {
        return t == MType.I8 || t == MType.I16 || t == MType.I32;
    }

    /** The constant the load reads, or null. */
    private static Value folded(Instr.Load l, Map<String, MModule.Global> consts) {
        long offset = 0;
        Value a = l.op(0);
        while (a instanceof Instr.PtrAdd p) {
            if (!(p.op(1) instanceof Value.Const k)) {
                return null;
            }
            offset += k.type().signed(k.bits());
            a = p.op(0);
        }
        if (!(a instanceof Value.GlobalAddr g) || !consts.containsKey(g.symbol())) {
            return null;
        }
        MModule.Global global = consts.get(g.symbol());
        int size = l.type().size;
        if (offset < 0 || offset + size > global.size()) {
            return null;
        }
        Long bits = bytes(global.init(), (int) offset, size);
        return bits == null ? null : new Value.Const(l.type(), bits);
    }

    /**
     * {@code size} bytes at {@code offset} of an object with these initial contents, little
     * endian, as the interpreter lays them out (a later datum over an earlier one; bytes no datum
     * covers are 0); null where an address covers one of them.
     */
    static Long bytes(List<MModule.Datum> init, int offset, int size) {
        long[] b = new long[size];
        boolean[] address = new boolean[size];
        for (MModule.Datum d : init) {
            for (int k = 0; k < d.size(); k++) {
                int at = d.offset() + k - offset;
                if (at >= 0 && at < size) {
                    address[at] = d.symbol() != null;
                    b[at] = (d.value() >> (8 * k)) & 0xFF;
                }
            }
        }
        long v = 0;
        for (int k = size - 1; k >= 0; k--) {
            if (address[k]) {
                return null;
            }
            v = (v << 8) | b[k];
        }
        return v;
    }
}
