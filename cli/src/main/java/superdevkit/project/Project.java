package superdevkit.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import superdevkit.gfx.Png2Snes;

/**
 * A project of the kit: its superdevkit.toml (ADR record 31), read and checked.
 *
 * <pre>
 * name = "catchme"              # the program: build/snes/catchme.sfc, build/linux/catchme, ...
 * superdevkit = "0.6"           # the kit's version it needs (two or three numbers), or
 * kit = "../.."                 # a superdevkit folder (working on the kit and a game at once)
 * sram = 8                      # bytes of battery-backed save RAM (sdevkit_sram), 0 to 32768
 * sources = ["src"]             # the game's C files and folders, for every target
 * include = ["src"]             # its header folders; the sources' folders if not given
 *
 * [graphics]                    # PNG files to C into build/gen (superdevkit png)
 * tiles = ["assets/maze_gfx.png", { png = "assets/boss.png", name = "boss_gfx" }]
 * tiles2 = []                   # 2 bits a pixel
 * palette = ["assets/pal_hot.png"]
 * mode7 = [{ png = "assets/festzelt.png", first = 33 }]
 *
 * [sound]
 * sfx = "assets/sfx/sfx.json"   # the sound effects (superdevkit sfx): build/gen/sfx.c and sfx.h
 * music = ["assets/music/title.ogg", { file = "assets/music/x.xm", name = "boss" }]
 *                               # the tracks: build/gen/music.h, MUSIC_TITLE ... MUSIC_COUNT
 * </pre>
 *
 * A picture's C name is its file name without ".png" unless {@code name} gives one, a track's its
 * file name without the extension. Paths are relative to the folder of superdevkit.toml.
 */
