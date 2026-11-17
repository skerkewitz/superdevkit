package dampf.mir;

/**
 * The MIR passes, in order. Each runs on every function until nothing changes; the verifier
 * checks the module after the passes (the tests also check after every single pass).
 */
public final class Optimizer {
    private Optimizer() {}

    public static void run(MModule m) {
        run(m, false);
    }

    /** @param verifyEach run the verifier after every pass, not only at the end (for tests) */
    public static void run(MModule m, boolean verifyEach) {
        for (MFunction f : m.functions()) {
            boolean changed = true;
            for (int round = 0; changed && round < 20; round++) {
                changed = Narrow.run(f);
                if (verifyEach) {
                    Verifier.verify(m);
                }
                changed |= StrengthReduce.run(f);
                if (verifyEach) {
                    Verifier.verify(m);
                }
                changed |= DeadCode.run(f);
                if (verifyEach) {
                    Verifier.verify(m);
                }
            }
        }
        Verifier.verify(m);
    }
}
