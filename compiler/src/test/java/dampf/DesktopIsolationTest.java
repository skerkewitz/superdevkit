package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The desktop build lets a game reach only the console API (ADR-6F3CK2AR, superdevkit build linux):
 * the host's headers are not there, and a function of raylib or libc declared by hand stops the
 * build. Skipped where raylib was not fetched (a superdevkit build linux before: the tests download nothing).
 */
class DesktopIsolationTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void raylib() {
        assumeTrue(Files.isRegularFile(Path.of("build/raylib/linux/lib/libraylib.a")), "raylib not fetched");
    }

    private static final String CALLBACKS = """
            void game_init(uint16_t seed) { (void)seed; }
            void game_vblank(void) { }
            """;

    /** A project of the kit's repository: superdevkit.toml (nothing to convert) and src/. */
    private Path game(String name, String source) throws IOException {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("superdevkit.toml"), "name = \"" + name + "\"\nkit = \""
                + Path.of("").toAbsolutePath().toString().replace("\\", "/") + "\"\nsources = [\"src\"]\n");
        Files.writeString(dir.resolve("src/game.c"), source);
        return dir;
    }

    private record Run(int exit, String out) {}

    /** superdevkit build linux, in this Java. */
    private static Run build(Path dir) throws Exception {
        java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(log, true, StandardCharsets.UTF_8);
        int status;
        try {
            superdevkit.project.Project p = superdevkit.project.Project.read(dir);
            status = superdevkit.project.Build.run(superdevkit.project.Build.plan(p, superdevkit.project.Kit.of(p), "linux", false),
                    false, false, false, ps, ps);
        } catch (superdevkit.project.Build.Failure | superdevkit.project.Project.Error e) {
            ps.println(e.getMessage());
            status = 1;
        }
        return new Run(status, log.toString(StandardCharsets.UTF_8));
    }

    private static Run run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(300, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return new Run(p.exitValue(), out);
    }

    @Test
    void aGameOnTheConsoleApiBuildsAndCopiesStructsRight() throws Exception {
        // A struct copy large enough that gcc calls memcpy: in this file that is dampf's static one
        // (<string.h>). Should a copy go wrong, the game writes VRAM while the picture is drawn,
        // and the check reports the bytes as lost.
        Path dir = game("good", """
                #include <stdint.h>
                #include <string.h>
                #include "sdevkit/loop.h"
                #include "sdevkit/snes.h"
                typedef struct { uint8_t bytes[300]; uint16_t sum; } Big;
                static Big a, b;
                static uint8_t ok;
                """ + CALLBACKS + """
                void game_tick(const uint16_t pad[SDEVKIT_PADS])
                {
                    uint16_t i;
                    (void)pad;
                    for (i = 0; i < 300; i++) { a.bytes[i] = (uint8_t)(i * 7); }
                    a.sum = 12345;
                    b = a;
                    memset(&a, 0, sizeof a);
                    ok = b.bytes[299] == (uint8_t)(299 * 7) && b.sum == 12345 && a.bytes[5] == 0;
                    memcpy(&a, &b, sizeof a);
                    ok = ok && a.bytes[100] == (uint8_t)(100 * 7);
                    if (!ok) { sdevkit_dma_copy_vram(b.bytes, 0, 16); }
                }
                """);
        Run build = build(dir);
        assertEquals(0, build.exit(), build.out());
        Files.writeString(dir.resolve("check.txt"), "ticks 3\n");
        Run check = run(dir.resolve("build/linux/good").toString(), "--check", dir.resolve("check.txt").toString(), "--out", tmp.toString());
        assertEquals(0, check.exit(), check.out());
        assertTrue(check.out().contains("lost 0"), check.out());
    }

    @Test
    void aHostHeaderIsNotThere() throws Exception {
        Path dir = game("stdio", """
                #include <stdio.h>
                #include <stdint.h>
                #include "sdevkit/loop.h"
                """ + CALLBACKS + """
                void game_tick(const uint16_t pad[SDEVKIT_PADS]) { (void)pad; printf("hi\\n"); }
                """);
        Run build = build(dir);
        assertNotEquals(0, build.exit(), build.out());
        assertTrue(build.out().contains("stdio.h"), build.out());
    }

    @Test
    void aFunctionOutsideTheApiStopsTheBuild() throws Exception {
        Path dir = game("raylib", """
                #include <stdint.h>
                #include "sdevkit/loop.h"
                void DrawText(const char *text, int x, int y, int size, uint32_t colour);
                """ + CALLBACKS + """
                void game_tick(const uint16_t pad[SDEVKIT_PADS]) { (void)pad; DrawText("hi", 0, 0, 10, 0); }
                """);
        Run build = build(dir);
        assertNotEquals(0, build.exit(), build.out());
        assertTrue(build.out().contains("uses DrawText, which is not part of superdevkit's console API"), build.out());
    }
}
