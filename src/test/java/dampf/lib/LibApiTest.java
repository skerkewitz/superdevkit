package dampf.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The console API of the dev kit (ADR record 29): every function {@code lib/include/snes/snes.h}
 * declares for games exists in {@code lib/snes}, built or as a stub, and has its row in the table
 * of {@code lib/README.md}. The PC side of the header (the {@code snes_xxx()} functions after
 * "PC side") is the PC implementation's interface, not the console API, and is not checked.
 */
class LibApiTest {

    private static final Path HEADER = Path.of("lib/include/snes/snes.h");
    private static final Path SNES = Path.of("lib/snes");
    private static final Path README = Path.of("lib/README.md");

    private static final Pattern DECLARATION = Pattern.compile("\\b(\\w+)\\s*\\([^(){};]*\\)\\s*;");
    private static final Pattern DEFINITION = Pattern.compile("\\b(\\w+)\\s*\\([^(){};]*\\)\\s*\\{");
    private static final Pattern ROW = Pattern.compile("^\\| `(\\w+)` \\|", Pattern.MULTILINE);

    /** C source without comments and preprocessor lines. */
    private static String code(String c) {
        return c.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ").replaceAll("(?m)^\\s*#.*$", " ");
    }

    static Set<String> consoleApi() throws IOException {
        String h = Files.readString(HEADER);
        int pc = h.indexOf("/* --- PC side");
        assertTrue(pc > 0, HEADER + " has no \"PC side\" section");
        Set<String> names = new TreeSet<>();
        Matcher m = DECLARATION.matcher(code(h.substring(0, pc)));
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    @Test
    void theHeaderDeclaresTheConsoleApi() throws IOException {
        assertTrue(consoleApi().containsAll(List.of("setMode", "dmaCopyVram", "oamSet", "snesDmaFits")), consoleApi().toString());
    }

    @Test
    void everyFunctionOfTheConsoleApiExistsOnTheSnes() throws IOException {
        Set<String> defined = new TreeSet<>();
        try (Stream<Path> files = Files.list(SNES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".c")).toList()) {
                Matcher m = DEFINITION.matcher(code(Files.readString(f)));
                while (m.find()) {
                    defined.add(m.group(1));
                }
            }
        }
        List<String> missing = new ArrayList<>(consoleApi());
        missing.removeAll(defined);
        assertEquals(List.of(), missing, "functions of snes.h that lib/snes does not define (a stub counts)");
    }

    @Test
    void everyFunctionOfTheConsoleApiHasARowInTheTable() throws IOException {
        Set<String> rows = new TreeSet<>();
        Matcher m = ROW.matcher(Files.readString(README));
        while (m.find()) {
            rows.add(m.group(1));
        }
        assertEquals(consoleApi(), rows, "the rows of the table in lib/README.md and the console API of snes.h");
    }
}
