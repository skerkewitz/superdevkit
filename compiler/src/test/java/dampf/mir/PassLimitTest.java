package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.util.PassLimit;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pass limit (docs/current-compiler-flaws.md, T3): with the limit n the optimizer stops after n
 * applications that changed something, with 0 it changes nothing, and with the number of
 * applications the passes make anyway it gives what it gives without a limit. The execution tests
 * run with any limit too ({@code -PoptLimit=n}), which checks that the program is right at every
 * point where the passes can stop.
 */
class PassLimitTest {
    /** A program that inlines, scales index parameters and runs most loop passes. */
    private static MModule program() throws Exception {
        Path src = Path.of(PassLimitTest.class.getResource("/exec/pair_loops.c").toURI());
        return Lower.lower(Compiler.frontEnd(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                Path.of("x.sfc"))));
    }

    private static String optimized(PassLimit limit) throws Exception {
        MModule m = program();
        Optimizer.run(m, true, true, limit);
        return Printer.print(m);
    }

    @Test
    void theLimitStopsThePassesWhereItSays() throws Exception {
        PassLimit all = PassLimit.none();
        String full = optimized(all);
        long total = all.applied();
        assertTrue(total > 20, "pair_loops.c should keep the passes busy, not " + total + " applications");

        assertEquals(Printer.print(program()), optimized(new PassLimit(0, "")), "with the limit 0 no pass changes anything");
        assertEquals(full, optimized(new PassLimit(total, "")), "the limit " + total + " lets every application run");
        assertEquals(full, optimized(new PassLimit(total + 10, "")));

        for (long n : List.of(1L, total / 2, total - 1)) {
            PassLimit limit = new PassLimit(n, "");
            String text = optimized(limit);
            assertEquals(n, limit.applied(), "the limit " + n);
            assertNotNull(limit.last());
            assertNotEquals(full, text, "the limit " + n + " of " + total + " leaves something undone");
        }
    }
}
