package dampf.lib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The debug view's calls (sdevkit_dbg_toggle, _range, _color, _action, _text, _value, _box; ADR-6F3CK03Z) and
 * the music's (sdevkit_music_play, _stop, not built on the SNES yet) are empty on the SNES, and
 * dampf removes them: a tick that registers and draws boxes costs the same
 * cycles as the same tick without them, best and worst case, so a game keeps them in its SNES
 * build without an #ifdef.
 */
class DebugCallsTest {
    @TempDir
    Path tmp;

    private long[] tick(String calls) throws Exception {
        Path src = tmp.resolve("tick" + calls.length() + ".c");
        Files.writeString(src, """
                #include "sdevkit/snes.h"
                static uint8_t frozen, pressed;
                static int16_t speed = 3;
                static uint16_t colour;
                static int16_t x[4] = { 10, 20, 30, 40 };
                static uint16_t sum;

                void test_main(void)
                {
                    uint8_t i;
                %s
                    for (i = 0; i < 4; i++) {
                        sum = (uint16_t)(sum + (uint16_t)x[i] + (uint16_t)speed + frozen + pressed + colour);
                    }
                }
                """.formatted(calls));
        List<Path> sources = new ArrayList<>(List.of(src));
        try (Stream<Path> s = Files.list(Path.of("lib/snes"))) {
            s.filter(p -> p.toString().endsWith(".c") && !p.getFileName().toString().equals("main.c")).sorted()
                    .forEach(sources::add);
        }
        Compiler.Result r = Compiler.compile(new Compiler.Options(sources, List.of(Path.of("lib/include"), Path.of("lib/snes")),
                List.of(), "test_main", tmp.resolve("tick" + calls.length() + ".sfc")).withBackend(Compiler.Backend.MIR));
        Map<String, long[]> timing = r.timing();
        long[] t = timing.get("_test_main");
        assertTrue(t != null && t[1] >= 0, "a bounded test_main");
        return t;
    }

    @Test
    void theCallsCostNothingOnTheSnes() throws Exception {
        long[] without = tick("");
        long[] with = tick("""
                    sdevkit_dbg_toggle("frozen", &frozen);
                    sdevkit_dbg_range("speed", &speed, -4, 4);
                    sdevkit_dbg_color("colour", &colour);
                    sdevkit_dbg_action("reset", &pressed);
                    sdevkit_dbg_text("goal", "the exit");
                    sdevkit_dbg_value("sum", &sum);
                    sdevkit_music_play(1);
                    sdevkit_music_stop();
                    for (i = 0; i < 4; i++) {
                        sdevkit_dbg_box(x[i], (int16_t)(x[i] + 8), 16, 16, i);
                    }
                """);
        assertArrayEquals(without, with, "test_main's best and worst case in CPU cycles, without and with the calls");
    }
}
