package superdevkit.sfx;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import superdevkit.AtomicWrite;

/**
 * {@code superdevkit sfx}: a game's {@code sfx.json} to its samples as C and a header with one name
 * per effect (docs/sound.md). The format of the file:
 *
 * <pre>
 * { "voices": 2,
 *   "sounds": [
 *     { "id": "jump", "priority": 1, "samples": [
 *         { "file": "jump.wav", "active": true,
 *           "rate": 16000, "treble": 6, "gain": 0, "trim": true, "volume": 100 } ] } ] }
 * </pre>
 *
 * An effect's number is its place in the list, its name the id in capitals with SFX_ in front. Of
 * its samples at most one is active; only that one is read and converted. An effect without an
 * active sample keeps its name and plays nothing. The build stops, with the line in the file, on
 * an effect with two active samples, an active sample whose file cannot be read, two effects with
 * the same id, and active samples that do not fit into the audio RAM next to the driver.
 */
public final class Sfx {
    private Sfx() {
    }

    /** Where the samples go in the audio RAM (lib/sound/sfx_driver.h): from $0400 to the IPL ROM at $FFC0. */
    static final int SAMPLES_FROM = 0x0400;
    static final int SAMPLES_TO = 0xFFC0;
    /** The sample directory has one page: 64 entries of 4 bytes. */
    static final int MAX_SAMPLES = 64;
    /** No object of dampf is larger than a ROM bank's 32 KB. */
    static final int MAX_SAMPLE_BYTES = 32 * 1024;
    static final int OFF = 0xFF;

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> FILE_KEYS = Set.of("voices", "sounds");
    private static final Set<String> SOUND_KEYS = Set.of("id", "priority", "samples");
    private static final Set<String> SAMPLE_KEYS = Set.of("file", "active", "rate", "treble", "gain", "trim", "volume");

    /** The build cannot go on: every message names the file and the line. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        private final List<String> messages;

        Failure(List<String> messages) {
            super(String.join("\n", messages));
            this.messages = List.copyOf(messages);
        }

        public List<String> messages() {
            return messages;
        }
    }

    /** One sample of an effect, as the file has it. */
    record Sample(String file, boolean active, Conversion.Settings settings, int volume, int line) {
    }

    /** One effect. */
    record Sound(String id, int priority, List<Sample> samples, int line) {
        Sample active() {
            for (Sample s : samples) {
                if (s.active()) {
                    return s;
                }
            }
            return null;
        }
    }

    /** The whole file. */
    record Bank(int voices, List<Sound> sounds) {
    }

    /** An active sample, converted. */
    record Converted(Sound sound, Sample sample, Conversion.Result result) {
    }

    /** What {@link #build} wrote and how much of the audio RAM it uses. */
    public record Report(List<String> lines, int bytes) {
    }

    // ------------------------------------------------------------------ reading the file

    static Bank read(Path json) throws Failure, IOException {
        String where = json.toString();
        Json.Value root;
        try {
            root = Json.parse(Files.readString(json, StandardCharsets.UTF_8));
        } catch (Json.SyntaxError e) {
            throw new Failure(List.of(where + ": " + e.getMessage()));
        }
        List<String> errors = new ArrayList<>();
        Reader r = new Reader(where, errors);
        if (!root.isObject()) {
            throw new Failure(List.of(where + ":" + root.line() + ": the file is one object: { \"voices\": ..., \"sounds\": [...] }"));
        }
        Map<String, Json.Value> top = root.object();
        r.unknown(top, FILE_KEYS, "the file");
        int voices = r.integer(top, "voices", 1, 8, 1, root.line());
        List<Sound> sounds = new ArrayList<>();
        Json.Value list = top.get("sounds");
        if (list == null || !list.isArray()) {
            errors.add(where + ":" + root.line() + ": \"sounds\" is a list of the effects");
        } else {
            Map<String, Integer> seen = new HashMap<>();
            for (Json.Value v : list.array()) {
                if (!v.isObject()) {
                    errors.add(where + ":" + v.line() + ": an effect is an object { \"id\": ..., \"samples\": [...] }");
                    continue;
                }
                Map<String, Json.Value> o = v.object();
                r.unknown(o, SOUND_KEYS, "an effect");
                String id = r.string(o, "id", v.line());
                if (id != null && !ID.matcher(id).matches()) {
                    errors.add(where + ":" + o.get("id").line() + ": the id \"" + id
                            + "\" is not a name: small letters, digits and _, starting with a letter");
                }
                if (id != null && seen.containsKey(id)) {
                    errors.add(where + ":" + o.get("id").line() + ": two effects have the id \"" + id
                            + "\" (the other on line " + seen.get(id) + ")");
                } else if (id != null) {
                    seen.put(id, o.get("id").line());
                }
                int priority = r.integer(o, "priority", 0, 255, 0, v.line());
                List<Sample> samples = new ArrayList<>();
                Json.Value sl = o.get("samples");
                if (sl == null || !sl.isArray()) {
                    errors.add(where + ":" + v.line() + ": effect \"" + id + "\": \"samples\" is a list (it may be empty)");
                } else {
                    for (Json.Value sv : sl.array()) {
                        Sample s = r.sample(sv, id);
                        if (s != null) {
                            samples.add(s);
                        }
                    }
                }
                Sound sound = new Sound(id, priority, samples, v.line());
                long active = samples.stream().filter(Sample::active).count();
                if (active > 1) {
                    errors.add(where + ":" + v.line() + ": effect \"" + id + "\" has " + active
                            + " active samples; at most one is active");
                }
                sounds.add(sound);
            }
            if (sounds.size() > 255) {
                errors.add(where + ":" + list.line() + ": " + sounds.size() + " effects; at most 255");
            }
        }
        if (!errors.isEmpty()) {
            throw new Failure(errors);
        }
        return new Bank(voices, sounds);
    }

