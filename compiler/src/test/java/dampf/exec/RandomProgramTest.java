package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dampf.Compiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Random programs (test layer 3): each one runs on the PC (clang) and on the SNES (dampf, in
 * dampf's emulator), and all globals must match. A failure names its seed and the command that
 * runs that program again, and prints the program.
 *
 * <p>Two generators: {@link RandomProgram} (version 1: scalars, expressions, short loops) with
 * the fixed seeds 1 to 50, the same programs in every build; and {@link RandomLoopProgram}
 * (version 2: arrays, counted loops, helpers, pointers, the optimizer's loop passes) with 50 seeds
 * a day, from {@code LocalDate.now().toEpochDay() * 1000}, so every day tries new programs.
 * {@code -Prandom=N} runs N programs of each, {@code -PrandomSeed=S} starts both at seed S;
 * {@code dev/random_soak.sh} runs thousands before a release, in shares at once ({@link RandomSoak}).
 */
class RandomProgramTest {
    @TempDir
    Path tmp;

    private static int count() {
        return Integer.getInteger("dampf.random.count", 50);
    }

    static final String RANDOM = "randomProgramsMatchGcc";
    static final String LOOP = "loopProgramsMatchGcc";

    @TestFactory
    Stream<DynamicTest> randomProgramsMatchGcc() {
        long first = Long.getLong("dampf.random.seed", firstSeed(RANDOM));
        return LongStream.range(first, first + count()).mapToObj(seed -> DynamicTest.dynamicTest("seed " + seed,
                () -> check(seed, RANDOM, tmp)));
    }

    @TestFactory
    Stream<DynamicTest> loopProgramsMatchGcc() {
        long first = Long.getLong("dampf.random.seed", firstSeed(LOOP));
        return LongStream.range(first, first + count()).mapToObj(seed -> DynamicTest.dynamicTest("seed " + seed,
                () -> check(seed, LOOP, tmp)));
    }

    /** The first seed of a generator when none is given: 1, or today's for the loop programs. */
    static long firstSeed(String factory) {
        return factory.equals(LOOP) ? LocalDate.now().toEpochDay() * 1000 : 1;
    }

    /**
     * Runs the program of a generator ({@link #RANDOM} or {@link #LOOP}) with this seed on the PC
     * and on the SNES, in a folder of its own under tmp; throws (an AssertionError) if they differ.
     */
    static void check(long seed, String factory, Path tmp) {
        String program = factory.equals(LOOP) ? new RandomLoopProgram(seed).generate() : new RandomProgram(seed).generate(10, 30);
        String again = "seed " + seed + " (again: ./gradlew :compiler:test --tests 'dampf.exec.RandomProgramTest." + factory
                + "' -PrandomSeed=" + seed + " -Prandom=1)";
        assertTimeoutPreemptively(java.time.Duration.ofMinutes(2), () -> {
            // a random program is short by construction (RandomLoopProgram counts the rounds of its
            // loops), but its bodies may call helpers and divide 32-bit values: the naive code of
            // one in a few hundred needs more than the usual 600 frames (seed 16842). A program that
            // hangs still fails, after 2,000 frames (6,000 with a pass limit).
            EmulatorRunner runner = new EmulatorRunner((dampf.util.PassLimit.configured() >= 0 ? 6_000L : 2_000L) * 357_366);
            Path work = Files.createDirectories(tmp.resolve("seed" + seed));
            Path src = work.resolve("random" + seed + ".c");
            Files.writeString(src, program);
            Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                    work.resolve("random.sfc")));
            List<Observed> globals = Observed.of(r.program());
            Map<String, Long> pc = PcReference.wrap(PcReference.run(src, "test_main", globals, work), globals);
            assertEquals(pc, MirRunner.run(r.program(), "test_main", globals), () -> again + " differs (PC vs MIR); the program:\n" + program);
            assertEquals(pc, MirRunner.run(r.program(), "test_main", globals, true), () -> again + " differs (PC vs optimized MIR); the program:\n" + program);
            Map<String, Long> snes = runner.run(r.rom(), globals).values();
            assertEquals(pc, snes, () -> again + " differs (PC vs SNES); the program:\n" + program);
            Compiler.Result m = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                    work.resolve("random_mir.sfc")).withBackend(Compiler.Backend.MIR));
            assertEquals(pc, runner.run(m.rom(), globals).values(), () -> again + " differs (PC vs SNES from MIR); the program:\n" + program);
            Timing.check(m, runner, again);
        }, () -> again + " took too long: hangs? The program:\n" + program);
    }
}
