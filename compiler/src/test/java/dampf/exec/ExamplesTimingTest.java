package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.ExamplesTest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The static timing against the examples: each runs in dampf's emulator for its first frames
 * without input (the titles' demos fly the busy parts, Mandelbrot computes, Schach plays), every
 * call of every function is measured (from its first instruction to the instruction after the JSL
 * that called it), and each must lie within the function's static bounds: the best case no more,
 * the worst case no less than any call took. It took over from the same test on Blubber Blasen's
 * benchmark when the game left the repository.
 */
class ExamplesTimingTest {
    private static final long FRAMES = 3000;
    private static final long FRAME = 262L * 1364;

    @TestFactory
    Stream<DynamicTest> everyCallLiesWithinItsBounds() throws IOException {
        List<Path> examples = ExamplesTest.examples();
        return examples.stream().map(ex -> DynamicTest.dynamicTest(ex.getFileName().toString(), () -> {
            String name = ex.getFileName().toString();
            Compiler.Result r = ExamplesTest.compile(ex, ex.resolve("build/timing/" + name + "_timing.sfc"));
            EmulatorRunner.CallCheck calls = new EmulatorRunner(FRAMES * FRAME).checkCalls(r.rom(), r.timing());
            assertTrue(calls.calls() > 1000, name + ": only " + calls.calls() + " calls measured");
            assertTrue(calls.wrong().isEmpty(), name + ": calls outside their static bounds: " + calls.wrong());
        }));
    }
}
