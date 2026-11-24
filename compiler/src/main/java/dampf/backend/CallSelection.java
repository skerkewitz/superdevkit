package dampf.backend;

import dampf.mir.Instr;
import dampf.mir.MFunction;
import dampf.mir.MType;
import dampf.mir.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        result(c);
    }

    /**
     * A call through a function table: the arguments go into dampf_regs (word by word, in order),
     * the function's address into dampf_fptr, then {@code JSL dampf_icall}, whose {@code JML}
     * goes to the function, or to its thunk, which copies the arguments into its parameters
     * ({@link MirCodeGen#thunks}). The callee's frame lies below the caller's like any callee's.
     */
    void callPtr(Instr.CallPtr c) {
        int at = 0;
        for (int k = 0; k < c.args(); k++) {
            Value a = c.arg(k);
            for (int w = 0; w < Selection.words(a.type()); w++) {
                sel.ins("lda", sel.operand(a, w));
                sel.ins("sta", ARGS.plus(at));
                at += 2;
            }
        }
        if (!(c.op(0) instanceof Instr p && FPTR.equals(sel.fixedDest.get(p)))) {
            sel.ins("lda", sel.operand(c.op(0), 0));
            sel.ins("sta", FPTR);
            sel.ins("lda", sel.operand(c.op(0), 1));
            sel.ins("sta", FPTR.plus(2));
        }
        sel.ins("jsl", sel.gen.dispatch(c.targets));
        result(c);
    }

    /**
     * The function of a table call, loaded from the table in the same block and used nowhere else,
     * with no call between the load and the table call (a call may itself call through a table),
     * is loaded straight into dampf_fptr.
     */
    void planFunctionPointers(dampf.mir.MFunction f) {
        sel.fixedDest.clear();
        Map<Value, Integer> uses = new HashMap<>();
        for (dampf.mir.Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    uses.merge(v, 1, Integer::sum);
                }
            }
        }
        for (dampf.mir.Block b : f.blocks()) {
            List<Instr> is = b.instrs();
            for (int n = 0; n < is.size(); n++) {
                if (!(is.get(n) instanceof Instr.CallPtr c) || !(c.op(0) instanceof Instr.Load l) || l.block() != b
                        || uses.getOrDefault(l, 0) != 1 || l.isVolatile) {
                    continue;
                }
                boolean free = true;
                for (int j = is.indexOf(l) + 1; j < n; j++) {
                    free &= !(is.get(j) instanceof Instr.Call || is.get(j) instanceof Instr.CallPtr);
                }
                if (free) {
                    sel.fixedDest.put(l, FPTR);
                }
            }
        }
    }

    /** Where a table call puts its arguments: the runtime's registers. */
    static final dampf.lir.LOperand.Mem ARGS = Selection.reg("dampf_regs");
    /** Where it puts the function's address. */
    static final dampf.lir.LOperand.Mem FPTR = Selection.reg("dampf_fptr");

    private void result(Instr c) {
        if (c.type() != MType.VOID) {
            sel.result(c, 0);
            if (Selection.words(c.type()) == 2) {
                sel.ins("stx", sel.dest(c, 1));
            }
        }
    }
}
