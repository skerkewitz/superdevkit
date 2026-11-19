package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dampf.emu.ExampleCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Every example builds for the SNES and plays its {@code check.txt}, on the SNES and on the
 * desktop, with the same checksum after every tick:
 *
 * <ol>
 *   <li>the SNES: its build.sh converts the assets (CONVERT_ONLY=1), then dampf compiles it with its
 *       argument file (from MIR), and dampf's emulator plays the ROM with the input of
 *       {@code check.txt} ({@link ExampleCheck}), the checksums into
 *       {@code build/emu-check/hashes.txt}, the frames per tick into {@code frames.txt} there (on average, and of the slowest 60 ticks in
 *       a row), and the master cycles from the end of each tick to the end of the next into
 *       {@code ticks.txt}; a {@code budget T1 T2} line of {@code check.txt} fails the test when a tick
 *       from T1 to T2 - 1 took more than a frame;</li>
 *   <li>the desktop (where raylib was fetched, tools/fetch_raylib.sh linux: the tests download
 *       nothing): tools/build_desktop.sh, then the same check without a window, without a byte
 *       the SNES would lose and without a register write Rachael does not draw, its checksums
 *       into {@code build/desktop-check/hashes.txt}, and the run's recording ({@code --record}:
 *       the format a session in the window is saved in with F11) must have the same buttons and
 *       the SNES's checksum for every tick;</li>
 *   <li>both lists the same: dampf's code and gcc's, lib/snes and Rachael computed the same game.
 *       The first tick that differs is named.</li>
 * </ol>
 *
 * The picture is compared by dev/compare_example.sh (Mesen), which also compares Mesen's
 * checksums with the desktop's.
 */
public class ExamplesTest {

    @TestFactory
    Stream<DynamicTest> everyExampleBuildsAndPlaysTheSameOnBoth() throws IOException {
        List<Path> examples = examples();
        assertTrue(!examples.isEmpty(), "no examples with an argument file");
        boolean desktop = Files.isRegularFile(Path.of("build/raylib/linux/lib/libraylib.a"));
        return examples.stream().map(ex -> DynamicTest.dynamicTest(ex.getFileName().toString(), () -> {
            String name = ex.getFileName().toString();
            Compiler.Result r = compile(ex, ex.resolve("build/" + name + "_test.sfc"));
            assertTrue(Files.size(r.rom().rom()) >= 32 * 1024, "no ROM written");

            Path check = ex.resolve("check.txt");
            if (!Files.isRegularFile(check)) {
                return;
            }
            ExampleCheck.Input input = ExampleCheck.parse(check);
            ExampleCheck.Result snes = ExampleCheck.run(Files.readAllBytes(r.rom().rom()), input, 40 * input.ticks() + 2000);
            Path emu = Files.createDirectories(ex.resolve("build/emu-check"));
            Files.write(emu.resolve("hashes.txt"), snes.lines());
            List<String> ticks = new ArrayList<>();
            for (int i = 0; i < snes.tickEnds().size(); i++) {
                ticks.add(i + " " + (snes.tickEnds().get(i) - (i == 0 ? snes.tickEnds().get(0) : snes.tickEnds().get(i - 1))));
            }
            Files.write(emu.resolve("ticks.txt"), ticks);
            long[] second = snes.slowestWindow(60);
            Files.writeString(emu.resolve("frames.txt"), String.format(
                    "frames %d ticks %d frames per tick %.3f slowest tick %d frames slowest 60 ticks %.3f frames per tick from tick %d%n",
                    snes.frames(), snes.hashes().size(), snes.framesPerTick(), (snes.slowestTick() + ExampleCheck.FRAME - 1) / ExampleCheck.FRAME,
                    second[0] / 60.0 / ExampleCheck.FRAME, second[1]));
            for (ExampleCheck.Budget b : input.budgets()) {
                List<Long> over = snes.overBudget(b);
                assertTrue(over.isEmpty(), name + ": ticks " + over + " took more than a frame (budget " + b.from() + " to " + b.to()
                        + " in check.txt; build/emu-check/ticks.txt has the cycles of every tick)");
            }
            if (!desktop) {
                return;
            }
            run("sh", "tools/build_desktop.sh", ex.toString());
            Path out = Files.createDirectories(ex.resolve("build/desktop-check"));
            Path recording = out.resolve("recording.txt");
            String result = run(ex.resolve("build/linux/" + name).toString(), "--check", check.toString(), "--out", out.toString(),
                    "--record", recording.toString());
            assertTrue(result.contains(" lost 0"), result);
            assertTrue(result.contains(" undrawn 0"), result);
            List<String> pc = Files.readAllLines(out.resolve("hashes.txt"));
            List<String> sn = snes.lines();
            for (int i = 0; i < Math.min(pc.size(), sn.size()); i++) {
                if (!pc.get(i).equals(sn.get(i))) {
                    fail(name + ": the SNES and the desktop differ first after tick " + i + " (SNES " + sn.get(i) + ", desktop " + pc.get(i)
                            + "; build/emu-check and build/desktop-check have all)");
                }
            }
            assertEquals(pc.size(), sn.size(), name + ": ticks played");

            // the desktop's recording of the same run (lib/desktop, F11 in the window) is a check.txt
            // of its own: the same buttons every tick, and the SNES's checksums
            ExampleCheck.Input rec = ExampleCheck.parse(recording);
            assertEquals(input.ticks(), rec.ticks(), name + ": ticks recorded");
            for (long t = 0; t < input.ticks(); t++) {
                assertEquals(input.pad(t), rec.pad(t), name + ": the recorded buttons of tick " + t);
                assertEquals(snes.hashes().get((int) t), rec.recorded(t), name + ": the recorded checksum of tick " + t);
            }
        }));
    }

