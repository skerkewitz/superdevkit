package dampf.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The table of features in lib/SNES_HARDWARE.md says what the kit has: a feature built on the SNES
 * and on Rachael names how a game reaches it, each a name of the public headers (a function, which
 * lib/snes and lib/rachael must both define, a macro, a variable), or a field of the register
 * shadow ({@code sdevkit_ppu.mosaic}) that sdevkit/shadow.h declares. So a row cannot say "built" for
 * something a platform lacks or the API does not have.
 */
class SnesHardwareGuideTest {
    private static final Path GUIDE = Path.of("lib/SNES_HARDWARE.md");
    private static final Pattern ROW = Pattern.compile("^\\| ([^|]+) \\| ([^|]+) \\| ([^|]+) \\| ([^|]*) \\|$", Pattern.MULTILINE);
    private static final Pattern NAME = Pattern.compile("`(\\w+)(?:\\.(\\w+)|\\[\\w*\\])*`");

    @Test
    void everyFeatureBuiltOnBothHasItsFunctionsOnBoth() throws Exception {
        String guide = Files.readString(GUIDE);
        int from = guide.indexOf("## What the kit has"), to = guide.indexOf("\n## ", from + 1);
        assertTrue(from >= 0, GUIDE + " has no section \"What the kit has\"");
        Set<String> api = LibApiTest.consoleApi();
        Set<String> names = LibNamesTest.publicNames();
        StringBuilder headers = new StringBuilder();
        for (String h : List.of("snes.h", "irq.h", "shadow.h", "sprites.h", "queue.h", "input.h", "easy.h")) {
            headers.append(Files.readString(Path.of("lib/include/sdevkit").resolve(h)));
        }
        String header = headers.toString();
        Set<String> snes = LibApiTest.defined(Path.of("lib/snes")), rachael = LibApiTest.defined(Path.of("lib/rachael"));
        List<String> wrong = new ArrayList<>();
        int built = 0;
        Matcher row = ROW.matcher(guide.substring(from, to));
        while (row.find()) {
            String feature = row.group(1), onSnes = row.group(2), onRachael = row.group(3);
            if (feature.equals("Feature") || feature.startsWith("---")) {
                continue;
            }
            if (!onSnes.startsWith("built") || !onRachael.startsWith("built")) {
                continue;
            }
            built++;
            Matcher n = NAME.matcher(row.group(4));
            boolean any = false;
            while (n.find()) {
                any = true;
                String f = n.group(1), field = n.group(2);
                if (!names.contains(f)) {
                    wrong.add(feature + ": " + f + " is not a name of the public headers");
                } else if (field != null && !Pattern.compile("\\b" + field + "\\b").matcher(header).find()) {
                    wrong.add(feature + ": " + f + " has no field " + field);
                } else if (api.contains(f) && (!snes.contains(f) || !rachael.contains(f))) {
                    wrong.add(feature + ": " + f + " is not defined in " + (!snes.contains(f) ? "lib/snes" : "lib/rachael"));
                }
            }
            if (!any) {
                wrong.add(feature + ": built on both, but names nothing a game reaches it with");
            }
        }
        assertFalse(built == 0, "no feature built on both in " + GUIDE);
        assertEquals(List.of(), wrong, "the table of features in " + GUIDE);
    }
}
