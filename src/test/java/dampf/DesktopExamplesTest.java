package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Every example builds for the desktop (tools/build_desktop.sh) and plays its check.txt there
 * without a window and without a byte the SNES would lose. Skipped where raylib was not fetched
 * (tools/fetch_raylib.sh linux): the tests do not download anything.
 */
class DesktopExamplesTest {

    @TestFactory
    Stream<DynamicTest> everyExamplePlaysOnTheDesktop() throws IOException {
        assumeTrue(Files.isRegularFile(Path.of("build/raylib/linux/lib/libraylib.a")), "raylib not fetched");
        List<Path> examples;
        try (Stream<Path> s = Files.list(Path.of("examples"))) {
            examples = s.filter(d -> Files.isRegularFile(d.resolve("check.txt"))).sorted().toList();
        }
        return examples.stream().map(ex -> DynamicTest.dynamicTest(ex.getFileName().toString(), () -> {
            String name = ex.getFileName().toString();
            run("sh", "tools/build_desktop.sh", ex.toString());
            Path out = Files.createDirectories(ex.resolve("build/desktop-check"));
            String result = run(ex.resolve("build/linux/" + name).toString(), "--check", ex.resolve("check.txt").toString(),
                    "--out", out.toString());
            assertEquals(true, result.contains(" lost 0"), result);
        }));
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
