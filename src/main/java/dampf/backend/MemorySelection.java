package dampf.backend;

import dampf.mir.Instr;
import dampf.mir.MType;
import dampf.mir.Value;
import java.util.Set;

/** Instruction selection for loads, stores and copies: addresses, indexes and pointers. */
final class MemorySelection {
    private final Selection sel;

    MemorySelection(Selection sel) {
        this.sel = sel;
    }

    /** An address as a base plus a constant, non-negative offset (folded into Y or the operand). */
    FrameLayout.Address address(Value v) {
        return sel.layout.address(v);
    }

    /**
     * The operand of {@code mnemonic} for a memory access at byte k of the object, or null if it
     * goes through a pointer. An indexed one loads X with the index first, or Y: where the index
     * is in Y already, or X holds another index and Y is free for it (two arrays indexed by two
     * values, such as two bubbles), as long as the instruction has an absolute,Y form.
     */
    String fixedAt(FrameLayout.Address a, int k, String mnemonic) {
        String f = sel.fixed(a.base(), a.offset() + k);
        if (f == null || a.index() == null) {
            return f;
        }
        if (sel.xResident.containsKey(Selection.throughCopies(a.index()))) {
            return f + ",x";   // X holds the loop's index already
        }
        String index = sel.operand(a.index(), 0);
        boolean yForm = ABS_Y.contains(mnemonic) && !f.startsWith("z:") && !f.startsWith("f:");
        boolean preferY = sel.yIndexes.contains(Selection.throughCopies(a.index()));
        if (yForm && !index.equals(sel.xHolds)
                && (index.equals(sel.yHolds) || preferY || sel.xTaken != null || sel.xHolds != null && !sel.xHolds.startsWith("#"))) {
            sel.ins("ldy", index);
            return f + ",y";
        }
        sel.ins("ldx", index);
        return f + ",x";
    }

    /** The instructions dampf uses with an absolute,Y operand (the 65816 has it for these). */
    static final Set<String> ABS_Y = Set.of("lda", "sta", "adc", "sbc", "and", "ora", "eor", "cmp");

    /** Whether the access goes to a fixed or indexed address (no pointer); emits nothing. */
    boolean isFixed(FrameLayout.Address a) {
        return sel.fixed(a.base(), a.offset()) != null;
    }

    /**
     * Y for a [dp],y access at byte k of the object: the constant offset, or the index (a pointer
     * plus a 16-bit value that is never negative), and INY INY for the next word.
     */
    void yAt(FrameLayout.Address a, int k) {
        if (a.index() == null) {
            sel.ins("ldy", Selection.imm(a.offset() + k));
        } else if (k == 0) {
            // (never a loop counter kept in X: usesFitX counts only globals and slots it indexes)
            sel.ins("ldy", sel.operand(a.index(), 0));
        } else {
            sel.ins("iny", null);
            sel.ins("iny", null);
        }
    }

    void load(Instr.Load l) {
        MType t = l.type();
        FrameLayout.Address a = address(l.op(0));
        boolean direct = isFixed(a);
        String via = direct ? null : via(a.base(), Selection.P);
        if (t.size == 1) {
            if (!direct) {
                yAt(a, 0);
            }
            if (l.isVolatile) {
                // exactly one byte: reading an I/O register can have an effect
                sel.ins("sep", "#$20");
                sel.ins("lda", direct ? fixedAt(a, 0, "lda") : via);
                sel.ins("rep", "#$20");
            } else {
                // a 16-bit read and a mask is cheaper than switching to 8 bits; the byte after the
                // object is always in the same bank (the last byte of every bank stays free)
                sel.ins("lda", direct ? fixedAt(a, 0, "lda") : via);
            }
            sel.maskResult(l);   // (the byte after it, or after an 8-bit read the old B, is in the high byte)
            sel.result(l, 0);
            return;
        }
        for (int w = 0; w < Selection.words(t); w++) {
            if (!direct) {
                yAt(a, 2 * w);
            }
            sel.ins("lda", direct ? fixedAt(a, 2 * w, "lda") : via);
            sel.result(l, w);
        }
    }

    void store(Instr.Store s) {
        FrameLayout.Address a = address(s.op(0));
        Value v = s.op(1);
        MType t = v.type();
        boolean direct = isFixed(a);
        String via = direct ? null : via(a.base(), Selection.P);
        if (direct && v instanceof Value.Const k && k.bits() == 0) {
            // STZ: no load of the zero (direct page, absolute and X-indexed; not long or [dp],y)
            String at0 = fixedAt(a, 0, "stz");
            if (!at0.startsWith("f:")) {
                if (t.size == 1) {
                    sel.ins("sep", "#$20");
                    sel.ins("stz", at0);
                    sel.ins("rep", "#$20");
                    return;
                }
                sel.ins("stz", at0);
                for (int w = 1; w < Selection.words(t); w++) {
                    sel.ins("stz", fixedAt(a, 2 * w, "stz"));
                }
                return;
            }
        }
        if (t.size == 1) {
            sel.ins("lda", sel.operand(v, 0));
            if (!direct) {
                yAt(a, 0);
            }
            sel.ins("sep", "#$20");
            sel.ins("sta", direct ? fixedAt(a, 0, "sta") : via);
            sel.ins("rep", "#$20");
            return;
        }
        for (int w = 0; w < Selection.words(t); w++) {
            sel.ins("lda", sel.operand(v, w));
            if (!direct) {
                yAt(a, 2 * w);
            }
            sel.ins("sta", direct ? fixedAt(a, 2 * w, "sta") : via);
        }
    }

    /**
     * The [dp],y operand for a pointer: its own place if that is in the direct page, else
     * dampf_ptr (or dampf_ptr+4) after copying it there, unless it is there already.
     */
    String via(Value pointer, String dp) {
        while (pointer instanceof Instr i && FrameLayout.transparent(i)) {
            pointer = i.op(0);   // the same bytes
        }
        if (pointer instanceof Value.Param || pointer instanceof Instr) {
            int p = sel.place.get(pointer);
            if (Selection.FRAME_START + p + 3 < 0x100) {
                return "[" + Selection.FRAME + "+" + p + "],y";
            }
        }
        boolean first = dp.equals(Selection.P);
        if ((first ? sel.ptrP : sel.ptrQ) != pointer || pointer instanceof Value.Undef) {
            sel.pointer(pointer, dp);
            if (first) {
                sel.ptrP = pointer;
            } else {
                sel.ptrQ = pointer;
            }
        }
        return first ? Selection.VIA_P : Selection.VIA_Q;
    }

    void copy(Instr.Copy c) {
        sel.pointer(c.op(1), Selection.P);
        sel.pointer(c.op(0), Selection.Q);
        sel.ptrP = c.op(1);
        sel.ptrQ = c.op(0);
        int size = c.size;
        if (size % 2 == 1) {
            sel.ins("ldy", Selection.imm(size - 1));
            sel.ins("sep", "#$20");
            sel.ins("lda", Selection.VIA_P);
            sel.ins("sta", Selection.VIA_Q);
            sel.ins("rep", "#$20");
        }
        if (size >= 2) {
            String loop = sel.label();
            sel.ins("ldy", Selection.imm((size & ~1) - 2));
            sel.place(loop);
            sel.out.loopBound(loop, size / 2);   // Y counts down two bytes a round
            sel.ins("lda", Selection.VIA_P);
            sel.ins("sta", Selection.VIA_Q);
            sel.ins("dey", null);
            sel.ins("dey", null);
            sel.ins("bpl", loop);
        }
    }
}