    /** The examples: the folders of examples/ with an argument file of their name. */
    public static List<Path> examples() throws IOException {
        try (Stream<Path> s = Files.list(Path.of("examples"))) {
            return s.filter(d -> Files.isRegularFile(d.resolve(d.getFileName() + ".args"))).sorted().toList();
        }
    }

    /** An example built as its build.sh does: the assets converted, then compiled with dampf (MIR). */
    public static Compiler.Result compile(Path ex, Path out) throws IOException, InterruptedException, dampf.frontend.CompileError {
        return Compiler.compile(options(ex, out).withBackend(Compiler.Backend.MIR));
    }

    /** The options of an example's build (its argument file), after its assets are converted. */
    public static Compiler.Options options(Path ex, Path out) throws IOException, InterruptedException {
        String name = ex.getFileName().toString();
        if (Files.isRegularFile(ex.resolve("build.sh"))) {
            ProcessBuilder pb = new ProcessBuilder("sh", ex.resolve("build.sh").toString()).redirectErrorStream(true);
            pb.environment().put("CONVERT_ONLY", "1");
            Process p = pb.start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(120, TimeUnit.SECONDS);
            assertEquals(0, p.exitValue(), log);
        }
        List<String> args = new ArrayList<>(ConfigFile.expand(List.of("build", "@" + ex.resolve(name + ".args"))));
        return options(args, out);
    }

    /** The options dampf build takes from these arguments (-I, --entry, sources). */
    private static Compiler.Options options(List<String> args, Path out) throws IOException {
        List<Path> includes = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        String entry = "main";
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-I")) {
                includes.add(Path.of(args.get(++i)));
            } else if (a.equals("--entry")) {
                entry = args.get(++i);
            } else if (a.startsWith("-")) {
                throw new IllegalArgumentException("an option the test does not know: " + a);
            } else {
                sources.addAll(ConfigFile.sources(Path.of(a)));
            }
        }
        return new Compiler.Options(sources, includes, List.of(), entry, out);
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(300, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        assertEquals(0, p.exitValue(), String.join(" ", cmd) + "\n" + out);
        return out;
    }
}
