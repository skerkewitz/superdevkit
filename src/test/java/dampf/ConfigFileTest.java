package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Argument files for dampf build, check and parse (ADR record 28). */
class ConfigFileTest {

    @Test
    void optionsValuesCommentsAndQuotesAreRead(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("game.args");
        Files.writeString(f, """
                # a game
                -I src
                -I"lib include"
                -D BB_DEBUG
                -DLEVEL=3       # attached
                --entry main
                -o "build/game x.sfc"
                --slowrom

                src/main.c
                """);
        assertEquals(List.of("-I", dir.resolve("src").toString(), "-I" + dir.resolve("lib include"), "-D", "BB_DEBUG",
                "-DLEVEL=3", "--entry", "main", "-o", dir.resolve("build/game x.sfc").toString(), "--slowrom",
                dir.resolve("src/main.c").toString()), ConfigFile.read(f));
    }

    @Test
    void relativePathsAreResolvedAgainstTheFilesFolder() throws IOException {
        Path f = Path.of("build/tmp/configfiletest/sub/game.args");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "-I ../src\n--replace f=../asm/f.s\n--block-counts counts.txt\nmain.c\n/abs/x.c\n");
        assertEquals(List.of("-I", "build/tmp/configfiletest/src", "--replace", "f=build/tmp/configfiletest/asm/f.s",
                "--block-counts", "build/tmp/configfiletest/sub/counts.txt", "build/tmp/configfiletest/sub/main.c",
                "/abs/x.c"), ConfigFile.read(f));
    }

    @Test
    void theFileGoesInFrontOfTheCommandLine(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.args");
        Files.writeString(f, "-o x.sfc\n-I inc\n");
        List<String> args = ConfigFile.expand(List.of("build", "-I", "more", "@" + f, "-o", "y.sfc"));
        assertEquals(List.of("build", "-o", dir.resolve("x.sfc").toString(), "-I", dir.resolve("inc").toString(),
                "-I", "more", "-o", "y.sfc"), args);
        assertEquals(args, ConfigFile.expand(List.of("build", "-I", "more", "--config", f.toString(), "-o", "y.sfc")));
    }

    @Test
    void aFolderStandsForTheCFilesDirectlyInIt(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/debug"));
        for (String name : List.of("b.c", "a.c", "x.h", "debug/d.c")) {
            Files.writeString(dir.resolve("src").resolve(name), "");
        }
        assertEquals(List.of(dir.resolve("src/a.c"), dir.resolve("src/b.c")), ConfigFile.sources(dir.resolve("src")));
        assertEquals(List.of(dir.resolve("src/x.h")), ConfigFile.sources(dir.resolve("src/x.h")));
    }

    @Test
    void mistakesAreReportedWithFileAndLine(@TempDir Path dir) throws IOException {
        assertError(dir, "-I\n", "game.args:1: -I needs one value");
        assertError(dir, "\n--entry main extra\n", "game.args:2: --entry needs one value");
        assertError(dir, "a.c b.c\n", "game.args:1: one option with its value per line, or one file or folder");
        assertError(dir, "@other.args\n", "game.args:1: an argument file may not name another");
        assertError(dir, "--config other.args\n", "game.args:1: an argument file may not name another");
        assertError(dir, "-o \"x.sfc\n", "game.args:1: a quote is not closed");
    }

    @Test
    void oneFilePerCall(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.args"), "");
        Path b = Files.writeString(dir.resolve("b.args"), "");
        ConfigFile.Error e = assertThrows(ConfigFile.Error.class, () -> ConfigFile.expand(List.of("build", "@" + a, "@" + b)));
        assertTrue(e.getMessage().startsWith("only one argument file per call"), e.getMessage());
        assertThrows(ConfigFile.Error.class, () -> ConfigFile.expand(List.of("build", "@" + dir.resolve("none.args"))));
        assertThrows(ConfigFile.Error.class, () -> ConfigFile.expand(List.of("build", "--config")));
    }

    private static void assertError(Path dir, String text, String expected) throws IOException {
        Path f = Files.writeString(dir.resolve("game.args"), text);
        ConfigFile.Error e = assertThrows(ConfigFile.Error.class, () -> ConfigFile.read(f));
        assertTrue(e.getMessage().endsWith(expected), e.getMessage());
    }
}
