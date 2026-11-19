package superdevkit.sfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sound converter (docs/sound.md): the same bytes from the Java converter, from the BRR files
 * checked in and from the Lab's JavaScript under Node (skipped when there is no Node, as the
 * tests that need Mesen are); its tables are the Lab's; its checks stop the build with the line.
 *
 * <p>The WAV files are made here, with integers, in every format the converter reads. When the
 * conversion changes on purpose: {@code SFX_GOLDEN=update ./gradlew :cli:test --tests '*SfxTest'}.
 */
class SfxTest {
    private static final Path GOLDEN = Path.of("cli/src/test/resources/superdevkit/sfx/golden");
    private static final Path LAB = Path.of("tools/brr_lab.html");

    /** A WAV to make and the settings to convert it with. */
    record Case(String name, int rate, int channels, int bits, boolean isFloat, int frames,
                int toRate, double treble, double gain, boolean trim) {
    }

    static final List<Case> CASES = List.of(
            new Case("pcm16_44k", 44100, 1, 16, false, 9000, 16000, 6, 0, true),
            new Case("pcm24_22k_stereo", 22050, 2, 24, false, 5000, 11000, 9, 2.5, true),
            new Case("float_48k", 48000, 1, 32, true, 6000, 32000, 0, -3, false),
            new Case("pcm8_8k_up", 8000, 1, 8, false, 1200, 16000, 18, 12, true),
            new Case("pcm16_32k_same", 32000, 1, 16, false, 3000, 32000, 3, 0, false),
            new Case("pcm32_96k_stereo_low", 96000, 2, 32, false, 20000, 2000, 0, -18, true));

