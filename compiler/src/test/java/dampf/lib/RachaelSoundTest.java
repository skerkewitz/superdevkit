package dampf.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dampf.util.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rachael's DSP (lib/rachael/dsp.c, docs/sound.md): what the BRR Lab plays is what she plays. A
 * small program on Rachael, built by gcc, puts BRR samples into her audio RAM, keys a voice on
 * at a pitch and a volume and prints her output; the Lab's {@code voicePlay} (tools/brr_lab.html),
 * run under Node, must give the same samples, with the main volume of the kit's driver ($7F)
 * applied. Skipped without Node. And she counts the DSP writes she does not play.
 */
class RachaelSoundTest {
    private static final Path GOLDEN = Path.of("cli/src/test/resources/superdevkit/sfx/golden");

    @TempDir
    Path tmp;

    private Path build(String name, String program) throws IOException, InterruptedException {
        Path c = tmp.resolve(name + ".c");
        Path exe = tmp.resolve(name);
        Files.writeString(c, program);
        Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("cc", "-std=c99", "-Wall", "-Wextra",
                "-Wno-unused-parameter", "-I", "lib/include", "-I", "lib/rachael", c.toString(), "lib/rachael/dsp.c",
                "-o", exe.toString()));
        return exe;
    }

    /** The cases: a BRR file of the converter's tests, a pitch, a volume. */
    private record Case(String brr, int pitch, int volume) {
    }

    private static final List<Case> CASES = List.of(
            new Case("pcm16_44k.brr", 0x1000, 100), new Case("pcm16_44k.brr", 0x0800, 127),
            new Case("float_48k.brr", 0x2A3D, 64), new Case("pcm8_8k_up.brr", 0x3FFF, 127),
            new Case("pcm24_22k_stereo.brr", 0x0123, 90));

    @Test
    void rachaelPlaysWhatTheLabPlays() throws Exception {
        assumeTrue(hasNode(), "no node: the Lab's voice is not run");
        Path exe = build("voice", """
                #include <stdio.h>
                #include <stdlib.h>
                #include "rachael.h"
                /* voice BRR PITCH VOLUME: the samples of the left channel, one a line, until the voice ends */
                int main(int argc, char **argv)
                {
                    FILE *f = fopen(argv[1], "rb");
                    unsigned pitch = (unsigned)strtol(argv[2], NULL, 0), volume = (unsigned)atoi(argv[3]);
                    int16_t s[2 * 64];
                    size_t n;
                    int i, quiet = 0;
                    sdevkit__sound_reset();
                    n = fread(sdevkit__sound.aram + 0x0400, 1, 0xF000, f);
                    fclose(f);
                    (void)n;
                    sdevkit__sound.aram[0x0300] = 0x00; sdevkit__sound.aram[0x0301] = 0x04;
                    sdevkit__dsp_write(0x6C, 0x20);
                    sdevkit__dsp_write(0x0C, 0x7F); sdevkit__dsp_write(0x1C, 0x7F);
                    sdevkit__dsp_write(0x5D, 0x03);
                    sdevkit__dsp_write(0x04, 0x00);
                    sdevkit__dsp_write(0x02, pitch & 0xFF); sdevkit__dsp_write(0x03, pitch >> 8);
                    sdevkit__dsp_write(0x00, volume); sdevkit__dsp_write(0x01, volume);
                    sdevkit__dsp_write(0x05, 0x00); sdevkit__dsp_write(0x07, 0x7F);
                    sdevkit__dsp_write(0x4C, 0x01);
                    while (quiet < 2000) {                    /* the voice's end, and some silence */
                        sdevkit__dsp_render(s, 64);
                        for (i = 0; i < 64; i++) {
                            printf("%d\\n", s[2 * i]);
                            quiet = s[2 * i] == 0 ? quiet + 1 : 0;
                        }
                    }
                    printf("unheard %u\\n", sdevkit__sound.unheard);
                    return 0;
                }
                """);
        Matcher m = Pattern.compile("<script id=\"core\">(.*?)</script>", Pattern.DOTALL).matcher(Files.readString(Path.of("tools/brr_lab.html")));
        assertTrue(m.find(), "the Lab's script \"core\"");
        Files.writeString(tmp.resolve("core.js"), m.group(1));
        for (Case c : CASES) {
            Path brr = GOLDEN.resolve(c.brr());
            String rachael = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString(), brr.toAbsolutePath().toString(),
                    Integer.toString(c.pitch()), Integer.toString(c.volume())));
            Files.writeString(tmp.resolve("run.js"), """
                    const fs = require('fs'); const core = require('./core.js');
                    const y = core.decodeBrr(new Uint8Array(fs.readFileSync(process.argv[2])));
                    const out = core.voicePlay(y, +process.argv[3], +process.argv[4]);
                    console.log(Array.from(out, v => (v * 127) >> 7).join('\\n'));
                    """);
            String lab = Processes.check(tmp, Duration.ofMinutes(1), List.of("node", tmp.resolve("run.js").toString(),
                    brr.toAbsolutePath().toString(), Integer.toString(c.pitch()), Integer.toString(c.volume())));
            List<String> r = new ArrayList<>(List.of(rachael.strip().split("\n")));
            assertEquals("unheard 0", r.remove(r.size() - 1), c + ": Rachael counted a write she does not play");
            List<String> l = List.of(lab.strip().split("\n"));
            assertTrue(l.size() > 100, c + ": the Lab played " + l.size() + " samples");
            for (int i = 0; i < l.size(); i++) {
                assertEquals(l.get(i), r.get(i), c + ": sample " + i + " of " + l.size());
            }
            for (int i = l.size(); i < r.size(); i++) {
                assertEquals("0", r.get(i), c + ": Rachael plays on after the Lab's end, sample " + i);
            }
        }
    }

    @Test
    void dspWritesRachaelDoesNotPlayAreCounted() throws Exception {
        Path exe = build("unheard", """
                #include <stdio.h>
                #include "rachael.h"
                static void show(const char *what) { printf("%s %u\\n", what, sdevkit__sound.unheard); }
                int main(void)
                {
                    sdevkit__sound_reset();
                    sdevkit__dsp_write(0x6C, 0x20);   /* what the kit's driver writes */
                    sdevkit__dsp_write(0x2C, 0x00);
                    sdevkit__dsp_write(0x4D, 0x00);
                    sdevkit__dsp_write(0x15, 0x00);
                    sdevkit__dsp_write(0x17, 0x7F);
                    sdevkit__dsp_write(0x5C, 0x01);   /* key off: played */
                    show("played");
                    sdevkit__dsp_write(0x4D, 0x01);   /* echo on voice 0 */
                    show("echo");
                    sdevkit__dsp_write(0x25, 0x8F);   /* ADSR on voice 2 */
                    show("adsr");
                    sdevkit__dsp_write(0x37, 0xC0);   /* GAIN, a bent line, on voice 3 */
                    show("gain");
                    sdevkit__dsp_write(0x6C, 0x00);   /* the echo writes its buffer */
                    show("flg");
                    printf("first %02X %02X\\n", sdevkit__sound.unheard_reg, sdevkit__sound.unheard_value);
                    return 0;
                }
                """);
        String out = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString()));
        assertEquals("played 0\necho 1\nadsr 2\ngain 3\nflg 4\nfirst 4D 01", out.strip());
    }

    private static boolean hasNode() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
