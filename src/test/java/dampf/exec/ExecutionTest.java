package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dampf.Compiler;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Execution tests: every program in {@code src/test/resources/exec} runs on the PC (gcc) and on
 * the SNES (dampf), and all its globals must have the same values afterwards. The SNES side runs
 * in dampf's emulator, and also in Mesen 2 when one is available (.mesen/ or -Pmesen=...), so the
 * emulator is checked against a complete SNES emulator too. Each program's entry function is
 * {@code test_main}. Each program is built twice, with the naive code generator and with the one
 * from MIR; the emulator runs both.
 */
class ExecutionTest {
    @TempDir
    Path tmp;

    @TestFactory
    Stream<DynamicTest> sameResultsOnPcAndSnes() throws IOException, URISyntaxException {
        String mesen = System.getProperty("dampf.mesen");
        MesenRunner mesenRunner = mesen == null ? null : new MesenRunner(Path.of(mesen));
        EmulatorRunner emulator = new EmulatorRunner();
        Path dir = Path.of(ExecutionTest.class.getResource("/exec").toURI());
        List<Path> programs;
        try (Stream<Path> s = Files.list(dir)) {
            programs = s.filter(p -> p.toString().endsWith(".c")).sorted().toList();
        }
        return programs.stream().map(src -> DynamicTest.dynamicTest(src.getFileName().toString(), () -> {
            String name = src.getFileName().toString().replace(".c", "");
            Path work = Files.createDirectories(tmp.resolve(name));
            Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                    work.resolve(name + ".sfc")));
            List<Observed> globals = Observed.of(r.program().globals());
            Map<String, Long> pc = PcReference.wrap(PcReference.run(src, "test_main", globals, work), globals);
            assertEquals(pc, MirRunner.run(r.program(), "test_main", globals), "globals after test_main() (PC vs MIR interpreter)");
            assertEquals(pc, MirRunner.run(r.program(), "test_main", globals, true), "globals after test_main() (PC vs optimized MIR)");
            Map<String, Long> snes = emulator.run(r.rom(), globals).values();
            assertEquals(pc, snes, "globals after test_main() (PC vs SNES in dampf's emulator)");
            if (mesenRunner != null) {
                assertEquals(pc, mesenRunner.run(r.rom(), globals), "globals after test_main() (PC vs SNES in Mesen)");
            }
            Compiler.Result m = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                    work.resolve(name + "_mir.sfc")).withBackend(Compiler.Backend.MIR));
            assertEquals(pc, emulator.run(m.rom(), globals).values(), "globals after test_main() (PC vs SNES, code generated from MIR)");
        }));
    }
}
