package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The speed rules of the C style guide (lib/C_STYLE.md) are measurements: each file in
 * compiler/src/test/resources/style is one rule, a pair of functions that compute the same thing two ways,
 * with the relation it claims in its first comment:
 * <pre>
 *   rule: slow-u16-counter-words
 *   claim: fill_u16_counter slower fill_u8_counter     (slower, faster, or same: within 5 %)
 * </pre>
 * Each file is built with dampf; the claim is checked on the worst cases of the timing report, the
 * program runs on the PC and in dampf's emulator with the same results (so a fast form that
 * changes the meaning cannot get in), and the measured cycles lie within the static bounds. The
 * table of measurements in the guide must be what this test measures: with STYLE_GUIDE=update in
 * the environment it is written into the guide, else a difference fails. Every rule must be named
 * in the guide. A claim that no longer holds fails the build: the rule changes or goes in the same
 * commit as the change to dampf that broke it.
 */
class StyleGuideTest {
    private static final Path GUIDE = Path.of("lib/C_STYLE.md");
    private static final String BEGIN = "<!-- measurements: written by StyleGuideTest -->";
    private static final String END = "<!-- end of measurements -->";
    private static final Pattern RULE = Pattern.compile("rule: (\\S+)");
    private static final Pattern CLAIM = Pattern.compile("claim: (\\w+) (slower|faster|same) (\\w+)");

    @TempDir
    Path tmp;

    record Row(String rule, String a, long costA, String relation, String b, long costB) {
        String line() {
            return "| `" + rule + "` | `" + a + "` | " + String.format("%,d", costA) + " | " + relation + " | `" + b
                    + "` | " + String.format("%,d", costB) + " |";
        }
    }

    @Test
    void everySpeedRuleHoldsAndTheGuideShowsItsNumbers() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            List<Row> rows = new ArrayList<>();
            List<String> broken = new ArrayList<>();
            for (Path src : files()) {
                String text = Files.readString(src);
                Matcher rule = RULE.matcher(text), claim = CLAIM.matcher(text);
                assertTrue(rule.find() && claim.find(), src + ": no 'rule:' and 'claim:' line");
                String name = src.getFileName().toString().replace(".c", "");
                assertEquals(name, rule.group(1), src + ": the file is named after its rule");
                Row row = measure(src, rule.group(1), claim.group(1), claim.group(2), claim.group(3));
                rows.add(row);
                if (!holds(row)) {
                    broken.add(row.rule() + ": " + row.a() + " " + row.costA() + " is not " + row.relation() + " "
                            + row.b() + " " + row.costB());
                }
            }
            assertTrue(broken.isEmpty(), "rules of the style guide that no longer hold (change the rule in "
                    + GUIDE + " and its file in compiler/src/test/resources/style): " + broken);
            String guide = Files.readString(GUIDE);
            for (Row r : rows) {
                assertTrue(guide.contains("**`" + r.rule() + "`**"), GUIDE + " has no rule **`" + r.rule() + "`**");
            }
            int from = guide.indexOf(BEGIN), to = guide.indexOf(END);
            assertTrue(from >= 0 && to > from, GUIDE + " has no block " + BEGIN + " ... " + END);
            StringBuilder table = new StringBuilder("\n| Rule | This | CPU cycles | is | than this | CPU cycles |\n"
                    + "| --- | --- | ---: | --- | --- | ---: |\n");
            rows.forEach(r -> table.append(r.line()).append('\n'));
            String old = guide.substring(from + BEGIN.length(), to);
            String oldTable = old.substring(old.indexOf('\n', 1) + 1);   // without the line with the date
            if ("update".equals(System.getenv("STYLE_GUIDE"))) {
                String block = "\nMeasured " + LocalDate.now() + " (worst case of the timing report, code generator "
                        + "from MIR).\n" + table;
                Files.writeString(GUIDE, guide.substring(0, from + BEGIN.length()) + block + guide.substring(to));
            } else {
                assertEquals(table.toString(), oldTable, "the measurements in " + GUIDE
                        + " are not today's: run STYLE_GUIDE=update ./gradlew test --tests dampf.exec.StyleGuideTest");
            }
        });
    }

    private static boolean holds(Row r) {
        return switch (r.relation()) {
            case "slower" -> r.costA() > r.costB();
            case "faster" -> r.costA() < r.costB();
            default -> Math.abs(r.costA() - r.costB()) * 20 <= Math.max(r.costA(), r.costB());   // within 5 %
        };
    }

    private Row measure(Path src, String rule, String a, String relation, String b) throws Exception {
        Path work = Files.createDirectories(tmp.resolve(rule));
        Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                work.resolve(rule + ".sfc")).withBackend(Compiler.Backend.MIR));
        long[] ta = r.timing().get("_" + a), tb = r.timing().get("_" + b);
        assertTrue(ta != null && tb != null, src + ": no timing for " + a + " or " + b);
        assertTrue(ta[1] >= 0 && tb[1] >= 0, src + ": " + a + " or " + b + " has no worst case");
        List<Observed> globals = Observed.of(r.program().globals());
        Map<String, Long> pc = PcReference.wrap(PcReference.run(src, "test_main", globals, work), globals);
        EmulatorRunner emulator = new EmulatorRunner();
        assertEquals(pc, emulator.run(r.rom(), globals).values(), src + ": the PC and the SNES differ");
        Timing.check(r, emulator, src.getFileName().toString());
        return new Row(rule, a, ta[1], relation, b, tb[1]);
    }

    private static List<Path> files() throws URISyntaxException, java.io.IOException {
        Path dir = Path.of(StyleGuideTest.class.getResource("/style").toURI());
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".c")).sorted().toList();
        }
    }
}
