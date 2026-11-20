package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same work, written the ways a programmer would write it, should cost the same
 * (docs/current-compiler-flaws.md, T1). Each file in compiler/src/test/resources/spelling is a
 * family: its functions without parameters (any result type, void too), except {@code setup}
 * and {@code check}, are its members, and all do the same thing. The header says:
 * <pre>
 *   family: sum-bytes
 *   tolerance: 10
 *   gap: c_i16_lt100 R1 the range analysis loses the lower bound of a signed counter
 * </pre>
 * For every family the test checks:
 * <ol>
 * <li>All members do the same, on the PC and on the SNES: a generated {@code test_main} runs, for
 *     each member, {@code setup()} (if the family has one), the member, and {@code check()} (if
 *     it has one), and stores the member's result and the check into global arrays. The PC and the
 *     SNES must agree on all globals, every member must give the same result and the same check,
 *     and the run must lie within the static timing bounds.</li>
 * <li>A member without a {@code gap:} line costs at most {@code tolerance} percent more than the
 *     family's cheapest member. The cost is the CPU cycles of one call in dampf's emulator, with a
 *     generated entry {@code spelling_entry} that calls the member as the ROM's entry, on the
 *     globals' initial values ({@code setup} does not run: a family whose cost depends on the data
 *     gives it in the definitions). The static worst case would not do: for a loop whose bound is
 *     a variable it assumes the largest bound the type allows.</li>
 * <li>A member with a {@code gap:} line costs more than that. The line names the step of the plan
 *     expected to close it and the cause; when a change to dampf closes a gap, this test fails
 *     until the line is removed, so the list only shrinks and always says what is open.</li>
 * </ol>
 * The costs are written to compiler/build/reports/spelling.txt, for the measurements of the
 * optimizations log.
 */
class SpellingTest {
    private static final Pattern FAMILY = Pattern.compile("family: (\\S+)");
    private static final Pattern TOLERANCE = Pattern.compile("tolerance: (\\d+)");
    private static final Pattern GAP = Pattern.compile("gap: (\\w+) (\\S+) (.+)");
    /** A function definition without parameters at the start of a line: its result type and name. */
    private static final Pattern MEMBER = Pattern.compile("(?m)^((?:const\\s+)?\\w+)\\s+(\\w+)\\s*\\(\\s*void\\s*\\)\\s*(\\{|$)");
    private static final Path REPORT = Path.of("compiler/build/reports/spelling.txt");

    @TempDir
    Path tmp;

    record Member(String name, boolean returns) {}

    record Family(String name, int tolerance, Map<String, String> gaps, List<Member> members, boolean setup,
                  boolean check, String text) {}