    /** Reads the members, collecting errors with the line. */
    private record Reader(String where, List<String> errors) {
        void unknown(Map<String, Json.Value> o, Set<String> keys, String what) {
            for (Map.Entry<String, Json.Value> e : o.entrySet()) {
                if (!keys.contains(e.getKey())) {
                    errors.add(where + ":" + e.getValue().line() + ": " + what + " has no \"" + e.getKey()
                            + "\" (known: " + String.join(", ", keys.stream().sorted().toList()) + ")");
                }
            }
        }

        String string(Map<String, Json.Value> o, String key, int line) {
            Json.Value v = o.get(key);
            if (v == null || !(v.v() instanceof String s)) {
                errors.add(where + ":" + (v == null ? line : v.line()) + ": \"" + key + "\" is a string");
                return null;
            }
            return s;
        }

        int integer(Map<String, Json.Value> o, String key, int min, int max, int dflt, int line) {
            Json.Value v = o.get(key);
            if (v == null) {
                return dflt;
            }
            if (v.v() instanceof BigDecimal d && d.stripTrailingZeros().scale() <= 0) {
                try {
                    int n = d.intValueExact();
                    if (n >= min && n <= max) {
                        return n;
                    }
                } catch (ArithmeticException e) {
                    // falls through to the error
                }
            }
            errors.add(where + ":" + v.line() + ": \"" + key + "\" is a whole number from " + min + " to " + max);
            return dflt;
        }

        /** A number in steps of 0.5 between min and max, as half steps. */
        int halves(Map<String, Json.Value> o, String key, int min, int max, int dflt) {
            Json.Value v = o.get(key);
            if (v == null) {
                return dflt * 2;
            }
            if (v.v() instanceof BigDecimal d) {
                BigDecimal twice = d.multiply(BigDecimal.valueOf(2));
                if (twice.stripTrailingZeros().scale() <= 0) {
                    try {
                        int n = twice.intValueExact();
                        if (n >= min * 2 && n <= max * 2) {
                            return n;
                        }
                    } catch (ArithmeticException e) {
                        // falls through to the error
                    }
                }
            }
            errors.add(where + ":" + v.line() + ": \"" + key + "\" is a number of dB in steps of 0.5 from " + min + " to " + max);
            return dflt * 2;
        }

        boolean bool(Map<String, Json.Value> o, String key, boolean dflt) {
            Json.Value v = o.get(key);
            if (v == null) {
                return dflt;
            }
            if (v.v() instanceof Boolean b) {
                return b;
            }
            errors.add(where + ":" + v.line() + ": \"" + key + "\" is true or false");
            return dflt;
        }

        Sample sample(Json.Value v, String id) {
            if (!v.isObject()) {
                errors.add(where + ":" + v.line() + ": effect \"" + id + "\": a sample is an object { \"file\": ..., ... }");
                return null;
            }
            Map<String, Json.Value> o = v.object();
            unknown(o, SAMPLE_KEYS, "a sample");
            String file = string(o, "file", v.line());
            boolean active = bool(o, "active", false);
            int rate = integer(o, "rate", 2000, 32000, 16000, v.line());
            int treble = halves(o, "treble", 0, 18, 0);
            int gain = halves(o, "gain", -18, 12, 0);
            boolean trim = bool(o, "trim", true);
            int volume = integer(o, "volume", 0, 127, 100, v.line());
            return new Sample(file, active, new Conversion.Settings(rate, treble, gain, trim), volume, v.line());
        }
    }

