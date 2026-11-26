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
 * The console API of the dev kit (ADR-6F3CJX8T, ADR-6FNPPCSX): every function its core
 * ({@code lib/include/sdevkit/snes.h}, and {@code irq.h} for the game's IRQ) and its optional layers ({@code shadow.h}, {@code sprites.h},
 * {@code queue.h}, {@code input.h}, {@code easy.h}) declare exists in {@code lib/snes} and in
 * {@code lib/rachael} (the desktop), built or as a stub, there or in the layers' shared code
 * ({@code lib/layers}), and has its row in the table of {@code lib/README.md}.
 */
class LibApiTest {

    private static final List<Path> HEADERS = List.of("snes.h", "irq.h", "shadow.h", "sprites.h", "queue.h", "input.h", "easy.h")
            .stream().map(h -> Path.of("lib/include/sdevkit").resolve(h)).toList();
    private static final Path LAYERS = Path.of("lib/layers");
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
        for (Path h : HEADERS) {
            Matcher m = DECLARATION.matcher(code(Files.readString(h)));
            while (m.find()) {
                if (!m.group(1).startsWith("game_")) {      // irq.h's game_irq is the game's
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    @Test
    void theHeaderDeclaresTheConsoleApi() throws IOException {
        assertTrue(consoleApi().containsAll(List.of("sdevkit_reg_write", "sdevkit_dma", "sdevkit_oam_copy", "sdevkit_sprite",
                "sdevkit_queue_dma", "sdevkit_commit", "sdevkit_dbg_vram")), consoleApi().toString());
    }

    @Test
    void everyFunctionOfTheConsoleApiExistsOnTheSnes() throws IOException {
        assertDefinesAll(SNES);
    }

    @Test
    void everyFunctionOfTheConsoleApiExistsOnTheDesktop() throws IOException {
        assertDefinesAll(RACHAEL);
    }

    /** The functions the .c files of a platform's folder define, and the layers' shared code. */
    static Set<String> defined(Path platform) throws IOException {
        Set<String> defined = new TreeSet<>(definedIn(LAYERS, ".h"));
        defined.addAll(definedIn(platform, ".c"));
        return defined;
    }

    private static Set<String> definedIn(Path folder, String suffix) throws IOException {
        Set<String> defined = new TreeSet<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path f : files.filter(p -> p.toString().endsWith(suffix)).toList()) {
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
        assertEquals(consoleApi(), rows, "the rows of the table in lib/README.md and the console API of sdevkit/*.h");
    }
}
