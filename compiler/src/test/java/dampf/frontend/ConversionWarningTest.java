package dampf.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.util.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * dampf warns about a constant converted to a type that cannot hold it where gcc on x86-64 warns
 * without any warning option ({@code -Woverflow}, on by default), with the same values, and
 * nowhere else (docs/subset.md, "Warnings").
 */
class ConversionWarningTest {
    private static final Path CASES = Path.of("compiler/src/test/resources/warnings/overflow.c");

    /** gcc's conversion warning, with ASCII quotes or the typographic ones of a UTF-8 locale. */
    private static final Pattern GCC = Pattern.compile("overflow\\.c:(\\d+):(\\d+): warning: (unsigned conversion|conversion|overflow in conversion)"
            + " from .* changes value from ['‘](-?\\d+)['’] to ['‘](-?\\d+)['’] \\[-Woverflow\\]");
    private static final Pattern DAMPF = Pattern.compile("overflow\\.c:(\\d+):(\\d+): warning: (unsigned conversion|conversion|overflow in conversion)"
            + " from '\\w+' to '\\w+' changes value from '(-?\\d+)' to '(-?\\d+)' \\[-Woverflow\\]");
    private static final Pattern GCC_EXPRESSION_OVERFLOW = Pattern.compile("overflow\\.c:(\\d+):\\d+: warning: integer overflow in expression");

    @TempDir
    Path tmp;

    @Test
    void dampfWarnsWhereGccWarns() throws Exception {
        // -ftrack-macro-expansion=0: a warning in a macro's expansion is placed where the macro is used, as dampf places it
        Processes.Result gcc = Processes.run(null, Duration.ofMinutes(1),
                List.of("gcc", "-std=c99", "-fsyntax-only", "-ftrack-macro-expansion=0", CASES.toString()));
        assertEquals(0, gcc.exit(), gcc.err());
        List<String> warnings = new ArrayList<>();
        Compiler.frontEnd(new Compiler.Options(List.of(CASES), List.of(), List.of(), "conversions", tmp.resolve("w.sfc")), warnings);

        List<String> lines = Files.readAllLines(CASES);
        Set<Integer> gccOnly = new TreeSet<>();
        Set<Integer> expressionOverflow = new TreeSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("/* gcc only */")) {
                gccOnly.add(i + 1);
            }
            if (lines.get(i).contains("/* gcc warns about the overflow only */")) {
                expressionOverflow.add(i + 1);
            }
        }

        Set<String> expected = new TreeSet<>();
        Set<Integer> gccOnlySeen = new TreeSet<>();
        Set<Integer> expressionOverflowSeen = new TreeSet<>();
        for (String line : gcc.err().split("\n")) {
            Matcher m = GCC.matcher(line);
            if (m.find()) {
                int at = Integer.parseInt(m.group(1));
                if (gccOnly.contains(at)) {
                    gccOnlySeen.add(at);
                } else {
                    expected.add(key(m));
                }
            }
            Matcher o = GCC_EXPRESSION_OVERFLOW.matcher(line);
            if (o.find()) {
                expressionOverflowSeen.add(Integer.parseInt(o.group(1)));
            }
        }
        Set<String> actual = new TreeSet<>();
        for (String w : warnings) {
            Matcher m = DAMPF.matcher(w);
            assertTrue(m.find(), "not a conversion warning in gcc's form: " + w);
            actual.add(key(m));
        }
        assertTrue(expected.size() > 40, "gcc gave only " + expected.size() + " warnings:\n" + gcc.err());
        assertEquals(String.join("\n", expected), String.join("\n", actual), "where gcc warns (first) and where dampf does");
        // the lines the file names as differences still are ones
        assertEquals(gccOnly, gccOnlySeen, "the lines marked 'gcc only' where gcc warns");
        assertEquals(expressionOverflow, expressionOverflowSeen, "the lines where gcc warns about an overflow in the expression");
    }

    /** line:column, the kind of conversion and the values, from a warning of gcc or dampf. */
    private static String key(Matcher m) {
        return String.format("%4d:%-3s %s %s -> %s", Integer.parseInt(m.group(1)), m.group(2), m.group(3), m.group(4), m.group(5));
    }

    @Test
    void theWarningDoesNotStopTheBuild() throws Exception {
        Path f = tmp.resolve("cat.c");
        Files.writeString(f, """
                #include <stdint.h>
                uint8_t wait[] = { 420 };
                uint8_t out;
                void test_main(void)
                {
                    out = wait[0];
                }
                """);
        Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(f), List.of(), List.of(), "test_main", tmp.resolve("cat.sfc")));
        assertEquals(List.of(f + ":2:20: warning: unsigned conversion from 'int32_t' to 'uint8_t' changes value from '420' to '164'"
                + " [-Woverflow]"), r.warnings());
        assertTrue(Files.isRegularFile(r.rom().rom()));
    }
}
