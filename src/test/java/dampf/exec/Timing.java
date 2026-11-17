package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import java.io.IOException;

/**
 * Checks the static timing against the emulator: every call of every function and runtime
 * routine lies within its bounds, and test_main as a whole too.
 */
final class Timing {
    private Timing() {}

    static void check(Compiler.Result r, EmulatorRunner emulator, String what) throws IOException {
        long[] b = r.timing().get("_test_main");
        long measured = emulator.cyclesOf(r.rom(), "_test_main");
        assertTrue(b[0] <= measured, what + ": test_main took " + measured + " CPU cycles, the best case says " + b[0]);
        assertTrue(b[1] < 0 || measured <= b[1], what + ": test_main took " + measured + " CPU cycles, the worst case says " + b[1]);
        EmulatorRunner.CallCheck calls = emulator.checkCalls(r.rom(), r.timing());
        assertTrue(calls.wrong().isEmpty(), what + ": calls outside their static bounds: " + calls.wrong());
    }
}
