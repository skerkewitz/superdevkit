package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pair loops' benchmark (exec/pair_loops.c, the roadmap's M9): the four variants of a pair
 * loop (a counter or a list sorted by x, over a struct array or one array per field), each built
 * with -DVARIANT and run in dampf's emulator, code from MIR. The master cycles per frame of a
 * variant are its run's less the run that only makes the frames (VARIANT=0). Written to
 * build/bench-pairs/pairs.txt and printed; the test fails only when a variant does not run. That
 * the variants compute the same is the execution test of the same file (all_agree).
 */
class PairLoopsBench {
    static final String[] NAMES = { "frames only", "A counter, struct array", "B sorted list, struct array",
            "C counter, one array per field", "D sorted list, one array per field" };
    static final int FRAMES = 32;

    @TempDir
    Path tmp;

    @Test
    void theVariantsCycles() throws Exception {
        Path src = Path.of(PairLoopsBench.class.getResource("/exec/pair_loops.c").toURI());
        long[] cycles = new long[NAMES.length];
        for (int v = 0; v < NAMES.length; v++) {
            Compiler.Options o = new Compiler.Options(List.of(src), List.of(), List.of("VARIANT=" + v), "test_main",
                    tmp.resolve("pairs" + v + ".sfc")).withBackend(Compiler.Backend.MIR);
            Compiler.Result r = Compiler.compile(o);
            cycles[v] = new EmulatorRunner().run(r.rom(), List.of()).masterCycles();
        }
        StringBuilder t = new StringBuilder(String.format("%-36s %12s %8s%n", "pair loops (exec/pair_loops.c)", "per frame", "frames"));
        for (int v = 1; v < NAMES.length; v++) {
            long perFrame = (cycles[v] - cycles[0]) / FRAMES;
            assertTrue(perFrame > 0, NAMES[v] + " costs nothing?");
            t.append(String.format("%-36s %,12d %8.3f%n", NAMES[v], perFrame, perFrame / 357_368.0));
        }
        Path out = Files.createDirectories(Path.of("build/bench-pairs"));
        Files.writeString(out.resolve("pairs.txt"), t.toString(), StandardCharsets.UTF_8);
        System.out.print(t);
    }
}
