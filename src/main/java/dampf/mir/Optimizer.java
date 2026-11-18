package dampf.mir;

/**
 * The MIR passes, in order. Each runs on every function until nothing changes; then parameters
 * get the ranges of their arguments ({@link ParamRanges}), small functions are inlined
 * ({@link Inline}) and the passes run again. The verifier checks the module
 * after the passes (the tests also check after every single pass).
 */
public final class Optimizer {
    private Optimizer() {}

    public static void run(MModule m) {
        run(m, false);
    }

    /** @param verifyEach run the verifier after every pass, not only at the end (for tests) */
    public static void run(MModule m, boolean verifyEach) {
        run(m, verifyEach, true);
    }

    /** @param inline whether to inline (tests of single passes switch it off) */
    public static void run(MModule m, boolean verifyEach, boolean inline) {
        // which functions may write memory: passes never add a store, a copy or a call, so this
        // stays true while they run
        java.util.Map<MFunction, Boolean> writes = Gvn.writesMemory(m);
        functions(m, writes, verifyEach);
        if (ParamRanges.run(m)) {
            functions(m, writes, verifyEach);
        }
        // inlining copies a callee's calls and stores into its callers, which already call it:
        // what may write memory stays the same
        boolean again = inline && Inline.run(m);
        again |= PointerParams.run(m);
        if (again) {
            if (verifyEach) {
                Verifier.verify(m);
            }
            functions(m, writes, verifyEach);
            // pointers that became offsets in callers may now reach more callees
            if (PointerParams.run(m)) {
                if (verifyEach) {
                    Verifier.verify(m);
                }
                functions(m, writes, verifyEach);
            }
            if (ParamRanges.run(m)) {
                functions(m, writes, verifyEach);
            }
        }
        // last: the order of the instructions for the code generator
        for (MFunction f : m.functions()) {
            Schedule.run(f);
        }
        Verifier.verify(m);
    }

    private static void functions(MModule m, java.util.Map<MFunction, Boolean> writes, boolean verifyEach) {
        for (MFunction f : m.functions()) {
            boolean changed = true;
            for (int round = 0; changed && round < 20; round++) {
                changed = Simplify.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= RangeChecks.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= BitSelect.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= ByteLoads.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= Narrow.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= RangeNarrow.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= Licm.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= Induction.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= PointerOffsets.run(m, f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= StrengthReduce.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= Gvn.run(m, f, writes);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
                changed |= DeadCode.run(f);
                if (verifyEach) {
                    Verifier.verify(m, f);
                }
            }
        }
    }
}
