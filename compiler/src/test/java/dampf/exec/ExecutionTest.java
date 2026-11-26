package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
 * Execution tests: every program in {@code compiler/src/test/resources/exec} runs on the PC (clang) and on
 * the SNES (dampf), and all its globals must have the same values afterwards. The SNES side runs
 * in dampf's emulator, and also in Mesen when one is available (.mesen/ or -Pmesen=...), so the
 * emulator is checked against a complete SNES emulator too (a program with an interrupt handler
 * with both code generators). Each program's entry function is
 * {@code test_main}. Each program is built twice, with the naive code generator and with the one
 * from MIR; the emulator runs both. The MIR interpreter runs each program too, except those with an
 * interrupt handler ({@code exec/interrupts.c}). Both sides find the kit's public headers
 * ({@code lib/include}, for {@code exec/published.c}).
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
            String only = System.getProperty("dampf.exec.only");   // -Pexec=<name>
            programs = s.filter(p -> p.toString().endsWith(".c"))
                    .filter(p -> only == null || p.getFileName().toString().equals(only + ".c")).sorted().toList();
        }
        return programs.stream().map(src -> DynamicTest.dynamicTest(src.getFileName().toString(), () -> assertTimeoutPreemptively(java.time.Duration.ofMinutes(3), () -> {
            String name = src.getFileName().toString().replace(".c", "");
            Path work = Files.createDirectories(tmp.resolve(name));
            Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(src), List.of(PcReference.KIT_INCLUDE), List.of(), "test_main",
                    work.resolve(name + ".sfc")));
            List<Observed> globals = Observed.of(r.program());
            Map<String, Long> pc = PcReference.wrap(PcReference.run(src, "test_main", globals, work), globals);
            // the MIR interpreter has no interrupts and no hardware registers: a program with an
            // interrupt handler (DAMPF_INTERRUPT) runs only on the PC and on the SNES
            if (r.program().functions().stream().noneMatch(f -> f.interrupt() != null)) {
                assertEquals(pc, MirRunner.run(r.program(), "test_main", globals), "globals after test_main() (PC vs MIR interpreter)");
                assertEquals(pc, MirRunner.run(r.program(), "test_main", globals, true), "globals after test_main() (PC vs optimized MIR)");
            }
            Map<String, Long> snes = emulator.run(r.rom(), globals).values();
            assertEquals(pc, snes, "globals after test_main() (PC vs SNES in dampf's emulator)");
            if (mesenRunner != null) {
                assertEquals(pc, mesenRunner.run(r.rom(), globals), "globals after test_main() (PC vs SNES in Mesen)");
            }
            Compiler.Result m = Compiler.compile(new Compiler.Options(List.of(src), List.of(PcReference.KIT_INCLUDE), List.of(), "test_main",
                    work.resolve(name + "_mir.sfc")).withBackend(Compiler.Backend.MIR));
            assertEquals(pc, emulator.run(m.rom(), globals).values(), "globals after test_main() (PC vs SNES, code generated from MIR)");
            if (mesenRunner != null && m.program().functions().stream().anyMatch(f -> f.interrupt() != null)) {
                // interrupts at any instruction: Mesen runs the code from MIR too
                assertEquals(pc, mesenRunner.run(m.rom(), globals), "globals after test_main() (PC vs SNES in Mesen, code generated from MIR)");
            }
            Timing.check(m, emulator, src.getFileName().toString());
            // block counts only change the order of the blocks: an arbitrary profile (about a third
            // of the blocks "never ran") must give the same results
            Compiler.Result l = Compiler.compile(new Compiler.Options(List.of(src), List.of(PcReference.KIT_INCLUDE), List.of(), "test_main",
                    work.resolve(name + "_layout.sfc")).withBackend(Compiler.Backend.MIR)
                    .withBlockCounts((function, block) -> (long) Math.floorMod((function + block).hashCode(), 3)));
            assertEquals(pc, emulator.run(l.rom(), globals).values(), "globals after test_main() (PC vs SNES, blocks laid out by a profile)");
            Timing.check(l, emulator, src.getFileName().toString() + " (laid out by a profile)");
        }, "took too long: hangs?")));
    }
}