    /** A sound to convert: a square wave sweeping down, noise from a fixed LCG, an envelope, silence at both ends. */
    static byte[] makeWav(Case c) {
        int bytes = c.bits() / 8;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        long seed = 12345;
        int lead = c.frames() / 10;
        for (int i = 0; i < c.frames(); i++) {
            int env = i < lead || i >= c.frames() - lead ? 0 : Math.min(i - lead, c.frames() - lead - i);
            env = Math.min(env * 64, 65535);                          // 0..65535
            int period = 20 + i * 200 / c.frames();
            int square = (i / period) % 2 == 0 ? 20000 : -20000;      // 16-bit scale
            seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF;
            int noise = (int) (seed >> 16) % 8000 - 4000;
            for (int ch = 0; ch < c.channels(); ch++) {
                long v16 = (long) (square + (ch == 0 ? noise : -noise)) * env / 65536;   // -24000..24000
                if (c.isFloat()) {
                    int bits = Float.floatToIntBits((float) (v16 / 32768.0));
                    le(data, bits, 4);
                } else {
                    long v = switch (c.bits()) {
                        case 8 -> (v16 >> 8) + 128;
                        case 16 -> v16;
                        case 24 -> v16 * 256 + (i & 255);
                        default -> v16 * 65536 + (i * 77 & 0xFFFF);
                    };
                    le(data, v, bytes);
                }
            }
        }
        byte[] d = data.toByteArray();
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        le(f, 4 + 8 + 16 + 8 + d.length, 4);
        f.writeBytes("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        le(f, 16, 4);
        le(f, c.isFloat() ? 3 : 1, 2);
        le(f, c.channels(), 2);
        le(f, c.rate(), 4);
        le(f, (long) c.rate() * c.channels() * bytes, 4);
        le(f, c.channels() * bytes, 2);
        le(f, c.bits(), 2);
        f.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        le(f, d.length, 4);
        f.writeBytes(d);
        return f.toByteArray();
    }

    private static void le(ByteArrayOutputStream o, long v, int n) {
        for (int k = 0; k < n; k++) {
            o.write((int) (v >> (8 * k)) & 0xFF);
        }
    }

    static Conversion.Result convert(Case c) throws Wav.FormatError {
        Wav w = Wav.read(makeWav(c));
        return Conversion.convert(w, new Conversion.Settings(c.toRate(), (int) Math.round(c.treble() * 2),
                (int) Math.round(c.gain() * 2), c.trim()));
    }

    @Test
    void theConverterGivesTheBytesCheckedIn() throws Exception {
        boolean update = "update".equals(System.getenv("SFX_GOLDEN"));
        List<String> differ = new ArrayList<>();
        for (Case c : CASES) {
            byte[] brr = convert(c).brr();
            Path g = GOLDEN.resolve(c.name() + ".brr");
            if (update) {
                Files.createDirectories(GOLDEN);
                Files.write(g, brr);
            } else if (!Files.exists(g) || !java.util.Arrays.equals(Files.readAllBytes(g), brr)) {
                differ.add(c.name());
            }
        }
        assertTrue(differ.isEmpty(), "the conversion changed for " + differ
                + "; if that is meant: SFX_GOLDEN=update ./gradlew :cli:test --tests '*SfxTest'");
    }

    @Test
    void theLabsTablesAreTheConvertersOwn() throws IOException {
        String lab = Files.readString(LAB);
        assertArrayEquals(Conversion.SINC, jsArray(lab, "SINC"), "SINC");
        assertArrayEquals(Conversion.SHELF, jsArray(lab, "SHELF"), "SHELF");
        assertArrayEquals(Conversion.GAIN, jsArray(lab, "GAIN"), "GAIN");
        Matcher m = Pattern.compile("const TRIM = (\\d+);").matcher(lab);
        assertTrue(m.find(), "TRIM in the Lab");
        assertEquals(Conversion.TRIM, Integer.parseInt(m.group(1)));
    }

    private static int[] jsArray(String text, String name) {
        Matcher m = Pattern.compile("const " + name + " = \\[([^\\]]*)\\];").matcher(text);
        assertTrue(m.find(), name + " in the Lab");
        return Pattern.compile("[,\\s]+").splitAsStream(m.group(1).trim()).mapToInt(Integer::parseInt).toArray();
    }

    /** The code of the Lab's script "core", as Node runs it. */
    static String labCore() throws IOException {
        Matcher m = Pattern.compile("<script id=\"core\">(.*?)</script>", Pattern.DOTALL).matcher(Files.readString(LAB));
        assertTrue(m.find(), "the Lab's script \"core\"");
        return m.group(1);
    }

    static boolean hasNode() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void theLabGivesTheSameBytes(@TempDir Path tmp) throws Exception {
        assumeTrue(hasNode(), "no node: the Lab's conversion is not run");
        Files.writeString(tmp.resolve("core.js"), labCore());
        StringBuilder js = new StringBuilder("const fs = require('fs'); const core = require('./core.js');\n");
        js.append("const cases = [\n");
        for (Case c : CASES) {
            Files.write(tmp.resolve(c.name() + ".wav"), makeWav(c));
            js.append(String.format(java.util.Locale.ROOT,
                    "  ['%s', { rate: %d, treble: %s, gain: %s, trim: %b }],\n", c.name(), c.toRate(),
                    c.treble(), c.gain(), c.trim()));
        }
        js.append("];\nfor (const [name, s] of cases) {\n");
        js.append("  const r = core.convert(core.readWav(fs.readFileSync(__dirname + '/' + name + '.wav')), s);\n");
        js.append("  console.log(name + ' ' + r.pitch + ' ' + r.clipped + ' ' + Buffer.from(r.brr).toString('hex'));\n}\n");
        // the file as the Lab saves it, for the Java reader
        js.append("fs.writeFileSync(__dirname + '/lab.json', core.writeBank({ voices: 3, sounds: [\n");
        js.append("  { id: 'jump', priority: 2, samples: [ { file: 'a.wav', active: true, rate: 11000, treble: 4.5,");
        js.append(" gain: -1.5, trim: false, volume: 90 }, { file: 'b.wav', active: false, rate: 16000, treble: 0,");
        js.append(" gain: 0, trim: true, volume: 100 } ] },\n  { id: 'off', priority: 0, samples: [] } ] }));\n");
        Files.writeString(tmp.resolve("run.js"), js);
        Process p = new ProcessBuilder("node", tmp.resolve("run.js").toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(5, TimeUnit.MINUTES), "node took too long");
        assertEquals(0, p.exitValue(), out);
        Sfx.Bank bank = Sfx.read(tmp.resolve("lab.json"));
        assertEquals(3, bank.voices());
        assertEquals(List.of("jump", "off"), bank.sounds().stream().map(Sfx.Sound::id).toList());
        Sfx.Sample a = bank.sounds().get(0).active();
        assertEquals(new Conversion.Settings(11000, 9, -3, false), a.settings());
        assertEquals(90, a.volume());
        assertEquals(2, bank.sounds().get(0).priority());
        String[] lines = out.strip().split("\n");
        assertEquals(CASES.size(), lines.length, out);
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            Conversion.Result r = convert(c);
            String[] w = lines[i].split(" ");
            assertEquals(c.name(), w[0]);
            assertEquals(r.pitch(), Integer.parseInt(w[1]), c.name() + ": pitch");
            assertEquals(r.clipped(), Integer.parseInt(w[2]), c.name() + ": clipped");
            String want = HexFormat.of().formatHex(r.brr());
            if (!want.equals(w[3])) {
                int at = 0;
                while (at < want.length() && at < w[3].length() && want.charAt(at) == w[3].charAt(at)) {
                    at++;
                }
                fail(c.name() + ": the Lab's BRR differs from byte " + at / 2 + " on (Java " + want.length() / 2
                        + " bytes, Lab " + w[3].length() / 2 + ")");
            }
        }
    }

