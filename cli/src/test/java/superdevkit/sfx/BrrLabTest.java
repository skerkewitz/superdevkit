package superdevkit.sfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The Lab (tools/brr_lab.html) in a real browser: runs the Playwright tests of tools/lab_test,
 * every use of the page and its bytes against the installed {@code superdevkit sfx}. Skipped
 * where Playwright is not installed (tools/lab_test/README.md: {@code npm ci} and the browser).
 */
class BrrLabTest {

    private static final Path DIR = Path.of("tools/lab_test");

    @Test
    void theLabsPlaywrightTestsPass() throws Exception {
        assumeTrue(Files.isDirectory(DIR.resolve("node_modules/@playwright/test")),
                "no Playwright in tools/lab_test: npm ci there (README.md) to run the Lab's tests");
        Process p = new ProcessBuilder("npx", "--no-install", "playwright", "test")
                .directory(DIR.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(5, TimeUnit.MINUTES), "Playwright took too long");
        assertEquals(0, p.exitValue(), out);
        assertTrue(!out.contains("skipped"), "a test was skipped (is the distribution installed?):\n" + out);
    }
}
