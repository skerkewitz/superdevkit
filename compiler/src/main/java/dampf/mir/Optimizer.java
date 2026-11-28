package dampf.mir;

import dampf.util.PassLimit;
import java.util.function.BooleanSupplier;

/**
 * The MIR passes, in order. Each runs on every function until nothing changes (at most 20
 * rounds); then parameters get the ranges of their arguments ({@link ParamRanges}), small
 * functions are inlined ({@link Inline}), pointer and scaled index parameters become offsets and
 * products ({@link PointerParams}, {@link IndexParams}), and the passes and the ranges run again;
 * last come {@link SinkCompares} and {@link Schedule}. The verifier checks the module after the passes (the
 * tests also check after every single pass).
 *
 * <p>Every pass call goes through a {@link PassLimit}: with a limit, the passes stop after that
 * many applications that changed something (to find the one that broke a program).
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
        run(m, verifyEach, inline, PassLimit.configured(""));
    }

    /** @param limit counts the applications and stops the passes at its limit */
    public static void run(MModule m, boolean verifyEach, boolean inline, PassLimit limit) {
        Run r = new Run(m, verifyEach, limit);
        r.functions();
        if (r.module("ParamRanges", () -> ParamRanges.run(m))) {
            r.functions();
        }
        // inlining copies a callee's calls and stores into its callers, which already call it:
        // what may write memory stays the same
        boolean again = inline && limit.allows() && Inline.run(m, r::function, limit);
        again |= r.module("PointerParams", () -> PointerParams.run(m));
        again |= r.module("IndexParams", () -> IndexParams.run(m));
        if (again) {
            r.functions();
            // pointers that became offsets in callers may now reach more callees
            if (r.module("PointerParams", () -> PointerParams.run(m))) {
                r.functions();
            }
            if (r.module("ParamRanges", () -> ParamRanges.run(m))) {
                r.functions();
            }
        }
        // 32-bit values only combined bit by bit, as two 16-bit values: after the passes that narrow
        // them; then the passes again, on the halves
        for (MFunction f : m.functions()) {
            if (r.pass("SplitWords", f, () -> SplitWords.run(f))) {
                r.function(f);
            }
        }
        // a 32-bit comparison with a sum of 16-bit values: after the loop passes, which read the
        // tests of their loops; then the passes again, since the ranges may decide the new test
        for (MFunction f : m.functions()) {
            if (r.pass("CompareSum", f, () -> CompareSum.run(f))) {
                r.function(f);
            }
        }
        // last: comparisons next to the branches that use them, after value numbering merged equal
        // ones; then the order of the instructions for the code generator
        for (MFunction f : m.functions()) {
            r.pass("SinkCompares", f, () -> SinkCompares.run(f));
            r.pass("Schedule", f, () -> Schedule.run(f));
        }
        Verifier.verify(m);
    }

    /** One run of the passes over a module. */
    private static final class Run {
        final MModule m;
        final boolean verifyEach;
        final PassLimit limit;
        // which functions may write memory: passes never add a store, a copy or a call, so this
        // stays true while they run
        final java.util.Map<MFunction, Boolean> writes;

        Run(MModule m, boolean verifyEach, PassLimit limit) {
            this.m = m;
            this.verifyEach = verifyEach;
            this.limit = limit;
            this.writes = Gvn.writesMemory(m);
        }

        void functions() {
            for (MFunction f : m.functions()) {
                function(f);
            }
        }

        /** The passes on one function, until nothing changes. */
        void function(MFunction f) {
            if (f.isAsm()) {
                return;   // a stand-in for hand-written assembly: nothing to look into
            }
            boolean changed = true;
            for (int round = 0; changed && round < 20; round++) {
                changed = pass("Simplify", f, () -> Simplify.run(f));
                changed |= pass("RangeChecks", f, () -> RangeChecks.run(f));
                changed |= pass("BitSelect", f, () -> BitSelect.run(f));
                changed |= pass("AbsDiff", f, () -> AbsDiff.run(f));
                changed |= pass("ByteLoads", f, () -> ByteLoads.run(f));
                changed |= pass("Narrow", f, () -> Narrow.run(f));
                changed |= pass("RangeNarrow", f, () -> RangeNarrow.run(f));
                changed |= pass("Licm", f, () -> Licm.run(m, f, writes));
                changed |= pass("Induction", f, () -> Induction.run(f));
                changed |= pass("PointerOffsets", f, () -> PointerOffsets.run(m, f));
                changed |= pass("StrengthReduce", f, () -> StrengthReduce.run(f));
                changed |= pass("Gvn", f, () -> Gvn.run(m, f, writes));
                changed |= pass("DeadCode", f, () -> DeadCode.run(f));
                changed |= pass("DeadLoops", f, () -> DeadLoops.run(f));
                changed |= pass("Collapse", f, () -> Collapse.run(f));
            }
        }

        boolean pass(String name, MFunction f, BooleanSupplier run) {
            if (f.isAsm()) {
                return false;
            }
            boolean changed = limit.apply(name, f.name(), run);
            if (verifyEach) {
                Verifier.verify(m, f);
            }
            return changed;
        }

        boolean module(String name, BooleanSupplier run) {
            boolean changed = limit.apply(name, "the module", run);
            if (changed && verifyEach) {
                Verifier.verify(m);
            }
            return changed;
        }
    }
}
