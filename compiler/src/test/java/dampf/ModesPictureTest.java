package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The picture modes Rachael draws (ADR-6FSHCP06): examples/modes shows each on a screen of its own,
 * and where Mesen and the desktop build are here, its pictures on the SNES (Mesen) are the
 * desktop's pixel for pixel, every screen of its check.txt. {@link ExamplesTest} plays the same
 * check on the SNES (dampf's emulator), the desktop and the web with the same checksums; only Mesen
 * draws the SNES's picture.
 */
class ModesPictureTest {
    private static final Path MODES = Path.of("examples/modes");

    @Test
    void mesenAndTheDesktopDrawTheSamePictures() throws Exception {
        String mesen = System.getProperty("dampf.mesen");
        assumeTrue(mesen != null && ExamplesTest.desktop(), "Mesen or raylib for linux not here");
        ExamplesTest.buildTarget(MODES, "linux");
        Path out = MODES.resolve("build/desktop-check");
        Files.createDirectories(out);
        run(List.of(MODES.resolve("build/linux/modes").toString(), "--check", MODES.resolve("check.txt").toString(),
                "--out", out.toString()), 0);
        Path rom = MODES.resolve("build/snes/modes.sfc");
        Files.createDirectories(rom.getParent());
        ExamplesTest.compile(MODES, MODES.resolve("build/modes_mesen.sfc"));
        Files.copy(MODES.resolve("build/modes_mesen.sfc"), rom, StandardCopyOption.REPLACE_EXISTING);
        run(List.of("env", "NOBUILD=1", "MESEN=" + Path.of(mesen).toAbsolutePath(), "sh", "dev/check_example.sh",
                MODES.toString()), 0);
        String shots = run(List.of("python3", "-I", "dev/compare_shots.py", MODES.resolve("build/check").toString(),
                out.toString()), 0);
        assertEquals(11, shots.split("0 pixels differ", -1).length - 1, shots);
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
