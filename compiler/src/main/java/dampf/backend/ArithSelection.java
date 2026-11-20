package dampf.backend;

import dampf.mir.Instr;
import dampf.mir.MType;
import dampf.mir.Ranges;
import dampf.mir.Value;
import java.util.List;

/** Instruction selection for arithmetic, shifts and casts. */
final class ArithSelection {
    private final Selection sel;

    ArithSelection(Selection sel) {
        this.sel = sel;
    }

    /**
     * Whether an 8-bit add, subtract, multiply or left shift, done in 16 bits on zero-extended
     * operands, provably gives a result below 256, so it needs no mask (from the value ranges,
     * in the unsigned view the 16-bit code computes in).
     */
    boolean staysByte(Instr.Bin b) {
        if (b.type() != MType.I8 || sel.ranges == null) {
            return false;
        }
        Ranges.Range x = sel.ranges.atUnsigned(b.op(0), b.block());
        Ranges.Range y = sel.ranges.atUnsigned(b.op(1), b.block());
        if (x == null || y == null) {
            return false;
        }
        return switch (b.op) {
            case ADD -> x.hi() + y.hi() <= 0xFF;
            case SUB -> x.lo() - y.hi() >= 0;
            case MUL -> x.hi() * y.hi() <= 0xFF;
            case SHL -> y.lo() == y.hi() && y.lo() < 8 && x.hi() << y.lo() <= 0xFF;
            default -> false;
        };
    }

    /**
     * INC or DEC on the place itself, for x ± 1 whose result goes back to x's place (a counter's
     * step): 7 cycles instead of CLC, LDA, ADC, STA (13). The 16 bits are those ADC #1 gives (an
     * i8 minus 1 differs in the high byte only, which an i8 that needs no mask may have any). An
     * i8 result that must be clean gets an 8-bit INC/DEC ("inc8", "dec8") if the old value's high
     * byte is 0. Not when the result is handed on in A. Null if it does not apply.
     */
    String incDec(Instr.Bin b) {
        if (b.type() != MType.I16 && b.type() != MType.I8 || sel.m8 || sel.forward.contains(b) || sel.place.get(b) == null
                || !(b.op(1) instanceof Value.Const k) || b.op(0) instanceof Value.Const
                || b.op(0) instanceof Instr x && !stored(x) || !sel.operand(b.op(0), 0).equals(sel.dest(b, 0))) {
            return null;
        }
        if (b.op != Instr.BinOp.ADD) {
            return null;   // canonical (Simplify): x - c is x + (-c)
        }
        long c = b.type().signed(k.bits());
        String m = c == 1 ? "inc" : c == -1 ? "dec" : null;
        // A clean result: an 8-bit INC/DEC keeps the high byte, which must be 0 already. That is
        // one that is masked, and also a DEC whose place must be clean without a mask: x + -1 on
        // a byte is ADC #$00FF in 16 bits, which staysByte finds clean for x = 0 (255), where a
        // 16-bit DEC gives $FFFF.
        if (m != null && b.type() == MType.I8 && (sel.masked.contains(b) || c == -1 && sel.clean.contains(b))) {
            Value x = b.op(0);
            return x instanceof Value.Param || sel.clean.contains(x) ? m + "8" : null;
        }
        return m;
    }

    /** Whether an instruction's result is written to its place (not only handed on in A, folded or in X). */
    boolean stored(Instr x) {
        return !sel.forward.contains(x) && !sel.folded.contains(x) && !sel.fused.contains(x) && !sel.xSteps.containsKey(x)
                && !sel.xResident.containsKey(x);
    }

