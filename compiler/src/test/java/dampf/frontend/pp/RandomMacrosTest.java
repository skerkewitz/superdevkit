package dampf.frontend.pp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.frontend.SystemHeaders;
import dampf.util.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Random macro programs ({@link RandomMacros}) through dampf's preprocessor and clang's: the same
 * tokens, or an error in both. A token stands at clang's line or later: after an invocation or a
 * comment over several lines, clang keeps the rest of the line where it began, gcc and dampf put
 * each token at its own line ({@link OracleTest} compares the lines of real code exactly). Seeds 1
 * to 300 in every build, side by side ({@link Concurrently}); {@code -PppRandom=N} runs N,
 * {@code -PppSeed=S} starts at S.
 */
class RandomMacrosTest {
    @TempDir
    Path tmp;

    @TestFactory
    Stream<DynamicTest> randomMacrosAgreeWithClang() {
        long first = Long.getLong("pp.seed", 1);
        long count = Long.getLong("pp.random", 300);
        return Concurrently.tests(LongStream.range(first, first + count).boxed().toList(), seed -> "seed " + seed, this::check);
    }

    private void check(long seed) throws Exception {
        String program = new RandomMacros(seed).generate();
        Path f = tmp.resolve("macros" + seed + ".c");
        Files.writeString(f, program);
        Processes.Result clang = Processes.run(null, Duration.ofMinutes(1),
                List.of("clang", "-E", "-std=c99", "-nostdinc", "-undef", "-D__DAMPF__=1", "-w", f.toString()));
        String ours;
        try {
            ours = new Preprocess(SystemHeaders.directory(), List.of(), List.of()).run(f);
        } catch (PpException e) {
            assertTrue(clang.exit() != 0, "seed " + seed + ": dampf failed where clang did not: " + e.getMessage()
                    + " at line " + e.line() + "\n" + program);
            return;
        }
        assertEquals(0, clang.exit(), "seed " + seed + ": clang failed where dampf did not: " + clang.err() + "\n" + program);
        List<String> a = OracleTest.placed(clang.out(), f.toString());
        List<String> b = OracleTest.placed(ours, f.toString());
        String context = "\n" + program + "\n--- clang:\n" + clang.out() + "\n--- dampf:\n" + ours;
        for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
            // the same token; at the same line, or later where clang keeps a token on the line of the
            // invocation or comment before it, which gcc and dampf do not
            String[] x = a.get(k).split(" ", 2);
            String[] y = b.get(k).split(" ", 2);
            assertEquals(x[1], y[1], "seed " + seed + ", token " + k + " (clang, then dampf)" + context);
            int lx = Integer.parseInt(x[0].substring(x[0].lastIndexOf(':') + 1));
            int ly = Integer.parseInt(y[0].substring(y[0].lastIndexOf(':') + 1));
            assertTrue(ly >= lx, "seed " + seed + ", token " + k + " " + x[1] + ": line " + ly + ", clang " + lx + context);
        }
        assertEquals(a.size(), b.size(), "seed " + seed + ": number of tokens" + context);
    }
}
