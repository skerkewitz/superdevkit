package dampf.backend;

import dampf.mir.Instr;
import dampf.mir.MFunction;
import dampf.mir.MType;
import dampf.mir.Value;

/** Instruction selection for calls. */
final class CallSelection {
    private final Selection sel;

    CallSelection(Selection sel) {
        this.sel = sel;
    }

    void call(Instr.Call c) {
        MFunction callee = sel.gen.module.function(c.callee);
        if (callee == null) {
            throw new IllegalStateException("call of " + c.callee + ", which has no MIR");
        }
        for (int k = 0; k < c.operands().size(); k++) {
            Value.Param p = callee.params().get(k);
            for (int w = 0; w < Selection.words(p.type()); w++) {
                if (c.op(k) instanceof Instr v && sel.arith.stored(v) && sel.operand(v, w).equals(sel.placeOf(p, w))) {
                    continue;   // computed in place (argumentsInPlace)
                }
                sel.ins("lda", sel.operand(c.op(k), w));
                sel.ins("sta", sel.placeOf(p, w));
            }
        }
        sel.ins("jsl", c.callee);
        if (callee.isAsm() && c.type() == MType.I8) {
            // an assembly function's byte result is the low byte of A (DAMPF_ASM); dampf's
            // results are clean
            sel.ins("and", Selection.imm(0xFF));
        }
        if (c.type() != MType.VOID) {
            sel.result(c, 0);
            if (Selection.words(c.type()) == 2) {
                sel.ins("stx", sel.dest(c, 1));
            }
        }
    }
}
