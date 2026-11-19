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
 *   <li>the SNES: built as superdevkit build snes builds it (its superdevkit.toml: the assets
 *       converted, dampf from MIR with the kit's lib/snes), and dampf's emulator plays the ROM with the input of
 *       {@code check.txt} ({@link ExampleCheck}), the checksums into
 *       {@code build/emu-check/hashes.txt}, the frames per tick into {@code frames.txt} there (on average, and of the slowest 60 ticks in
 *       a row), and the master cycles from the end of each tick to the end of the next into
 *       {@code ticks.txt}; a {@code budget T1 T2} line of {@code check.txt} fails the test when a tick
 *       from T1 to T2 - 1 took more than a frame;</li>
 *   <li>the desktop (where raylib was fetched, by a superdevkit build linux before: the tests
 *       download nothing): built as superdevkit build linux builds it, then the same check without a window, without a byte
 *       the SNES would lose and without a register write Rachael does not draw, its checksums
 *       into {@code build/desktop-check/hashes.txt}, and the run's recording ({@code --record}:
 *       the format a session in the window is saved in with F11) must have the same buttons and
 *       the SNES's checksum for every tick;</li>
 *   <li>both lists the same: dampf's code and gcc's, lib/snes and Rachael computed the same game.
 *       The first tick that differs is named. And the same sound ({@code sound.txt} in both
 *       folders): what game_init uploaded into the audio RAM, and every DSP write the SPC700's driver made in the
 *       emulator is the one Rachael's DSP got, in the same tick; the desktop's check plays nothing
 *       that Rachael's DSP does not model ({@code unheard 0}).</li>
 * </ol>
 *
 * The picture is compared by dev/compare_example.sh (Mesen), which also compares Mesen's
 * checksums with the desktop's.
 */
public class ExamplesTest {

    @TestFactory
    Stream<DynamicTest> everyExampleBuildsAndPlaysTheSameOnBoth() throws IOException {
        List<Path> examples = examples();
        assertTrue(!examples.isEmpty(), "no examples with a superdevkit.toml");
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
            Files.write(emu.resolve("sound.txt"), snes.sound());
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
            buildDesktop(ex);
            Path out = Files.createDirectories(ex.resolve("build/desktop-check"));
            Path recording = out.resolve("recording.txt");
            String result = run(ex.resolve("build/linux/" + name).toString(), "--check", check.toString(), "--out", out.toString(),
                    "--record", recording.toString());
            assertTrue(result.contains(" lost 0"), result);
            assertTrue(result.contains(" undrawn 0"), result);
            assertTrue(result.contains(" unheard 0"), result);
            List<String> pc = Files.readAllLines(out.resolve("hashes.txt"));
            List<String> sn = snes.lines();
            for (int i = 0; i < Math.min(pc.size(), sn.size()); i++) {
                if (!pc.get(i).equals(sn.get(i))) {
                    fail(name + ": the SNES and the desktop differ first after tick " + i + " (SNES " + sn.get(i) + ", desktop " + pc.get(i)
                            + "; build/emu-check and build/desktop-check have all)");
                }
            }
            assertEquals(pc.size(), sn.size(), name + ": ticks played");
            List<String> pcSound = Files.readAllLines(out.resolve("sound.txt"));
            List<String> snSound = snes.sound();
            for (int i = 0; i < Math.min(pcSound.size(), snSound.size()); i++) {
                if (!pcSound.get(i).equals(snSound.get(i))) {
                    fail(name + ": the sound differs first in line " + (i + 1) + " of sound.txt (SNES " + snSound.get(i)
                            + ", desktop " + pcSound.get(i) + "; build/emu-check and build/desktop-check have all)");
                }
            }
            assertEquals(pcSound.size(), snSound.size(), name + ": the lines of sound.txt");

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

    /** The examples: the folders of examples/ with a superdevkit.toml. */
    public static List<Path> examples() throws IOException {
        try (Stream<Path> s = Files.list(Path.of("examples"))) {
            return s.filter(d -> Files.isRegularFile(d.resolve(superdevkit.project.Project.FILE))).sorted().toList();
        }
    }

    /** An example built as superdevkit build snes builds it: the assets converted, then dampf (MIR). */
    public static Compiler.Result compile(Path ex, Path out) throws Exception {
        return Compiler.compile(options(ex, out).withBackend(Compiler.Backend.MIR));
    }

    /** The dampf options of an example's SNES build (its superdevkit.toml), after its assets are converted. */
    public static Compiler.Options options(Path ex, Path out) throws Exception {
        superdevkit.project.Project p = superdevkit.project.Project.read(ex.toAbsolutePath().normalize());
        superdevkit.project.Kit kit = superdevkit.project.Kit.of(p);
        java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(log, true, StandardCharsets.UTF_8);
        assertEquals(0, superdevkit.project.Build.run(superdevkit.project.Build.assets(p), false, false, false, ps, ps),
                log.toString(StandardCharsets.UTF_8));
        return superdevkit.project.Build.dampfOptions(p, kit, out);
    }

    /** The desktop program of an example, as superdevkit build linux builds it. */
    static void buildDesktop(Path ex) throws Exception {
        superdevkit.project.Project p = superdevkit.project.Project.read(ex.toAbsolutePath().normalize());
        superdevkit.project.Kit kit = superdevkit.project.Kit.of(p);
        java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(log, true, StandardCharsets.UTF_8);
        assertEquals(0, superdevkit.project.Build.run(superdevkit.project.Build.plan(p, kit, "linux", false), false, false, false, ps, ps),
                log.toString(StandardCharsets.UTF_8));
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
