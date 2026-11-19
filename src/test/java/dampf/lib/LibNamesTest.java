package dampf.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.util.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The names of the dev kit (ADR record 34): a game reaches only names that start with
 * {@code sdevkit_} or {@code SDEVKIT_}, and the library takes no other name from it.
 */
class LibNamesTest {

    private static final Path PUBLIC = Path.of("lib/include/sdevkit");
    private static final Path COMPAT = Path.of("lib/include/snes");
    /** The callbacks a game defines: its names, not the kit's. */
    private static final Set<String> GAME_CALLBACKS = Set.of("game_init", "game_tick", "game_vblank");

    private static final Pattern DEFINE = Pattern.compile("(?m)^\\s*#\\s*define\\s+(\\w+)");
    /** A function declaration at file scope: the name before the parameter list. */
    private static final Pattern FUNCTION = Pattern.compile("\\b(\\w+)\\s*\\([^(){};]*\\)\\s*;");
    private static final Pattern EXTERN = Pattern.compile("\\bextern\\b[^;(]*?\\b(\\w+)\\s*(?:\\[[^\\]]*\\])*\\s*;");
    private static final Pattern TYPEDEF = Pattern.compile("\\btypedef\\b[^;]*?\\b(\\w+)\\s*;");

    @TempDir
    Path tmp;

    private static String withoutComments(String c) {
        return c.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static List<Path> files(Path dir, String suffix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        }
    }

    /** Every name a public header declares or defines at file scope. */
    static Set<String> publicNames() throws IOException {
        Set<String> names = new TreeSet<>();
        for (Path h : files(PUBLIC, ".h")) {
            String c = withoutComments(Files.readString(h));
            for (Pattern p : List.of(DEFINE, EXTERN, TYPEDEF)) {
                Matcher m = p.matcher(c);
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
            Matcher m = FUNCTION.matcher(c.replaceAll("(?m)^\\s*#.*$", " "));
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    @Test
    void everyNameOfThePublicHeadersHasThePrefix() throws IOException {
        Set<String> names = publicNames();
        assertTrue(names.containsAll(List.of("sdevkit_oam_set", "sdevkit_oam_memory", "SDEVKIT_KEY_A", "SDEVKIT_RGB5", "game_tick")),
                "the header scan missed names: " + names);
        List<String> wrong = names.stream()
                .filter(n -> !n.startsWith("sdevkit_") && !n.startsWith("SDEVKIT_") && !GAME_CALLBACKS.contains(n)).toList();
        assertEquals(List.of(), wrong, "names in lib/include/sdevkit without sdevkit_ or SDEVKIT_");
        List<String> internal = names.stream().filter(n -> n.startsWith("sdevkit__") || n.startsWith("SDEVKIT__")).toList();
        assertEquals(List.of(), internal, "internal names (sdevkit__) do not belong in the public headers");
        assertTrue(names.stream().noneMatch(n -> n.startsWith("game_") && !GAME_CALLBACKS.contains(n)), "no kit name starts with game_");
    }

    @Test
    void everyExternalDefinitionOfTheLibraryHasThePrefix() throws IOException {
        List<String> wrong = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        for (String dir : List.of("lib/snes", "lib/rachael", "lib/desktop")) {
            // the frontend needs raylib's header (dev/fetch_raylib.sh), as ExamplesTest does
            if (!dir.equals("lib/desktop") || Files.exists(Path.of("build/raylib/linux/include/raylib.h"))) {
                sources.addAll(files(Path.of(dir), ".c"));
            }
        }
        for (Path c : sources) {
            Path o = tmp.resolve(c.getParent().getFileName() + "_" + c.getFileName() + ".o");
            Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("gcc", "-std=c99", "-c", "-w", "-I", "lib/include",
                    "-I", c.getParent().toString(), "-I", "build/raylib/linux/include", "-I", "lib/rachael", c.toString(), "-o", o.toString()));
            String nm = Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("nm", "--defined-only", "-g", o.toString()));
            for (String line : nm.split("\\R")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 3) {
                    String name = parts[2];
                    if (!name.startsWith("sdevkit_") && !name.equals("main")) {
                        wrong.add(c + ": " + name);
                    }
                }
            }
        }
        assertEquals(List.of(), wrong, "external names in lib/ that are not sdevkit_*, sdevkit__* or main (make them static)");
    }

    @Test
    void theCompatibilityHeadersAreWhatTheRenameScriptWrites() throws IOException {
        Processes.check(Path.of("."), Duration.ofMinutes(1),
                List.of("python3", "-I", "tools/sdevkit_rename.py", "--write-compat", tmp.toString()));
        for (String name : List.of("snes.h", "loop.h")) {
            assertEquals(Files.readString(tmp.resolve(name)), Files.readString(COMPAT.resolve(name)),
                    COMPAT.resolve(name) + " differs from python3 -I tools/sdevkit_rename.py --write-compat " + COMPAT);
        }
    }
}