public record Project(Path dir, String name, String version, String kit, int sram, List<String> sources,
                      List<String> include, List<Graphic> graphics, String sfx, List<Track> music) {

    /** A track of music: its file and its name (MUSIC_ and the name in capitals). */
    public record Track(String file, String name) {}

    public static final String FILE = "superdevkit.toml";

    /** One picture to convert: how, the PNG, its C name, and for mode 7 its first CGRAM colour. */
    public record Graphic(Png2Snes.Mode mode, String png, String name, int first) {}

    /** A project file the kit does not take, with the reason. */
    public static final class Error extends Exception {
        public Error(String message) {
            super(message);
        }
    }

    private static final Set<String> TOP = Set.of("name", "superdevkit", "kit", "sram", "sources", "include",
            "graphics", "sound");
    private static final Set<String> GRAPHICS = Set.of("tiles", "tiles2", "palette", "mode7");
    private static final Set<String> SOUND = Set.of("sfx", "music");

    /** Reads dir/superdevkit.toml. */
    public static Project read(Path dir) throws Error, IOException {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            throw new Error("no " + FILE + " in " + dir.toAbsolutePath().normalize()
                    + " (a project of the kit has one: superdevkit init makes it)");
        }
        Map<String, Object> t;
        try {
            t = Toml.parse(FILE, Files.readString(file));
        } catch (Toml.Error e) {
            throw new Error(e.getMessage());
        }
        unknown(t, TOP, "");
        String name = string(t, "name", null);
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw new Error(FILE + ": 'name' is the program's name: letters, digits, _ and - (\"catchme\")");
        }
        String version = string(t, "superdevkit", null);
        String kit = string(t, "kit", null);
        if (version == null && kit == null) {
            throw new Error(FILE + ": 'superdevkit' (the kit's version it needs, \"" + major(dampf.Main.version())
                    + "\") or 'kit' (a superdevkit folder) is missing");
        }
        if (version != null && !version.matches("[0-9]+\\.[0-9]+(\\.[0-9]+)?")) {
            throw new Error(FILE + ": 'superdevkit' is a version of two or three numbers (\"" + major(dampf.Main.version()) + "\")");
        }
        long sram = number(t, "sram", 0);
        if (sram < 0 || sram > 32768) {
            throw new Error(FILE + ": 'sram' is the bytes of save RAM, 0 to 32768");
        }
        List<String> sources = strings(t, "sources");
        if (sources.isEmpty()) {
            throw new Error(FILE + ": 'sources' names the game's C files or folders ([\"src\"])");
        }
        List<String> include = t.containsKey("include") ? strings(t, "include") : null;
        List<Graphic> graphics = new ArrayList<>();
        Map<String, Object> g = table(t, "graphics");
        unknown(g, GRAPHICS, "[graphics] ");
        for (String key : GRAPHICS.stream().sorted().toList()) {
            Png2Snes.Mode mode = Png2Snes.Mode.valueOf(key.toUpperCase(java.util.Locale.ROOT));
            if (!g.containsKey(key)) {
                continue;
            }
            if (!(g.get(key) instanceof List<?> list)) {
                throw new Error(FILE + ": [graphics] " + key + " is an array of PNG files");
            }
            for (Object o : list) {
                graphics.add(graphic(mode, key, o));
            }
        }
        graphics.sort(java.util.Comparator.comparingInt(x -> order(x.mode())));
        Map<String, Object> s = table(t, "sound");
        unknown(s, SOUND, "[sound] ");
        String sfx = string(s, "sfx", null);
        List<Track> music = new ArrayList<>();
        if (s.containsKey("music")) {
            if (!(s.get("music") instanceof List<?> list)) {
                throw new Error(FILE + ": [sound] music is an array of files");
            }
            for (Object o : list) {
                String tfile;
                String tname = null;
                if (o instanceof String f) {
                    tfile = f;
                } else if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = (Map<String, Object>) m;
                    unknown(e, Set.of("file", "name"), "[sound] music: ");
                    tfile = string(e, "file", null);
                    tname = string(e, "name", null);
                    if (tfile == null) {
                        throw new Error(FILE + ": [sound] music: an entry without 'file'");
                    }
                } else {
                    throw new Error(FILE + ": [sound] music: a file or { file = ..., name = ... }");
                }
                if (tname == null) {
                    String f = Path.of(tfile).getFileName().toString();
                    tname = f.contains(".") ? f.substring(0, f.lastIndexOf('.')) : f;
                }
                if (!tname.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new Error(FILE + ": [sound] music: '" + tname + "' is no C name; give one: { file = \"" + tfile
                            + "\", name = \"...\" }");
                }
                for (Track other : music) {
                    if (other.name().equalsIgnoreCase(tname)) {
                        throw new Error(FILE + ": [sound] music: two tracks are called " + tname);
                    }
                    if (Path.of(other.file()).getFileName().equals(Path.of(tfile).getFileName())) {
                        throw new Error(FILE + ": [sound] music: two files are called " + Path.of(tfile).getFileName()
                                + " (they go into one folder next to the program)");
                    }
                }
                music.add(new Track(tfile, tname));
            }
            if (music.size() > 255) {
                throw new Error(FILE + ": [sound] music: at most 255 tracks");
            }
        }
        List<String> names = new ArrayList<>();
        for (Graphic x : graphics) {
            if (names.contains(x.name())) {
                throw new Error(FILE + ": two pictures are called " + x.name() + " (give one a name: { png = ..., name = ... })");
            }
            names.add(x.name());
        }
        if (sfx != null && names.contains("sfx")) {
            throw new Error(FILE + ": a picture is called sfx, as the sound effects' files are");
        }
        return new Project(dir, name, version, kit, (int) sram, List.copyOf(sources),
                include == null ? null : List.copyOf(include), List.copyOf(graphics), sfx, List.copyOf(music));
    }

    /** The order the build converts in: tiles, tiles2, palette, mode7 (as in the old build scripts). */
    private static int order(Png2Snes.Mode m) {
        return switch (m) {
            case TILES -> 0;
            case TILES2 -> 1;
            case PALETTE -> 2;
            case MODE7 -> 3;
        };
    }

    private static Graphic graphic(Png2Snes.Mode mode, String key, Object o) throws Error {
        String png;
        String name = null;
        long first = 1;
        if (o instanceof String p) {
            png = p;
        } else if (o instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) m;
            unknown(e, mode == Png2Snes.Mode.MODE7 ? Set.of("png", "name", "first") : Set.of("png", "name"),
                    "[graphics] " + key + ": ");
            png = string(e, "png", null);
            name = string(e, "name", null);
            first = number(e, "first", 1);
            if (png == null) {
                throw new Error(FILE + ": [graphics] " + key + ": an entry without 'png'");
            }
        } else {
            throw new Error(FILE + ": [graphics] " + key + ": a PNG file or { png = ..., name = ... }");
        }
        if (name == null) {
            String file = Path.of(png).getFileName().toString();
            name = file.endsWith(".png") ? file.substring(0, file.length() - 4) : file;
        }
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new Error(FILE + ": [graphics] " + key + ": '" + name + "' is no C name; give one: { png = \"" + png
                    + "\", name = \"...\" }");
        }
        if (first < 1 || first > 255) {
            throw new Error(FILE + ": [graphics] mode7: 'first' is the first CGRAM colour, 1 to 255");
        }
        return new Graphic(mode, png, name, (int) first);
    }

    /** "0.6" of "0.6.2". */
    public static String major(String version) {
        String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : version;
    }

    private static void unknown(Map<String, Object> t, Set<String> known, String where) throws Error {
        for (String k : t.keySet()) {
            if (!known.contains(k)) {
                throw new Error(FILE + ": " + where + "unknown key '" + k + "' (known: "
                        + String.join(", ", known.stream().sorted().toList()) + ")");
            }
        }
    }

    private static String string(Map<String, Object> t, String key, String otherwise) throws Error {
        Object v = t.get(key);
        if (v == null) {
            return otherwise;
        }
        if (!(v instanceof String s)) {
            throw new Error(FILE + ": '" + key + "' is a string in quotes");
        }
        return s;
    }

    private static long number(Map<String, Object> t, String key, long otherwise) throws Error {
        Object v = t.get(key);
        if (v == null) {
            return otherwise;
        }
        if (!(v instanceof Long n)) {
            throw new Error(FILE + ": '" + key + "' is a whole number");
        }
        return n;
    }

    private static List<String> strings(Map<String, Object> t, String key) throws Error {
        Object v = t.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new Error(FILE + ": '" + key + "' is an array of strings ([\"src\"])");
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s)) {
                throw new Error(FILE + ": '" + key + "' is an array of strings ([\"src\"])");
            }
            out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> t, String key) throws Error {
        Object v = t.get(key);
        if (v == null) {
            return Map.of();
        }
        if (!(v instanceof Map<?, ?>)) {
            throw new Error(FILE + ": '" + key + "' is a table: [" + key + "]");
        }
        return (Map<String, Object>) v;
    }

    /** A path of the project. */
    public Path path(String relative) {
        return dir.resolve(relative).normalize();
    }

    /** build/gen: the converted assets. */
    public Path gen() {
        return dir.resolve("build").resolve("gen");
    }

    /** The folders the game's headers are in: include, or the folders of the sources. */
    public List<Path> includeDirs() {
        List<Path> out = new ArrayList<>();
        if (include != null) {
            for (String i : include) {
                out.add(path(i));
            }
            return out;
        }
        for (String s : sources) {
            Path p = path(s);
            Path d = s.endsWith(".c") ? p.getParent() : p;   // a file names its folder, anything else is one
            if (d != null && !out.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** Whether the build writes anything into build/gen. */
    public boolean generates() {
        return !graphics.isEmpty() || sfx != null || !music.isEmpty();
    }
}