    @TestFactory
    Stream<DynamicTest> everySpellingCostsTheSame() throws IOException, URISyntaxException {
        List<Path> files = files();
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, "# CPU cycles of one call of each member of each spelling family (SpellingTest)\n");
        return files.stream().map(src -> DynamicTest.dynamicTest(src.getFileName().toString(),
                () -> assertTimeoutPreemptively(Duration.ofMinutes(5), () -> family(src))));
    }

    private void family(Path src) throws Exception {
        Family fam = parse(src);
        Path work = Files.createDirectories(tmp.resolve(fam.name()));
        sameResults(fam, work);
        Map<String, Long> cost = new LinkedHashMap<>();
        EmulatorRunner emulator = new EmulatorRunner();
        for (Member m : fam.members()) {
            Path file = work.resolve(m.name() + ".c");
            Files.writeString(file, fam.text() + "\n" + (m.returns()
                    ? "int32_t spelling_out;\nvoid spelling_entry(void) { spelling_out = (int32_t)" + m.name() + "(); }\n"
                    : "void spelling_entry(void) { " + m.name() + "(); }\n"));
            Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(file), List.of(), List.of(), "spelling_entry",
                    work.resolve(m.name() + ".sfc")).withBackend(Compiler.Backend.MIR));
            cost.put(m.name(), emulator.cyclesOf(r.rom(), "_spelling_entry"));
        }
        long cheapest = cost.values().stream().mapToLong(Long::longValue).min().orElseThrow();
        StringBuilder report = new StringBuilder("\n" + fam.name() + " (tolerance " + fam.tolerance() + " %)\n");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Long> e : cost.entrySet()) {
            long c = e.getValue();
            boolean within = c * 100 <= cheapest * (100L + fam.tolerance());
            String gap = fam.gaps().get(e.getKey());
            report.append(String.format("%10d  %+5d %%  %-24s %s%n", c, Math.round(100.0 * (c - cheapest) / cheapest),
                    e.getKey(), gap == null ? "" : "gap " + gap));
            if (gap == null && !within) {
                wrong.add(e.getKey() + " costs " + c + ", more than " + fam.tolerance() + " % above the cheapest, "
                        + cheapest + " (a new gap: fix it, or add a 'gap:' line with its cause)");
            } else if (gap != null && within) {
                wrong.add(e.getKey() + " costs " + c + ", within " + fam.tolerance() + " % of the cheapest, " + cheapest
                        + ": the gap '" + gap + "' is closed, remove its line");
            }
        }
        synchronized (SpellingTest.class) {
            Files.writeString(REPORT, report, java.nio.file.StandardOpenOption.APPEND);
        }
        assertTrue(wrong.isEmpty(), fam.name() + ":\n  " + String.join("\n  ", wrong) + "\n" + report);
    }

    /** Every member does the same thing, on the PC and on the SNES, within the static timing bounds. */
    private void sameResults(Family fam, Path work) throws Exception {
        int n = fam.members().size();
        StringBuilder main = new StringBuilder("\nint32_t spelling_result[" + n + "];\nint32_t spelling_check[" + n + "];\n"
                + "void test_main(void)\n{\n");
        for (int k = 0; k < n; k++) {
            Member m = fam.members().get(k);
            main.append(fam.setup() ? "    setup();\n" : "")
                    .append(m.returns() ? "    spelling_result[" + k + "] = (int32_t)" + m.name() + "();\n" : "    " + m.name() + "();\n")
                    .append(fam.check() ? "    spelling_check[" + k + "] = (int32_t)check();\n" : "");
        }
        main.append("}\n");
        Path file = work.resolve(fam.name() + ".c");
        Files.writeString(file, fam.text() + main);
        Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(file), List.of(), List.of(), "test_main",
                work.resolve(fam.name() + ".sfc")).withBackend(Compiler.Backend.MIR));
        List<Observed> globals = Observed.of(r.program().globals());
        Map<String, Long> pc = PcReference.wrap(PcReference.run(file, "test_main", globals, work), globals);
        EmulatorRunner emulator = new EmulatorRunner();
        assertEquals(pc, emulator.run(r.rom(), globals).values(), fam.name() + ": the PC and the SNES differ");
        Timing.check(r, emulator, fam.name());
        for (String array : List.of("spelling_result", "spelling_check")) {
            Long first = pc.get(array + "[0]");
            for (int k = 1; k < n; k++) {
                assertEquals(first, pc.get(array + "[" + k + "]"), fam.name() + ": " + fam.members().get(k).name()
                        + " does not do what " + fam.members().getFirst().name() + " does (" + array + ")");
            }
        }
    }

    static Family parse(Path src) throws IOException {
        String text = Files.readString(src);
        String header = text.substring(0, Math.max(0, text.indexOf("*/")));
        Matcher family = FAMILY.matcher(header), tolerance = TOLERANCE.matcher(header);
        assertTrue(family.find() && tolerance.find(), src + ": no 'family:' and 'tolerance:' line in the first comment");
        assertEquals(src.getFileName().toString().replace(".c", ""), family.group(1), src + ": the file is named after its family");
        Map<String, String> gaps = new LinkedHashMap<>();
        Matcher gap = GAP.matcher(header);
        while (gap.find()) {
            gaps.put(gap.group(1), gap.group(2) + " " + gap.group(3).replaceAll("\\s*\\*?\\s*$", ""));
        }
        List<Member> members = new ArrayList<>();
        boolean setup = false, check = false;
        Matcher m = MEMBER.matcher(text);
        while (m.find()) {
            String type = m.group(1), name = m.group(2);
            if (name.equals("setup")) {
                setup = true;
            } else if (name.equals("check")) {
                check = true;
            } else {
                members.add(new Member(name, !type.equals("void")));
            }
        }
        assertTrue(members.size() >= 2, src + ": a family needs at least two members");
        for (String g : gaps.keySet()) {
            assertTrue(members.stream().anyMatch(x -> x.name().equals(g)), src + ": the gap names no member: " + g);
        }
        return new Family(family.group(1), Integer.parseInt(tolerance.group(1)), gaps, members, setup, check, text);
    }

    static List<Path> files() throws URISyntaxException, IOException {
        Path dir = Path.of(SpellingTest.class.getResource("/spelling").toURI());
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".c")).sorted().toList();
        }
    }
}
