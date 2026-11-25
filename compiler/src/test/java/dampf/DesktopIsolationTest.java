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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The desktop build lets a game reach only the console API (ADR-6F3CK2AR, superdevkit build linux):
 * the host's headers are not there, and a function of raylib or libc declared by hand stops the
 * build. The web build (emcc) the same. Each target is skipped where its raylib was not fetched (a
 * superdevkit build linux or web before: the tests download nothing), web also without emcc.
 */
class DesktopIsolationTest {

    @TempDir
    Path tmp;

    private static boolean linux() {
        return Files.isRegularFile(Path.of("build/raylib/linux/lib/libraylib.a"));
    }

    /** The web builds here: raylib was fetched for the web, and emcc runs. */
    static boolean webBuilds() {
        if (!Files.isRegularFile(Path.of("build/raylib/web/lib/libraylib.a"))) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(System.getenv().getOrDefault("EMCC", "emcc"), "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** The targets whose builds can run here. */
    private static java.util.List<String> targets() {
        java.util.List<String> t = new java.util.ArrayList<>();
        if (linux()) {
            t.add("linux");
        }
        if (webBuilds()) {
            t.add("web");
        }
        assumeTrue(!t.isEmpty(), "raylib not fetched for linux or web (or no emcc)");
        return t;
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

    /** superdevkit build TARGET, in this Java. */
    private static Run build(Path dir, String target) throws Exception {
        java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(log, true, StandardCharsets.UTF_8);
        int status;
        try {
            superdevkit.project.Project p = superdevkit.project.Project.read(dir);
            status = superdevkit.project.Build.run(superdevkit.project.Build.plan(p, superdevkit.project.Kit.of(p), target, false),
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
        assumeTrue(linux(), "raylib not fetched");
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
                    if (!ok) { sdevkit_vram_copy(0, b.bytes, 16); }
                }
                """);
        Run build = build(dir, "linux");
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
        for (String target : targets()) {
            Run build = build(dir, target);
            assertNotEquals(0, build.exit(), target + ": " + build.out());
            assertTrue(build.out().contains("stdio.h"), target + ": " + build.out());
        }
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
        for (String target : targets()) {
            Run build = build(dir, target);
            assertNotEquals(0, build.exit(), target + ": " + build.out());
            assertTrue(build.out().contains("uses DrawText, which is not part of superdevkit's console API"), target + ": " + build.out());
        }
    }
}
