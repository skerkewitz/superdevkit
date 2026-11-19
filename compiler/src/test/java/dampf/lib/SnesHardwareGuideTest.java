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
 * and on Rachael names its functions, and each is a function of the console API (sdevkit/snes.h)
 * that lib/snes and lib/rachael define. So a row cannot say "built" for something a platform lacks.
 */
class SnesHardwareGuideTest {
    private static final Path GUIDE = Path.of("lib/SNES_HARDWARE.md");
    private static final Pattern ROW = Pattern.compile("^\\| ([^|]+) \\| ([^|]+) \\| ([^|]+) \\| ([^|]*) \\|$", Pattern.MULTILINE);
    private static final Pattern NAME = Pattern.compile("`(\\w+)`");

    @Test
    void everyFeatureBuiltOnBothHasItsFunctionsOnBoth() throws Exception {
        String guide = Files.readString(GUIDE);
        int from = guide.indexOf("## What the kit has"), to = guide.indexOf("\n## ", from + 1);
        assertTrue(from >= 0, GUIDE + " has no section \"What the kit has\"");
        Set<String> api = LibApiTest.consoleApi();
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
                String f = n.group(1);
                if (!api.contains(f)) {
                    wrong.add(feature + ": " + f + " is not in sdevkit/snes.h");
                } else if (!snes.contains(f) || !rachael.contains(f)) {
                    wrong.add(feature + ": " + f + " is not defined in " + (!snes.contains(f) ? "lib/snes" : "lib/rachael"));
                }
            }
            if (!any) {
                wrong.add(feature + ": built on both, but names no function");
            }
        }
        assertFalse(built == 0, "no feature built on both in " + GUIDE);
        assertEquals(List.of(), wrong, "the table of features in " + GUIDE);
    }
}
