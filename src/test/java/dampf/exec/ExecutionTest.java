package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dampf.Compiler;
import dampf.ast.Variable;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Execution tests: every program in {@code src/test/resources/exec} runs on the PC (gcc) and on
 * the SNES (dampf, in Mesen), and all its globals must have the same values afterwards.
 * Each program's entry function is {@code test_main}.
 *
 * <p>Needs Mesen 2: {@code ./gradlew test -Pmesen=/path/to/Mesen.exe}. Skipped otherwise.
 */
class ExecutionTest {
    @TempDir
    Path tmp;

    @TestFactory
    Stream<DynamicTest> sameResultsOnPcAndSnes() throws IOException, URISyntaxException {
        String mesen = System.getProperty("dampf.mesen");
        assumeTrue(mesen != null, "set -Pmesen=<path to Mesen.exe> to run");
        MesenRunner runner = new MesenRunner(Path.of(mesen));
        Path dir = Path.of(ExecutionTest.class.getResource("/exec").toURI());
        List<Path> programs;
        try (Stream<Path> s = Files.list(dir)) {
            programs = s.filter(p -> p.toString().endsWith(".c")).sorted().toList();
        }
        return programs.stream().map(src -> DynamicTest.dynamicTest(src.getFileName().toString(), () -> {
            String name = src.getFileName().toString().replace(".c", "");
            Path work = Files.createDirectories(tmp.resolve(name));
            Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                    work.resolve(name + ".sfc")));
            List<Variable> globals = r.program().globals();
            Map<String, Long> pc = PcReference.wrap(PcReference.run(src, "test_main", globals, work), globals);
            Map<String, Long> snes = runner.run(r.rom(), globals);
            assertEquals(pc, snes, "globals after test_main() (PC vs SNES)");
        }));
    }
}
