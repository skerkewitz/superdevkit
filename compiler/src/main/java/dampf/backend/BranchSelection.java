package dampf.backend;

import dampf.lir.BranchRelax;
import dampf.mir.Block;
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
     * Sets the carry for an ordered comparison, C = x >= y (with x and y swapped for > and <=);
     * returns the branch that is taken when the comparison is true ("bcc" or "bcs").
     */
    String orderedFlags(Instr.Cmp c) {
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
        String yTop = sel.operand(y, top);
        if (bias != 0) {
            if (y instanceof Value.Const k) {
                yTop = Selection.imm((k.bits() >> (16 * top)) ^ bias);
            } else {
                sel.ins("lda", sel.operand(y, top));
                sel.ins("eor", Selection.imm(bias));
                sel.ins("sta", "z:R0");
                yTop = "z:R0";
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