    void bin(Instr.Bin b) {
        MType t = b.type();
        int n = Selection.words(t);
        Value x = b.op(0);
        Value y = b.op(1);
        switch (b.op) {
            case ADD, SUB -> {
                String step = incDec(b);
                if (step != null && step.endsWith("8")) {
                    sel.ins("sep", "#$20");
                    sel.ins(step.substring(0, 3), sel.dest(b, 0));
                    sel.ins("rep", "#$20");
                    return;
                }
                if (step != null) {
                    sel.ins(step, sel.dest(b, 0));
                    return;
                }
                if (n == 1 && b.op == Instr.BinOp.ADD && sel.aHolds.contains(sel.operand(y, 0))) {
                    Value s = x;   // A has the second operand already: add the first to it
                    x = y;
                    y = s;
                }
                sel.ins(b.op == Instr.BinOp.ADD ? "clc" : "sec", null);
                for (int w = 0; w < n; w++) {
                    sel.ins("lda", sel.operand(x, w));
                    sel.ins(b.op == Instr.BinOp.ADD ? "adc" : "sbc", sel.operand(y, w));
                    sel.maskResult(b);
                    sel.result(b, w);
                }
            }
            case AND, OR, XOR -> {
                if (n == 1 && sel.aHolds.contains(sel.operand(y, 0))) {
                    Value s = x;   // A has the second operand already
                    x = y;
                    y = s;
                }
                String m = b.op == Instr.BinOp.AND ? "and" : b.op == Instr.BinOp.OR ? "ora" : "eor";
                for (int w = 0; w < n; w++) {
                    sel.ins("lda", sel.operand(x, w));
                    sel.ins(m, sel.operand(y, w));
                    if (n == 1) {
                        sel.maskResult(b);   // (operands may have any high byte)
                    }
                    sel.result(b, w);
                }
            }
            case MUL -> {
                if (n == 1 && (x instanceof Value.Const) != (y instanceof Value.Const)) {
                    Value v = x instanceof Value.Const ? y : x;
                    long c = ((Value.Const) (x instanceof Value.Const ? x : y)).bits();
                    multiplyBy(v, c, t);
                    sel.maskResult(b);
                    sel.result(b, 0);
                } else if (n == 1) {
                    sel.ins("lda", sel.operand(y, 0));
                    sel.ins("sta", "z:R0");
                    sel.ins("lda", sel.operand(x, 0));
                    sel.ins("jsl", "dampf_mul16");
                    sel.maskResult(b);
                    sel.result(b, 0);
                } else {
                    to32(x, "z:R0", "z:R2");
                    to32(y, "z:R4", "z:R6");
                    sel.ins("jsl", "dampf_mul32");
                    from32(b, "z:R8", "z:R10");
                }
            }
            case UDIV, UREM -> {
                boolean rem = b.op == Instr.BinOp.UREM;
                if (n == 1) {
                    sel.ins("lda", sel.operand(y, 0));
                    sel.ins("sta", "z:R0");
                    sel.ins("lda", sel.operand(x, 0));
                    sel.ins("jsl", "dampf_udiv16");
                    if (rem) {
                        sel.ins("lda", "z:R2");
                    }
                    sel.result(b, 0);
                } else {
                    to32(x, "z:R0", "z:R2");
                    to32(y, "z:R4", "z:R6");
                    sel.ins("jsl", "dampf_udiv32");
                    from32(b, rem ? "z:R12" : "z:R8", rem ? "z:R14" : "z:R10");
                }
            }
            case SDIV, SREM -> {
                boolean rem = b.op == Instr.BinOp.SREM;
                if (n == 1) {
                    signExtend(x, t, "z:R0", "z:R2");
                    signExtend(y, t, "z:R4", "z:R6");
                } else {
                    to32(x, "z:R0", "z:R2");
                    to32(y, "z:R4", "z:R6");
                }
                sel.ins("jsl", "dampf_sdiv32");
                if (n == 1) {
                    sel.ins("lda", rem ? "z:R12" : "z:R8");
                    sel.maskResult(b);
                    sel.result(b, 0);
                } else {
                    from32(b, rem ? "z:R12" : "z:R8", rem ? "z:R14" : "z:R10");
                }
            }
            case SHL, LSHR, ASHR -> shift(b);
        }
    }

