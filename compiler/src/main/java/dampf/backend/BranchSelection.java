package dampf.backend;

import dampf.lir.LOperand;

import dampf.lir.BranchRelax;
import dampf.mir.Block;
import dampf.mir.MFunction;
import dampf.mir.Instr;
import dampf.mir.MType;
import dampf.mir.Value;

/** Instruction selection for comparisons, jumps and branches (in their long form, see {@link dampf.lir.BranchRelax}). */
final class BranchSelection {
    private final Selection sel;

    BranchSelection(Selection sel) {
        this.sel = sel;
    }

    /**
     * Signed comparisons of 16-bit values in the biased form, as unsigned ones of x ^ $8000 and
     * y ^ $8000, instead of by subtraction ({@link #bySubtraction}): MirCodeGen writes a function
     * with signed comparisons both ways and keeps the form with the smaller worst case.
     */
    boolean biased;

    /** f has a signed comparison of 16-bit values (which {@link #bySubtraction} may write). */
    static boolean hasSignedCompare(MFunction f) {
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Cmp c && signed(c.pred) && c.op(0).type() == MType.I16) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean signed(Instr.Pred p) {
        return switch (p) {
            case SLT, SLE, SGT, SGE -> true;
            default -> false;
        };
    }

    /**
     * An unconditional jump to a block, in its long form (JMP); {@link BranchRelax} makes it a BRA
     * where that reaches (the same 3 cycles, one byte shorter, so other branches reach more often).
     */
    void jumpTo(Block target) {
        sel.ins("jmp", target.name(), true);
    }

    void cmp(Instr.Cmp c) {
        switch (c.pred) {
            case EQ, NE -> {
                String differ = sel.label();
                String done = sel.label();
                eqFlags(c, differ);
                sel.ins("bne", differ);
                sel.ins("lda", Selection.imm(c.pred == Instr.Pred.EQ ? 1 : 0));
                sel.ins("bra", done);
                sel.place(differ);
                sel.ins("lda", Selection.imm(c.pred == Instr.Pred.EQ ? 0 : 1));
                sel.place(done);
                sel.result(c, 0);
            }
            default -> {
                if (bySubtraction(c) && !(c.op(0) instanceof Value.Const) && !(c.op(1) instanceof Value.Const)) {
                    signedValue(c);
                    sel.result(c, 0);
                    return;
                }
                boolean less = orderedFlags(c).equals("bcc");
                sel.ins("lda", Selection.imm(0));
                sel.ins("rol", "a");          // A = C = x >= y
                if (less) {
                    sel.ins("eor", Selection.imm(1));
                }
                sel.result(c, 0);
            }
        }
    }

    /**
     * A signed comparison of 16-bit values decided by the sign of x - y (with x and y swapped for
     * > and <=): of two variables, N xor V after SBC (or N alone after CMP where the value ranges
     * say the difference cannot overflow), and of a variable and a constant where it cannot. (A
     * constant otherwise: its biased form costs nothing, x ^ $8000 compared unsigned is cheaper.)
     */
    boolean bySubtraction(Instr.Cmp c) {
        return !biased && signed(c.pred) && c.op(0).type() == MType.I16 && (!(c.op(1) instanceof Value.Const) || noOverflow(c));
    }

    /** The comparison's operands as x - y: x < y for slt and sgt, x >= y for sge and sle. */
    private static boolean swapped(Instr.Cmp c) {
        return c.pred == Instr.Pred.SGT || c.pred == Instr.Pred.SLE;
    }

    /** x - y cannot overflow 16 bits by the value ranges, so N alone is the sign of the difference. */
    private boolean noOverflow(Instr.Cmp c) {
        Value x = swapped(c) ? c.op(1) : c.op(0);
        Value y = swapped(c) ? c.op(0) : c.op(1);
        dampf.mir.Ranges.Range rx = sel.ranges == null ? null : sel.ranges.at(x, c.block());
        dampf.mir.Ranges.Range ry = sel.ranges == null ? null : sel.ranges.at(y, c.block());
        return rx != null && ry != null && rx.lo() - ry.hi() >= -0x8000 && rx.hi() - ry.lo() <= 0x7FFF;
    }

