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
 * The console API of the dev kit (ADR-6F3CJX8T and ADR-6F3CK2AR): every function
 * {@code lib/include/sdevkit/snes.h} declares exists in {@code lib/snes} and in {@code lib/rachael}
 * (the desktop), built or as a stub, and has its row in the table of {@code lib/README.md}.
 */
class LibApiTest {

    private static final Path HEADER = Path.of("lib/include/sdevkit/snes.h");
    private static final Path SNES = Path.of("lib/snes");
    private static final Path RACHAEL = Path.of("lib/rachael");
    private static final Path README = Path.of("lib/README.md");

    private static final Pattern DECLARATION = Pattern.compile("\\b(\\w+)\\s*\\([^(){};]*\\)\\s*;");
    private static final Pattern DEFINITION = Pattern.compile("\\b(\\w+)\\s*\\([^(){};]*\\)\\s*\\{");
    private static final Pattern ROW = Pattern.compile("^\\| `(\\w+)` \\|", Pattern.MULTILINE);

    /** C source without comments and preprocessor lines. */
    private static String code(String c) {
        return c.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ").replaceAll("(?m)^\\s*#.*$", " ");
    }

    static Set<String> consoleApi() throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher m = DECLARATION.matcher(code(Files.readString(HEADER)));
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    @Test
    void theHeaderDeclaresTheConsoleApi() throws IOException {
        assertTrue(consoleApi().containsAll(List.of("sdevkit_sprite", "sdevkit_queue_dma", "sdevkit_dma_now", "sdevkit_commit_now", "sdevkit_dbg_vram")), consoleApi().toString());
    }

    @Test
    void everyFunctionOfTheConsoleApiExistsOnTheSnes() throws IOException {
        assertDefinesAll(SNES);
    }

    @Test
    void everyFunctionOfTheConsoleApiExistsOnTheDesktop() throws IOException {
        assertDefinesAll(RACHAEL);
    }

    /** The functions the .c files of a platform's folder define. */
    static Set<String> defined(Path platform) throws IOException {
        Set<String> defined = new TreeSet<>();
        try (Stream<Path> files = Files.list(platform)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".c")).toList()) {
                Matcher m = DEFINITION.matcher(code(Files.readString(f)));
                while (m.find()) {
                    defined.add(m.group(1));
                }
            }
        }
        return defined;
    }

    private static void assertDefinesAll(Path platform) throws IOException {
        Set<String> defined = defined(platform);
        List<String> missing = new ArrayList<>(consoleApi());
        missing.removeAll(defined);
        assertEquals(List.of(), missing, "functions of sdevkit/snes.h that " + platform + " does not define (a stub counts)");
    }

    @Test
    void everyFunctionOfTheConsoleApiHasARowInTheTable() throws IOException {
        Set<String> rows = new TreeSet<>();
        Matcher m = ROW.matcher(Files.readString(README));
        while (m.find()) {
            rows.add(m.group(1));
        }
        assertEquals(consoleApi(), rows, "the rows of the table in lib/README.md and the console API of sdevkit/snes.h");
    }
}
