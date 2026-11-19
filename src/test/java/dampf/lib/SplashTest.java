package dampf.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dampf.util.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The splash screen's course (lib/splash/splash.h), the same code on every platform: without a
 * button it fades in (31 frames), stays three seconds (180) and fades out (31); a button pressed
 * while it fades in or stays ends it once it has faded in, without the fade out. A small program on
 * Rachael, built by gcc, gives the splash a frame function that presses A in one frame and counts
 * the frames and the brightness of the last one.
 */
class SplashTest {
    @TempDir
    Path tmp;

    @Test
    void aButtonEndsTheSplashOnceItHasFadedIn() throws Exception {
        String program = """
                #include <stdio.h>
                #include "rachael.h"
                #include "sdevkit/loop.h"
                static long frames, press_at;
                static uint8_t last_brightness;
                static uint16_t sdevkit__splash_frame(uint8_t brightness)
                {
                    frames++;
                    last_brightness = brightness;
                    return frames == press_at ? SDEVKIT_KEY_A : 0;
                }
                #include "splash.h"
                int main(void)
                {
                    static const long presses[] = { 0, 1, 5, 31, 32, 100, 211, 212 };
                    unsigned i;
                    for (i = 0; i < sizeof presses / sizeof presses[0]; i++) {
                        sdevkit__reset();
                        frames = 0;
                        press_at = presses[i];
                        sdevkit__splash();
                        printf("%ld:%ld:%u ", presses[i], frames, last_brightness);
                    }
                    return 0;
                }
                """;
        Path c = tmp.resolve("splash_course.c"), exe = tmp.resolve("splash_course");
        Files.writeString(c, program);
        Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("cc", "-std=c99", "-Wall", "-Wextra",
                "-Wno-unused-parameter", "-I", "lib/include", "-I", "lib/rachael", "-I", "lib/splash", c.toString(), "lib/rachael/ppu.c",
                "lib/rachael/render.c", "-o", exe.toString()));
        String out = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString()));
        // press frame : frames the splash lasted : brightness of its last frame
        assertEquals("0:242:0 1:31:15 5:31:15 31:31:15 32:32:15 100:100:15 211:211:15 212:242:0", out.strip());
    }
}