    /**
     * Starts x - y: loads x and returns true, for SBC or CMP y to follow; or, where A holds y
     * (just computed) and not x, computes ~y + x + 1 with ADC, which gives the same difference
     * and the same N, V, Z and C as SBC (and costs less than storing y and loading x), and
     * returns false.
     */
    private boolean subtract(Value x, Value y) {
        LOperand xo = sel.operand(x, 0);
        LOperand yo = sel.operand(y, 0);
        if (!sel.m8 && sel.aHolds.contains(yo) && !sel.aHolds.contains(xo)) {
            sel.ins("eor", Selection.imm(0xFFFF));
            sel.ins("sec");
            sel.ins("adc", xo);
            return false;
        }
        sel.ins("lda", xo);
        return true;
    }

    /**
     * The flags of x - y for {@link #bySubtraction}: N is set if x < y. Returns the branch taken
     * when the comparison is true ("bmi" or "bpl").
     */
    private String signedFlags(Instr.Cmp c) {
        Value x = swapped(c) ? c.op(1) : c.op(0);
        Value y = swapped(c) ? c.op(0) : c.op(1);
        boolean loaded = subtract(x, y);
        if (loaded && noOverflow(c)) {
            sel.ins("cmp", sel.operand(y, 0));
        } else if (loaded) {
            sel.ins("sec");
            sel.ins("sbc", sel.operand(y, 0));
        }
        if (!noOverflow(c)) {
            String sign = sel.label();
            sel.ins("bvc", sign);
            sel.ins("eor", Selection.imm(0x8000));   // overflow: N is the opposite of the sign
            sel.place(sign);
        }
        boolean less = c.pred == Instr.Pred.SLT || c.pred == Instr.Pred.SGT;
        return less ? "bmi" : "bpl";
    }

    /**
     * The truth value of a comparison {@link #bySubtraction} of two variables in A: bit 15 of
     * x - y made the answer, then moved to bit 0. (Against a constant the biased form is cheaper.)
     */
    private void signedValue(Instr.Cmp c) {
        Value x = swapped(c) ? c.op(1) : c.op(0);
        Value y = swapped(c) ? c.op(0) : c.op(1);
        boolean less = c.pred == Instr.Pred.SLT || c.pred == Instr.Pred.SGT;
        if (subtract(x, y)) {
            sel.ins("sec");
            sel.ins("sbc", sel.operand(y, 0));
        }
        if (noOverflow(c)) {
            if (!less) {
                sel.ins("eor", Selection.imm(0x8000));
            }
        } else {
            // bit 15 is x < y where V is clear (else its opposite); for >= the other way round
            String done = sel.label();
            sel.ins(less ? "bvc" : "bvs", done);
            sel.ins("eor", Selection.imm(0x8000));
            sel.place(done);
        }
        sel.ins("asl", "a");                 // C = the answer
        sel.ins("lda", Selection.imm(0));
        sel.ins("rol", "a");
    }

    /**
     * Compares the operands of an eq/ne word by word: every word but the last branches to
     * {@code differ} when it differs, the last one leaves Z set if it is equal.
     */
    void eqFlags(Instr.Cmp c, String differ) {
        int n = Selection.words(c.op(0).type());
        for (int w = 0; w < n; w++) {
            if (!(c.op(1) instanceof Value.Const k && (k.bits() >> (16 * w) & 0xFFFF) == 0)) {
                sel.ins("lda", sel.operand(c.op(0), w));
                sel.ins("cmp", sel.operand(c.op(1), w));
            } else {
                sel.flagsOf(sel.operand(c.op(0), w));   // Z for a comparison with 0
            }
            if (w < n - 1) {
                sel.ins("bne", differ);
            }
        }
    }