    /**
     * A = x * c (low 16 bits): the constant in non-adjacent form, worked through from the top
     * digit down (Horner): shift A left one place per digit, add or subtract x where the digit is
     * +1 or -1. A run of ones such as 15 costs one subtraction: 16 - 1.
     */
    void multiplyBy(Value x, long c, MType t) {
        List<long[]> digits = dampf.mir.StrengthReduce.naf(t.wrap(c), 16);
        if (digits.isEmpty()) {
            sel.ins("lda", Selection.imm(0));
            return;
        }
        String xs = sel.operand(x, 0);
        int top = (int) digits.getLast()[0];
        sel.ins("lda", xs);
        if (digits.getLast()[1] < 0) {
            // the top digit is -1 (only for a constant of 2^16 - ...): start from -x
            sel.ins("eor", Selection.imm(0xFFFF));
            sel.ins("inc", "a");
        }
        int k = digits.size() - 2;
        for (int pos = top - 1; pos >= 0; pos--) {
            sel.ins("asl", "a");
            if (k >= 0 && digits.get(k)[0] == pos) {
                if (digits.get(k)[1] > 0) {
                    sel.ins("clc", null);
                    sel.ins("adc", xs);
                } else {
                    sel.ins("sec", null);
                    sel.ins("sbc", xs);
                }
                k--;
            }
        }
    }

    void to32(Value v, String lo, String hi) {
        sel.ins("lda", sel.operand(v, 0));
        sel.ins("sta", lo);
        sel.ins("lda", sel.operand(v, 1));
        sel.ins("sta", hi);
    }

    void from32(Instr d, String lo, String hi) {
        sel.ins("lda", lo);
        sel.result(d, 0);
        sel.ins("lda", hi);
        sel.result(d, 1);
    }

    /** A = the value of an i1/i8/i16 as a signed 16-bit number. */
    void loadSigned16(Value v, MType t) {
        sel.ins("lda", sel.operand(v, 0));
        int m = t == MType.I8 ? 0x80 : t == MType.I1 ? 1 : 0;
        if (m != 0) {
            sel.ins("eor", Selection.imm(m));     // (x ^ m) - m: sign-extends the top bit m
            sel.ins("sec", null);
            sel.ins("sbc", Selection.imm(m));
        }
    }

    /** Stores A (a signed 16-bit number) to lo, and its sign (0 or $FFFF) to hi. */
    void storeWithSign(String lo, String hi) {
        String positive = sel.label();
        sel.ins("sta", lo);
        sel.ins("ldx", Selection.imm(0));
        sel.ins("cmp", Selection.imm(0x8000));
        sel.ins("bcc", positive);
        sel.ins("dex", null);
        sel.place(positive);
        sel.ins("stx", hi);
    }

    void signExtend(Value v, MType t, String lo, String hi) {
        loadSigned16(v, t);
        storeWithSign(lo, hi);
    }

    /** Shifts by a constant up to this are written out; longer ones and variable ones loop. */
    static final int UNROLL_SHIFT = 8;

    void shift(Instr.Bin b) {
        MType t = b.type();
        int bits = t.bits();
        Value x = b.op(0);
        Value y = b.op(1);
        if (y instanceof Value.Const k && k.bits() <= UNROLL_SHIFT && k.bits() < bits) {
            unrolledShift(b, (int) k.bits());
            return;
        }
        // X = the shift count, at most the width (shifting further changes nothing: all 0, or all sign)
        if (y instanceof Value.Const k) {
            sel.ins("ldx", Selection.imm(Math.min(k.bits(), bits)));
        } else {
            String have = sel.label();
            String big = sel.label();
            if (Selection.words(y.type()) == 2) {
                sel.loadAlways(sel.operand(y, 1));
                sel.ins("bne", big);
            }
            sel.ins("lda", sel.operand(y, 0));
            sel.ins("cmp", Selection.imm(bits));
            sel.ins("bcc", have);
            sel.place(big);
            sel.ins("lda", Selection.imm(bits));
            sel.place(have);
            sel.ins("tax", null);
        }
        String loop = sel.label();
        String done = sel.label();
        if (Selection.words(t) == 1) {
            if (b.op == Instr.BinOp.ASHR) {
                loadSigned16(x, t);
            } else {
                sel.ins("lda", sel.operand(x, 0));
            }
            sel.ins("cpx", Selection.imm(0));
            sel.ins("beq", done);
            sel.place(loop);
            sel.out.loopBound(loop, bits);   // X counts down from at most the width
            switch (b.op) {
                case SHL -> sel.ins("asl", "a");
                case LSHR -> sel.ins("lsr", "a");
                default -> {
                    sel.ins("cmp", Selection.imm(0x8000));    // C = the sign
                    sel.ins("ror", "a");
                }
            }
            sel.ins("dex", null);
            sel.ins("bne", loop);
            sel.place(done);
            sel.maskResult(b);
            sel.result(b, 0);
        } else {
            String lo = sel.dest(b, 0);
            String hi = sel.dest(b, 1);
            to32(x, lo, hi);
            sel.ins("cpx", Selection.imm(0));
            sel.ins("beq", done);
            sel.place(loop);
            sel.out.loopBound(loop, bits);   // X counts down from at most the width
            switch (b.op) {
                case SHL -> {
                    sel.ins("asl", lo);
                    sel.ins("rol", hi);
                }
                case LSHR -> {
                    sel.ins("lsr", hi);
                    sel.ins("ror", lo);
                }
                default -> {
                    sel.ins("lda", hi);
                    sel.ins("cmp", Selection.imm(0x8000));
                    sel.ins("ror", hi);
                    sel.ins("ror", lo);
                }
            }
            sel.ins("dex", null);
            sel.ins("bne", loop);
            sel.place(done);
        }
    }