    @Test
    void everyFormatReadsAsTwentyFourBits() throws Exception {
        for (Case c : CASES) {
            Wav w = Wav.read(makeWav(c));
            assertEquals(c.rate(), w.rate(), c.name());
            assertEquals(c.frames(), w.mono().length, c.name());
            assertEquals(c.channels(), w.channels(), c.name());
        }
        // a 16-bit sample is shifted up by 8, 8-bit is unsigned, the channels averaged rounding down
        assertEquals(-256, Wav.sample(new byte[] {(byte) 0xFF, (byte) 0xFF}, 0, 16, false));
        assertEquals(-128 << 16, Wav.sample(new byte[] {0}, 0, 8, false));
        assertEquals(8388607, Wav.sample(le32(Float.floatToIntBits(2.0f)), 0, 32, true));
        assertEquals(-4194304, Wav.sample(le32(Float.floatToIntBits(-0.5f)), 0, 32, true));
    }

    private static byte[] le32(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    @Test
    void theDecoderReadsWhatTheEncoderWrote() throws Exception {
        Conversion.Result r = convert(CASES.get(0));
        int[] y = Brr.decode(r.brr());
        assertEquals((r.samples() + 15) / 16 * 16, y.length);
        long sig = 0;
        long err = 0;
        for (int i = 0; i < r.samples(); i++) {
            sig += (long) r.input()[i] * r.input()[i];
            err += (long) (y[i] - r.input()[i]) * (y[i] - r.input()[i]);
        }
        assertTrue(10 * Math.log10((double) sig / err) > 20, "BRR's error is too large");
    }

    // ------------------------------------------------------------------ sfx.json and the build

    private static Path project(Path dir, String json) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve("a.wav"), makeWav(CASES.get(0)));
        Files.write(dir.resolve("b.wav"), makeWav(CASES.get(3)));
        Files.writeString(dir.resolve("sfx.json"), json);
        return dir.resolve("sfx.json");
    }

    private static String failure(Path json) throws IOException {
        try {
            Sfx.build(json, "sfx", json.getParent().resolve("out"));
        } catch (Sfx.Failure e) {
            return e.getMessage();
        }
        return fail("the build went on");
    }

    @Test
    void theBuildWritesANamePerEffect(@TempDir Path tmp) throws Exception {
        Path json = project(tmp, """
                {
                  "voices": 2,
                  "sounds": [
                    { "id": "jump", "priority": 1, "samples": [
                        { "file": "a.wav", "active": true, "rate": 16000, "treble": 6, "gain": 0, "trim": true, "volume": 100 },
                        { "file": "gone.wav", "active": false, "rate": 11000 } ] },
                    { "id": "death", "priority": 3, "samples": [
                        { "file": "b.wav", "active": true, "rate": 11000, "volume": 127 } ] },
                    { "id": "hurry", "priority": 2, "samples": [] }
                  ]
                }
                """);
        Sfx.Report r = Sfx.build(json, "sfx", tmp.resolve("out"));
        String h = Files.readString(tmp.resolve("out/sfx.h"));
        assertTrue(h.contains("#define SFX_JUMP  0\n"), h);
        assertTrue(h.contains("#define SFX_DEATH 1\n"), h);
        assertTrue(h.contains("#define SFX_HURRY 2   /* disabled: no active sample */\n"), h);
        assertTrue(h.contains("#define SFX_COUNT 3\n"), h);
        assertTrue(h.contains("extern const sdevkit_sfx_bank sfx_bank;"), h);
        String c = Files.readString(tmp.resolve("out/sfx.c"));
        assertTrue(c.contains("static const uint8_t sfx_sample[3] = { 0, 1, 0xFF };"), c);
        assertTrue(c.contains("static const uint8_t sfx_priority[3] = { 1, 3, 2 };"), c);
        assertTrue(c.contains("static const uint8_t sfx_volume[3] = { 100, 127, 0 };"), c);
        assertTrue(c.contains("const sdevkit_sfx_bank sfx_bank = {\n    3, 2, 2,"), c);
        assertEquals(r.bytes(), convert(CASES.get(0)).brr().length + Conversion.convert(Wav.read(makeWav(CASES.get(3))),
                new Conversion.Settings(11000, 0, 0, true)).brr().length);
    }

    @Test
    void theBuildStopsWithTheLine(@TempDir Path tmp) throws Exception {
        String two = failure(project(tmp.resolve("two"), """
                { "sounds": [
                  { "id": "jump", "samples": [
                    { "file": "a.wav", "active": true },
                    { "file": "b.wav", "active": true } ] } ] }
                """));
        assertTrue(two.contains("sfx.json:2: effect \"jump\" has 2 active samples; at most one is active"), two);

        String missing = failure(project(tmp.resolve("missing"), """
                { "sounds": [
                  { "id": "jump", "samples": [
                    { "file": "gone.wav", "active": false },
                    { "file": "nowhere.wav", "active": true } ] } ] }
                """));
        assertTrue(missing.contains("sfx.json:4: effect \"jump\": cannot read nowhere.wav (no such file)"), missing);
        assertTrue(!missing.contains("gone.wav"), "an inactive sample is not read: " + missing);

        String same = failure(project(tmp.resolve("same"), """
                { "sounds": [
                  { "id": "jump", "samples": [] },
                  { "id": "jump", "samples": [] } ] }
                """));
        assertTrue(same.contains("sfx.json:3: two effects have the id \"jump\" (the other on line 2)"), same);

        String typo = failure(project(tmp.resolve("typo"), """
                { "sounds": [
                  { "id": "jump", "samples": [
                    { "file": "a.wav", "activ": true, "treble": 6.25 } ] } ] }
                """));
        assertTrue(typo.contains("sfx.json:3: a sample has no \"activ\""), typo);
        assertTrue(typo.contains("sfx.json:3: \"treble\" is a number of dB in steps of 0.5 from 0 to 18"), typo);

        String syntax = failure(project(tmp.resolve("syntax"), "{ \"sounds\": [\n  { \"id\": \"jump\" \"samples\": [] } ] }\n"));
        assertTrue(syntax.contains("sfx.json: line 2: ',' expected"), syntax);
    }

    @Test
    void theBuildStopsWhenTheSamplesDoNotFit(@TempDir Path tmp) throws Exception {
        StringBuilder j = new StringBuilder("{ \"sounds\": [\n");
        for (int i = 0; i < 20; i++) {   // 20 x about 3.7 KB at 32 kHz is more than the 63 KB there are
            j.append(i == 0 ? "" : ",\n").append("  { \"id\": \"s").append(i)
                    .append("\", \"samples\": [ { \"file\": \"a.wav\", \"active\": true, \"rate\": 32000, \"trim\": false, \"treble\": ")
                    .append(i % 10).append(" } ] }");
        }
        String m = failure(project(tmp, j.append(" ] }\n").toString()));
        assertTrue(m.contains("the audio RAM has 64448 next to the driver. The largest:"), m);
        assertTrue(m.contains("\n  s0 (a.wav, line 2): "), m);
    }

    @Test
    void theLengthInFramesIsRoundedUpWithAFrameToSpare() {
        // 100 blocks at pitch 0x1000: 1600 samples at 32 kHz, 3.005 frames: 4, and one more
        assertEquals(5, Conversion.frames(100, 0x1000));
        assertEquals(0x0800, Conversion.pitch(16000));
        assertEquals(0x3FFF, Conversion.pitch(200000));
    }
}