    // ------------------------------------------------------------------ the build

    /** Reads {@code json}, converts its active samples and writes {@code NAME.c} and {@code NAME.h} into {@code out}. */
    public static Report build(Path json, String name, Path out) throws Failure, IOException {
        if (!Pattern.matches("[a-z][a-z0-9_]*", name)) {
            throw new Failure(List.of("the name \"" + name + "\" is not a C name in small letters"));
        }
        Bank bank = read(json);
        String where = json.toString();
        Path dir = json.toAbsolutePath().getParent();
        List<String> errors = new ArrayList<>();
        List<Converted> done = new ArrayList<>();
        for (Sound s : bank.sounds()) {
            Sample a = s.active();
            if (a == null) {
                continue;
            }
            Path f = dir.resolve(a.file());
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(f);
            } catch (IOException e) {
                errors.add(where + ":" + a.line() + ": effect \"" + s.id() + "\": cannot read " + a.file()
                        + " (" + (Files.exists(f) ? e.getMessage() : "no such file") + ")");
                continue;
            }
            Wav wav;
            try {
                wav = Wav.read(bytes);
            } catch (Wav.FormatError e) {
                errors.add(where + ":" + a.line() + ": effect \"" + s.id() + "\": " + a.file() + ": " + e.getMessage());
                continue;
            }
            Conversion.Result r = Conversion.convert(wav, a.settings());
            if (r.brr().length > MAX_SAMPLE_BYTES) {
                errors.add(where + ":" + a.line() + ": effect \"" + s.id() + "\": " + a.file() + " makes "
                        + r.brr().length + " bytes of BRR; a sample has at most " + MAX_SAMPLE_BYTES
                        + " (a lower rate or a shorter sound)");
            }
            done.add(new Converted(s, a, r));
        }
        if (done.size() > MAX_SAMPLES) {
            errors.add(where + ": " + done.size() + " active samples; the sample directory has room for " + MAX_SAMPLES);
        }
        int total = done.stream().mapToInt(c -> c.result().brr().length).sum();
        int room = SAMPLES_TO - SAMPLES_FROM;
        if (total > room) {
            StringBuilder b = new StringBuilder(where + ": the active samples are " + total + " bytes; the audio RAM has "
                    + room + " next to the driver. The largest:");
            done.stream().sorted(Comparator.comparingInt((Converted c) -> -c.result().brr().length)).limit(5)
                    .forEach(c -> b.append("\n  ").append(c.sound().id()).append(" (").append(c.sample().file())
                            .append(", line ").append(c.sample().line()).append("): ").append(c.result().brr().length).append(" bytes"));
            errors.add(b.toString());
        }
        if (!errors.isEmpty()) {
            throw new Failure(errors);
        }
        Files.createDirectories(out);
        AtomicWrite.writeString(out.resolve(name + ".h"), header(bank, name, json));
        AtomicWrite.writeString(out.resolve(name + ".c"), source(bank, done, name, json));
        List<String> lines = new ArrayList<>();
        for (Converted c : done) {
            Conversion.Result r = c.result();
            lines.add(String.format(Locale.ROOT, "%-12s %-16s %6d bytes  %5.2f kHz  %3d frames%s", c.sound().id(),
                    c.sample().file(), r.brr().length, r.pitch() * 32000.0 / 4096 / 1000,
                    Conversion.frames(r.brr().length / 9, r.pitch()),
                    r.clipped() > 0 ? "  " + r.clipped() + " samples clipped by the gain" : ""));
        }
        for (Sound s : bank.sounds()) {
            if (s.active() == null) {
                lines.add(String.format(Locale.ROOT, "%-12s disabled: no active sample", s.id()));
            }
        }
        lines.add(String.format(Locale.ROOT, "%d bytes of %d in the audio RAM (%.1f %%)", total, room, 100.0 * total / room));
        return new Report(lines, total);
    }

    static String macro(String id) {
        return "SFX_" + id.toUpperCase(Locale.ROOT);
    }

    private static String header(Bank bank, String name, Path json) {
        String guard = name.toUpperCase(Locale.ROOT) + "_H";
        StringBuilder b = new StringBuilder();
        b.append("/* generated from ").append(json.getFileName()).append(" by superdevkit sfx: do not edit */\n");
        b.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        b.append("#include \"sdevkit/snes.h\"\n\n");
        int width = bank.sounds().stream().mapToInt(s -> macro(s.id()).length()).max().orElse(0);
        width = Math.max(width, "SFX_COUNT".length());
        int n = 0;
        for (Sound s : bank.sounds()) {
            b.append(String.format(Locale.ROOT, "#define %-" + width + "s %d", macro(s.id()), n++));
            if (s.active() == null) {
                b.append("   /* disabled: no active sample */");
            }
            b.append('\n');
        }
        b.append(String.format(Locale.ROOT, "#define %-" + width + "s %d\n\n", "SFX_COUNT", n));
        b.append("/* The effects for sdevkit_sfx_init(&").append(name).append("_bank), in game_init. */\n");
        b.append("extern const sdevkit_sfx_bank ").append(name).append("_bank;\n\n");
        b.append("#endif\n");
        return b.toString();
    }

    private static String source(Bank bank, List<Converted> done, String name, Path json) {
        StringBuilder b = new StringBuilder();
        b.append("/* generated from ").append(json.getFileName()).append(" by superdevkit sfx: do not edit */\n");
        b.append("#include \"").append(name).append(".h\"\n\n");
        Map<Sound, Integer> number = new IdentityHashMap<>();
        for (int i = 0; i < done.size(); i++) {
            Converted c = done.get(i);
            number.put(c.sound(), i);
            byte[] brr = c.result().brr();
            b.append("/* ").append(c.sound().id()).append(": ").append(c.sample().file()).append(", ")
                    .append(brr.length / 9).append(" blocks */\n");
            b.append("static const uint8_t ").append(name).append("_brr_").append(i).append("[").append(brr.length).append("] = {");
            for (int k = 0; k < brr.length; k++) {
                b.append(k % 18 == 0 ? "\n    " : " ").append(String.format(Locale.ROOT, "0x%02X,", brr[k] & 0xFF));
            }
            b.append("\n};\n\n");
        }
        int count = bank.sounds().size();
        List<String> sample = new ArrayList<>();
        List<String> priority = new ArrayList<>();
        List<String> volume = new ArrayList<>();
        List<String> pitch = new ArrayList<>();
        List<String> frames = new ArrayList<>();
        for (Sound s : bank.sounds()) {
            Integer k = number.get(s);
            priority.add(Integer.toString(s.priority()));
            if (k == null) {
                sample.add("0xFF");
                volume.add("0");
                pitch.add("0");
                frames.add("0");
            } else {
                Conversion.Result r = done.get(k).result();
                sample.add(Integer.toString(k));
                volume.add(Integer.toString(done.get(k).sample().volume()));
                pitch.add(String.format(Locale.ROOT, "0x%04X", r.pitch()));
                frames.add(Integer.toString(Conversion.frames(r.brr().length / 9, r.pitch())));
            }
        }
        int arrays = Math.max(1, count);   // C has no arrays of size 0
        int samples = Math.max(1, done.size());
        table(b, "uint8_t", name + "_sample", arrays, sample);
        table(b, "uint8_t", name + "_priority", arrays, priority);
        table(b, "uint8_t", name + "_volume", arrays, volume);
        table(b, "uint16_t", name + "_pitch", arrays, pitch);
        table(b, "uint16_t", name + "_frames", arrays, frames);
        List<String> ptr = new ArrayList<>();
        List<String> size = new ArrayList<>();
        for (int i = 0; i < done.size(); i++) {
            ptr.add(name + "_brr_" + i);
            size.add(Integer.toString(done.get(i).result().brr().length));
        }
        if (done.isEmpty()) {
            b.append("static const uint8_t ").append(name).append("_brr_none[1] = { 0 };\n");
            ptr.add(name + "_brr_none");
            size.add("0");
        }
        table(b, "uint8_t *const", name + "_brr", samples, ptr);
        table(b, "uint16_t", name + "_brr_size", samples, size);
        b.append("\nconst sdevkit_sfx_bank ").append(name).append("_bank = {\n");
        b.append("    ").append(count).append(", ").append(bank.voices()).append(", ").append(done.size()).append(",\n");
        b.append("    ").append(name).append("_sample, ").append(name).append("_priority, ").append(name).append("_volume, ")
                .append(name).append("_pitch, ").append(name).append("_frames,\n");
        b.append("    ").append(name).append("_brr, ").append(name).append("_brr_size\n};\n");
        return b.toString();
    }

    private static void table(StringBuilder b, String type, String name, int size, List<String> values) {
        b.append("static const ").append(type).append(' ').append(name).append('[').append(size).append("] = { ");
        b.append(values.isEmpty() ? "0" : String.join(", ", values)).append(" };\n");
    }
}