    void unrolledShift(Instr.Bin b, int count) {
        MType t = b.type();
        Value x = b.op(0);
        if (Selection.words(t) == 1) {
            if (b.op == Instr.BinOp.ASHR) {
                loadSigned16(x, t);
            } else {
                sel.ins("lda", sel.operand(x, 0));
            }
            if (t == MType.I16 && count >= 8) {
                // the bytes swap places: a shift by 8 in two instructions
                sel.ins("xba", null);
                switch (b.op) {
                    case SHL -> sel.ins("and", Selection.imm(0xFF00));
                    case LSHR -> sel.ins("and", Selection.imm(0x00FF));
                    default -> {
                        sel.ins("and", Selection.imm(0x00FF));
                        sel.ins("eor", Selection.imm(0x0080));   // sign-extend the byte
                        sel.ins("sec", null);
                        sel.ins("sbc", Selection.imm(0x0080));
                    }
                }
                count -= 8;
            }
            for (int k = 0; k < count; k++) {
                switch (b.op) {
                    case SHL -> sel.ins("asl", "a");
                    case LSHR -> sel.ins("lsr", "a");
                    default -> {
                        sel.ins("cmp", Selection.imm(0x8000));    // C = the sign
                        sel.ins("ror", "a");
                    }
                }
            }
            sel.maskResult(b);
            sel.result(b, 0);
            return;
        }
        String lo = sel.dest(b, 0);
        String hi = sel.dest(b, 1);
        to32(x, lo, hi);
        for (int k = 0; k < count; k++) {
            switch (b.op) {
                case SHL -> {
                    sel.ins("asl", lo);
                    sel.ins("rol", hi);
                }
                case LSHR -> {
                    sel.ins("lsr", hi);
                    sel.ins("ror", lo);
                }
                default -> {
                    sel.ins("lda", hi);
                    sel.ins("cmp", Selection.imm(0x8000));
                    sel.ins("ror", hi);
                    sel.ins("ror", lo);
                }
            }
        }
    }

    void cast(Instr.Cast c) {
        MType from = c.op(0).type();
        MType to = c.type();
        if (c.kind == Instr.CastKind.SEXT) {
            loadSigned16(c.op(0), from);
            if (Selection.words(to) == 1) {
                sel.maskResult(c);
                sel.result(c, 0);
            } else {
                storeWithSign(sel.dest(c, 0), sel.dest(c, 1));
            }
            return;
        }
        // zext, trunc, ptrtoint, inttoptr: the low bits, zero-extended
        sel.ins("lda", sel.operand(c.op(0), 0));
        sel.maskResult(c);
        sel.result(c, 0);
        if (Selection.words(to) == 2) {
            if (Selection.words(from) == 2) {
                sel.ins("lda", sel.operand(c.op(0), 1));
                sel.result(c, 1);
            } else {
                sel.ins("stz", sel.dest(c, 1));
            }
        }
    }
}
