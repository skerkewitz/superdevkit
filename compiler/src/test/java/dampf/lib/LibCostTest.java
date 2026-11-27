package dampf.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What each function of the console API costs on the SNES: a program that calls every function of
 * sdevkit/snes.h and of the optional layers once (with zeros) is built by dampf with lib/snes, and
 * the best and worst case of
 * each function in master cycles, from the timing report, are the last column of the table in
 * lib/README.md. With LIB_COSTS=update in the environment the column is written, else a
 * difference fails, so the numbers there are dampf's of today.
 */
class LibCostTest {
    private static final List<Path> HEADERS = List.of("snes.h", "irq.h", "shadow.h", "sprites.h", "queue.h", "input.h", "easy.h")
            .stream().map(h -> Path.of("lib/include/sdevkit").resolve(h)).toList();
    private static final Path README = Path.of("lib/README.md");
    private static final Pattern PROTOTYPE = Pattern.compile("\\b(\\w+)\\s*\\(([^(){};]*)\\)\\s*;");
    private static final Pattern ROW = Pattern.compile("^(\\| `(\\w+)` \\| [^|]+ \\| [^|]+ \\|)(.*)$", Pattern.MULTILINE);

    @TempDir
    Path tmp;

    @Test
    void theTableGivesWhatEachFunctionCostsOnTheSnes() throws Exception {
        StringBuilder headers = new StringBuilder();
        for (Path h : HEADERS) {
            headers.append(Files.readString(h).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*#.*$", " "));
        }
        StringBuilder probe = new StringBuilder("#include \"sdevkit/easy.h\"\n#include \"sdevkit/irq.h\"\n\nvoid test_main(void)\n{\n");
        Matcher m = PROTOTYPE.matcher(headers);
        List<String> api = new ArrayList<>();
        while (m.find()) {
            if (m.group(1).startsWith("game_")) {          // irq.h's game_irq is the game's
                continue;
            }
            String params = m.group(2).trim();
            int n = params.equals("void") || params.isEmpty() ? 0 : params.split(",").length;
            api.add(m.group(1));
            probe.append("    ").append(m.group(1)).append('(').append(String.join(", ", java.util.Collections.nCopies(n, "0")))
                    .append(");\n");
        }
        probe.append("}\n");
        Path src = tmp.resolve("probe.c");
        Files.writeString(src, probe);
        List<Path> sources = new ArrayList<>(List.of(src));
        try (Stream<Path> s = Files.list(Path.of("lib/snes"))) {
            s.filter(p -> p.toString().endsWith(".c") && !p.getFileName().toString().equals("main.c")).sorted()
                    .forEach(sources::add);
        }
        Compiler.Result r = Compiler.compile(new Compiler.Options(sources, List.of(Path.of("lib/include"), Path.of("lib/snes")),
                List.of(), "test_main", tmp.resolve("probe.sfc")).withBackend(Compiler.Backend.MIR));
        Map<String, long[]> timing = r.timing();

        String readme = Files.readString(README);
        StringBuilder out = new StringBuilder();
        Matcher row = ROW.matcher(readme);
        int last = 0, rows = 0;
        while (row.find()) {
            String name = row.group(2);
            long[] t = timing.get("_" + name);
            assertTrue(t != null, "no timing for " + name);
            String cost = t[1] < 0 ? t[0] + " to unbounded" : t[0] == t[1] ? String.format("%,d", t[0])
                    : String.format("%,d to %,d", t[0], t[1]);
            out.append(readme, last, row.start()).append(row.group(1)).append(' ').append(cost).append(" |");
            last = row.end();
            rows++;
        }
        out.append(readme.substring(last));
        assertEquals(api.size(), rows, "a row of the table in " + README + " for each function of " + HEADERS);
        String expected = out.toString().replace("| Function | SNES | desktop |\n| --- | --- | --- |",
                "| Function | SNES | desktop | master cycles on the SNES |\n| --- | --- | --- | ---: |");
        if ("update".equals(System.getenv("LIB_COSTS"))) {
            Files.writeString(README, expected);
        } else {
            assertEquals(expected, readme, "the costs in " + README
                    + " are not today's: run LIB_COSTS=update ./gradlew test --tests dampf.lib.LibCostTest");
        }
    }
}
