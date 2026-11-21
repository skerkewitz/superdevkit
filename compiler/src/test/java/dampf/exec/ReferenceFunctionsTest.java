package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dampf.Compiler;
import dampf.ExamplesTest;
import dampf.emu.ExampleCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The hand-written reference functions (reference/README.md) compute what the C computes: each
 * example with references ({@code reference/<example>_<function>.s}) is built with the naive code
 * generator and all its references linked in place of the compiled functions, plays its check.txt
 * in dampf's emulator, and every tick's checksum must be the one of its normal build.
 */
class ReferenceFunctionsTest {
    private static final Path REFERENCE = Path.of("reference");

    @TestFactory
    Stream<DynamicTest> theReferencesComputeWhatTheCDoes() throws IOException {
        List<Path> examples = ExamplesTest.examples();
        Map<Path, Map<String, Path>> refs = new TreeMap<>();
        try (Stream<Path> s = Files.list(REFERENCE)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".s")).sorted().toList()) {
                String file = f.getFileName().toString();
                for (Path ex : examples) {
                    String prefix = ex.getFileName() + "_";
                    if (file.startsWith(prefix)) {
                        String function = file.substring(prefix.length(), file.length() - 2);
                        refs.computeIfAbsent(ex, k -> new TreeMap<>()).put(function, f.toAbsolutePath());
                    }
                }
            }
        }
        assertFalse(refs.isEmpty(), "no reference functions of an example in " + REFERENCE);
        return refs.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey().getFileName() + " " + e.getValue().keySet(), () -> {
            Path ex = e.getKey();
            String name = ex.getFileName().toString();
            Compiler.Options o = ExamplesTest.options(ex, ex.resolve("build/reference/" + name + "_reference.sfc"));
            Compiler.Result ref = Compiler.compile(new Compiler.Options(o.sources(), o.includeDirs(), o.defines(), o.entry(),
                    o.output(), e.getValue(), Compiler.Backend.NAIVE));
            Compiler.Result mir = ExamplesTest.compile(ex, ex.resolve("build/reference/" + name + "_test.sfc"));
            ExampleCheck.Input input = ExampleCheck.parse(ex.resolve("check.txt"));
            List<String> expected = ExampleCheck.run(Files.readAllBytes(mir.rom().rom()), input, 40 * input.ticks() + 2000).lines();
            List<String> got = ExampleCheck.run(Files.readAllBytes(ref.rom().rom()), input, 40 * input.ticks() + 2000).lines();
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), got.get(i), name + " with " + e.getValue().keySet() + ": first different tick");
            }
        }));
    }
}
