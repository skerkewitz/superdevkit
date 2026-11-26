package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The game's IRQ (lib/include/sdevkit/irq.h, ADR-6FRX1GYC): dev/irq_check splits the screen twice
 * with values the tick publishes and changes every tick, with short ticks and long ones (A held).
 * It plays its check.txt as {@link ExamplesTest} plays an example, on the SNES (dampf's emulator),
 * the desktop and the web, with the same checksum after every tick; where Mesen is, its pictures
 * on the SNES are the desktop's pixel for pixel (and the web's the desktop's), which only holds if
 * the handler's writes land in the horizontal blank. And a handler that writes before
 * sdevkit_irq_wait_hblank fails the desktop check.
 */
class IrqCheckTest {
    private static final Path CHECK = Path.of("dev/irq_check");

    @TempDir
    Path tmp;

    @Test
    void theSnesTheDesktopAndTheWebPlayTheSameAndShowMesensPictures() throws Exception {
        boolean desktop = ExamplesTest.desktop(), web = ExamplesTest.web();
        ExamplesTest.playsTheSameOnBoth(CHECK, desktop, web);
        if (web && desktop) {
            run(List.of("python3", "-I", "dev/compare_shots.py", CHECK.resolve("build/web-check").toString(),
                    CHECK.resolve("build/desktop-check").toString()), 0);
        }
        String mesen = System.getProperty("dampf.mesen");
        if (mesen == null || !desktop) {
            return;
        }
        Path rom = CHECK.resolve("build/snes/irq_check.sfc");
        Files.createDirectories(rom.getParent());
        ExamplesTest.compile(CHECK, CHECK.resolve("build/irq_mesen.sfc"));
        Files.copy(CHECK.resolve("build/irq_mesen.sfc"), rom, StandardCopyOption.REPLACE_EXISTING);
        List<String> cmd = List.of("env", "NOBUILD=1", "MESEN=" + Path.of(mesen).toAbsolutePath(), "sh", "dev/check_example.sh",
                CHECK.toString());
        run(cmd, 0);
        String shots = run(List.of("python3", "-I", "dev/compare_shots.py", CHECK.resolve("build/check").toString(),
                CHECK.resolve("build/desktop-check").toString()), 0);
        assertTrue(shots.contains("example_shot_"), shots);
    }

    /** The same game with the waits taken out: the desktop check counts its writes and fails. */
    @Test
    void aHandlerThatWritesBeforeTheWaitFailsTheDesktopCheck() throws Exception {
        assumeTrue(ExamplesTest.desktop(), "raylib not fetched for linux");
        Path game = tmp.resolve("irq_nowait");
        Files.createDirectories(game.resolve("src"));
        String c = Files.readString(CHECK.resolve("src/irq_check.c"));
        assertTrue(c.contains("        sdevkit_irq_wait_hblank();\n"));
        Files.writeString(game.resolve("src/irq_check.c"), c.replace("        sdevkit_irq_wait_hblank();\n", ""));
        Files.writeString(game.resolve("superdevkit.toml"), "name = \"irq_nowait\"\nkit = \""
                + Path.of(".").toAbsolutePath().normalize().toString().replace("\\", "/") + "\"\nsources = [\"src\"]\n");
        Files.copy(CHECK.resolve("check.txt"), game.resolve("check.txt"));
        ExamplesTest.buildTarget(game, "linux");
        Path out = Files.createDirectories(game.resolve("build/desktop-check"));
        String result = run(List.of(game.resolve("build/linux/irq_nowait").toString(), "--check", game.resolve("check.txt").toString(),
                "--out", out.toString()), 1);
        assertTrue(result.matches("(?s).*drawing [1-9].*"), result);
        assertTrue(result.contains("lost 0"), result);
    }

    private static String run(List<String> cmd, int status) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(900, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        assertEquals(status, p.exitValue(), String.join(" ", cmd) + "\n" + out);
        return out;
    }
}
