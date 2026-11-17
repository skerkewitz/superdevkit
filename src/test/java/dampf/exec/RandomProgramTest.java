package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dampf.Compiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Random programs (test layer 3): each one runs on the PC (gcc) and on the SNES (dampf, in
 * dampf's emulator), and all globals must match. The seeds are fixed, so a failure can be
 * reproduced; the failing program is printed in the message.
 *
 * <p>{@code -Prandom=N} runs N programs (default 50), {@code -PrandomSeed=S} starts at seed S.
 */
class RandomProgramTest {
    @TempDir
    Path tmp;

    @TestFactory
    Stream<DynamicTest> randomProgramsMatchGcc() {
        EmulatorRunner runner = new EmulatorRunner();
        int count = Integer.getInteger("dampf.random.count", 50);
        long first = Long.getLong("dampf.random.seed", 1);
        return LongStream.range(first, first + count).mapToObj(seed -> DynamicTest.dynamicTest("seed " + seed, () -> {
            Path work = Files.createDirectories(tmp.resolve("seed" + seed));
            Path src = work.resolve("random" + seed + ".c");
            String program = new RandomProgram(seed).generate(10, 30);
            Files.writeString(src, program);
            Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                    work.resolve("random.sfc")));
            List<Observed> globals = Observed.of(r.program().globals());
            Map<String, Long> pc = PcReference.wrap(PcReference.run(src, "test_main", globals, work), globals);
            assertEquals(pc, MirRunner.run(r.program(), "test_main", globals), () -> "seed " + seed + " differs (PC vs MIR); the program:\n" + program);
            Map<String, Long> snes = runner.run(r.rom(), globals).values();
            assertEquals(pc, snes, () -> "seed " + seed + " differs (PC vs SNES); the program:\n" + program);
            Compiler.Result m = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                    work.resolve("random_mir.sfc")).withBackend(Compiler.Backend.MIR));
            assertEquals(pc, runner.run(m.rom(), globals).values(), () -> "seed " + seed + " differs (PC vs SNES from MIR); the program:\n" + program);
        }));
    }
}