    /**
     * Sets the flags for an ordered comparison: C = x >= y (with x and y swapped for > and <=), or
     * for a signed one of 16-bit values N = x < y ({@link #bySubtraction}); returns the branch that
     * is taken when the comparison is true ("bcc", "bcs", "bmi" or "bpl").
     */
    String orderedFlags(Instr.Cmp c) {
        if (bySubtraction(c)) {
            return signedFlags(c);
        }
        MType t = c.op(0).type();
        int n = Selection.words(t);
        Value a = c.op(0);
        Value b = c.op(1);
        // x < y, or its negation; signed by flipping the sign bits, so an unsigned compare works
        boolean swap = switch (c.pred) {
            case UGT, ULE, SGT, SLE -> true;
            default -> false;
        };
        boolean wantLess = switch (c.pred) {
            case ULT, UGT, SLT, SGT -> true;
            default -> false;
        };
        boolean signed = switch (c.pred) {
            case SLT, SLE, SGT, SGE -> true;
            default -> false;
        };
        Value x = swap ? b : a;
        Value y = swap ? a : b;
        int top = n - 1;
        int bias = !signed ? 0 : t == MType.I8 ? 0x80 : t == MType.I1 ? 1 : 0x8000;
        LOperand yTop = sel.operand(y, top);
        if (bias != 0) {
            if (y instanceof Value.Const k) {
                yTop = Selection.imm((k.bits() >> (16 * top)) ^ bias);
            } else {
                sel.ins("lda", sel.operand(y, top));
                sel.ins("eor", Selection.imm(bias));
                sel.ins("sta", Selection.reg("R0"));
                yTop = Selection.reg("R0");
            }
        }
        if (n == 2) {
            sel.ins("lda", sel.operand(x, 0));
            sel.ins("cmp", sel.operand(y, 0));
        }
        sel.ins("lda", sel.operand(x, top));
        if (bias != 0) {
            sel.ins("eor", Selection.imm(bias));
        }
        sel.ins(n == 2 ? "sbc" : "cmp", yTop);   // C = x >= y
        return wantLess ? "bcc" : "bcs";
    }

    static String inverse(String branch) {
        return switch (branch) {
            case "bcc" -> "bcs";
            case "bcs" -> "bcc";
            case "beq" -> "bne";
            case "bne" -> "beq";
            case "bmi" -> "bpl";
            case "bpl" -> "bmi";
            default -> throw new IllegalArgumentException(branch);
        };
    }

    /** A branch on the comparison c without its truth value: flags, then the jump. */
    void compareAndBranch(Instr.Cmp c, Block ifTrue, Block ifFalse, Block next) {
        Value x = Selection.throughCopies(c.op(0));
        if (sel.xResident.containsKey(x) || sel.xSteps.containsKey(x)) {
            sel.xOp("cpx", sel.operand(c.op(1), 0));
            jumpOn(switch (c.pred) {
                case EQ -> "beq";
                case NE -> "bne";
                case ULT -> "bcc";
                case SLT -> "bmi";   // against 0: N is the sign of X
                case SGE -> "bpl";
                default -> "bcs";   // UGE (findXCounters admits no other)
            }, null, false, ifTrue, ifFalse, next);
            return;
        }
        if ((c.pred == Instr.Pred.SLT || c.pred == Instr.Pred.SGE) && c.op(0).type() == MType.I16
                && c.op(1) instanceof Value.Const k && k.bits() == 0) {
            sel.flagsOf(sel.operand(c.op(0), 0));   // N is the sign
            jumpOn(c.pred == Instr.Pred.SLT ? "bmi" : "bpl", null, false, ifTrue, ifFalse, next);
            return;
        }
        if (c.pred == Instr.Pred.EQ || c.pred == Instr.Pred.NE) {
            // the label for "the first word already differs" only exists for 32-bit values
            String differ = Selection.words(c.op(0).type()) > 1 ? sel.label() : null;
            eqFlags(c, differ);
            jumpOn(c.pred == Instr.Pred.EQ ? "beq" : "bne", differ, c.pred == Instr.Pred.NE, ifTrue, ifFalse, next);
        } else {
            jumpOn(orderedFlags(c), null, false, ifTrue, ifFalse, next);
        }
    }

    /**
     * Goes to ifTrue if the branch {@code taken} would be taken, else to ifFalse. Blocks may be
     * far away, so the conditional branch only skips a JMP. {@code differ}, if not null, is a
     * label earlier code branches to; it means true if {@code differMeansTrue}, else false.
     */
    void jumpOn(String taken, String differ, boolean differMeansTrue, Block ifTrue, Block ifFalse, Block next) {
        // the long form; BranchRelax shortens it where the targets reach (not with differ)
        boolean relax = differ == null;
        String skip = sel.label();
        if (ifFalse == next) {
            sel.ins(inverse(taken), skip, relax);
            if (differ != null && differMeansTrue) {
                sel.place(differ);
            }
            jumpTo(ifTrue);
            sel.place(skip);
            if (differ != null && !differMeansTrue) {
                sel.place(differ);
            }
            return;
        }
        sel.ins(taken, skip, relax);
        if (differ != null && !differMeansTrue) {
            sel.place(differ);
        }
        jumpTo(ifFalse);
        sel.place(skip);
        if (differ != null && differMeansTrue) {
            sel.place(differ);
        }
        if (ifTrue != next) {
            jumpTo(ifTrue);
        }
    }
}
