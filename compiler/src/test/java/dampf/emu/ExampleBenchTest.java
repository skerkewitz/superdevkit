package dampf.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.ExamplesTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The work of every tick that ExampleBench writes: it adds up to the average it prints. */
class ExampleBenchTest {
    @TempDir
    Path tmp;

    @Test
    void theWorkPerTickAddsUpToTheAverage() throws Exception {
        Path example = Path.of("examples/hello").toAbsolutePath();
        Path out = example.resolve("build/bench-test/hello.sfc");
        Files.createDirectories(out.getParent());
        Compiler.Result built = ExamplesTest.compile(example, out);
        Path rom = built.rom().rom();
        Path lbl = Path.of(rom.toString().replaceFirst("\\.sfc$", ".lbl"));
        Path check = example.resolve("check.txt");
        Path work = tmp.resolve("work.txt");

        ExampleBench.main(new String[] { rom.toString(), lbl.toString(), check.toString(), work.toString() });

        ExampleBench.Measured m = ExampleBench.measure(rom, lbl, check);
        List<String> lines = Files.readAllLines(work);
        assertEquals(ExampleCheck.parse(check).ticks(), lines.size(), "one line per tick");
        long total = 0;
        for (int t = 0; t < lines.size(); t++) {
            String[] w = lines.get(t).split(" ");
            assertEquals(t, Integer.parseInt(w[0]));
            total += Long.parseLong(w[1]);
        }
        assertEquals(m.average(), total / lines.size());
        assertTrue(m.average() < ExampleCheck.FRAME / 2, "the wait for the vertical blank is not counted as work");
    }
}
