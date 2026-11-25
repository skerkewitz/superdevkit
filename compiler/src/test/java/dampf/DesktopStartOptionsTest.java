package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dampf.emu.ExampleCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the desktop's program starts a game: --seed reaches game_init, --set changes what the game
 * registered before the first tick, and a recording keeps both, so its replay plays the same game.
 * Skipped where raylib was not fetched (a superdevkit build linux before).
 */
class DesktopStartOptionsTest {
    private static final String GAME = """
            #include "sdevkit/loop.h"
            #include "sdevkit/snes.h"

            static uint8_t god;
            static int16_t speed = 1;
            static uint8_t autoplay;
            static uint16_t state[3];

            void game_init(uint16_t seed)
            {
                state[0] = seed;
                sdevkit_dbg_toggle("god mode", &god);
                sdevkit_dbg_range("speed", &speed, 0, 9);
                sdevkit_dbg_toggle("autoplay", &autoplay);
                sdevkit_dbg_text("goal", "the exit");
                sdevkit_dbg_value("position", &state[1]);
            }

            void game_tick(void)
            {
                /* the bot: the game makes its own pad */
                uint16_t held = autoplay ? (uint16_t)(state[1] & 1 ? SDEVKIT_KEY_LEFT : SDEVKIT_KEY_RIGHT) : sdevkit_input.pad[0];
                if (held & SDEVKIT_KEY_RIGHT) {
                    state[1] = (uint16_t)(state[1] + 3);
                }
                state[1] = (uint16_t)(state[1] + (uint16_t)speed);
                state[2] = god;
                sdevkit_dbg_hash(state, sizeof state);
            }
            """;

    @TempDir
    Path tmp;
    private Path program;
    private Path check;

    @BeforeEach
    void build() throws Exception {
        assumeTrue(Files.isRegularFile(Path.of("build/raylib/linux/lib/libraylib.a")), "raylib not fetched");
        Path dir = tmp.resolve("game");
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("superdevkit.toml"), "name = \"game\"\nkit = \""
                + Path.of("").toAbsolutePath().toString().replace("\\", "/") + "\"\nsources = [\"src\"]\n");
        Files.writeString(dir.resolve("src/game.c"), GAME);
        ExamplesTest.buildTarget(dir, "linux");
        program = dir.resolve("build/linux/game");
        check = tmp.resolve("check.txt");
        Files.writeString(check, "ticks 20\n");
    }

    private record Ran(int exit, String out) {}

    private Ran play(String file, String out, String... options) throws IOException, InterruptedException {
        Files.createDirectories(tmp.resolve(out));
        List<String> cmd = new ArrayList<>(List.of(program.toString(), "--check", file, "--out", tmp.resolve(out).toString()));
        cmd.addAll(List.of(options));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return new Ran(p.exitValue(), log);
    }

    private List<String> hashes(String out) throws IOException {
        return Files.readAllLines(tmp.resolve(out).resolve("hashes.txt"));
    }

    @Test
    void theSeedAndTheSetsReachTheGameAndARecordingKeepsThem() throws Exception {
        assertEquals(0, play(check.toString(), "plain").exit());
        assertEquals(0, play(check.toString(), "seeded", "--seed", "7").exit());
        assertNotEquals(hashes("plain"), hashes("seeded"), "the seed reaches game_init");
        assertEquals(0, play(check.toString(), "set", "--set", "speed=5").exit());
        assertNotEquals(hashes("plain"), hashes("set"), "the range is set before the first tick");

        Path recording = tmp.resolve("recording.txt");
        Ran all = play(check.toString(), "all", "--seed", "0x10", "--set", "god mode=1", "--set", "speed=5",
                "--record", recording.toString());
        assertEquals(0, all.exit(), all.out());
        String text = Files.readString(recording);
        assertTrue(text.contains("seed 16\n") && text.contains("set 1 god mode\n") && text.contains("set 5 speed\n"), text);

        Ran replay = play(recording.toString(), "replay");
        assertEquals(0, replay.exit(), "a replay without the options plays the same game: " + replay.out());
        assertEquals(hashes("all"), hashes("replay"));

        IOException refused = assertThrows(IOException.class, () -> ExampleCheck.parse(recording));
        assertTrue(refused.getMessage().contains("only the desktop"), refused.getMessage());
    }

    @Test
    void aGameCanPlayItselfThroughARegisteredToggle() throws Exception {
        assertEquals(0, play(check.toString(), "human").exit());
        Path recording = tmp.resolve("bot.txt");
        assertEquals(0, play(check.toString(), "bot", "--set", "autoplay=1", "--record", recording.toString()).exit());
        assertNotEquals(hashes("human"), hashes("bot"), "the bot plays without a key pressed");
        Ran replay = play(recording.toString(), "again");
        assertEquals(0, replay.exit(), replay.out());
        assertEquals(hashes("bot"), hashes("again"));
    }

    @Test
    void theCommandLineComesAfterTheFile() throws Exception {
        Path file = tmp.resolve("with_set.txt");
        Files.writeString(file, "ticks 20\nseed 3\nset 2 speed\n");
        assertEquals(0, play(file.toString(), "file").exit());
        assertEquals(0, play(check.toString(), "same", "--seed", "3", "--set", "speed=2").exit());
        assertEquals(hashes("same"), hashes("file"));
        assertEquals(0, play(file.toString(), "over", "--seed", "4", "--set", "speed=6").exit());
        assertEquals(0, play(check.toString(), "direct", "--seed", "4", "--set", "speed=6").exit());
        assertEquals(hashes("direct"), hashes("over"));
    }

    /** Both stop before a window opens. */
    @Test
    void aJumpNeedsAReplayThatHasTheTick() throws Exception {
        Process alone = new ProcessBuilder(program.toString(), "--jump", "5").redirectErrorStream(true).start();
        String log = new String(alone.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(alone.waitFor(30, TimeUnit.SECONDS));
        assertEquals(2, alone.exitValue(), log);
        assertTrue(log.contains("--jump goes with --replay"), log);

        Path recording = tmp.resolve("short.txt");
        assertEquals(0, play(check.toString(), "short", "--record", recording.toString()).exit());
        Process beyond = new ProcessBuilder(program.toString(), "--replay", recording.toString(), "--jump", "21")
                .redirectErrorStream(true).start();
        log = new String(beyond.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(beyond.waitFor(30, TimeUnit.SECONDS));
        assertEquals(2, beyond.exitValue(), log);
        assertTrue(log.contains("the recording has 20 ticks"), log);
    }

    @Test
    void aWrongNameOrValueStopsWithAMessage() throws Exception {
        Ran unknown = play(check.toString(), "e1", "--set", "lives=3");
        assertEquals(2, unknown.exit(), unknown.out());
        assertTrue(unknown.out().contains("no 'lives'") && unknown.out().contains("god mode, speed"), unknown.out());
        Ran outside = play(check.toString(), "e2", "--set", "speed=10");
        assertEquals(2, outside.exit(), outside.out());
        assertTrue(outside.out().contains("outside 0 to 9"), outside.out());
        Ran shown = play(check.toString(), "e5", "--set", "position=1");
        assertEquals(2, shown.exit(), shown.out());
        assertTrue(shown.out().contains("only shown"), shown.out());
        assertEquals(2, play(check.toString(), "e3", "--set", "speed=fast").exit());
        assertEquals(2, play(check.toString(), "e4", "--seed", "70000").exit());
    }
}
