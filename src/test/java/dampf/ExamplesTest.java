package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Every example builds for the SNES: its build.sh converts the assets (CONVERT_ONLY=1), then dampf
 * compiles it with its argument file. Playing it is dev/check_example.sh's job (Mesen).
 */
class ExamplesTest {

    @TestFactory
    Stream<DynamicTest> everyExampleBuilds() throws IOException {
        List<Path> examples;
        try (Stream<Path> s = Files.list(Path.of("examples"))) {
            examples = s.filter(d -> Files.isRegularFile(d.resolve(d.getFileName() + ".args"))).sorted().toList();
        }
        assertTrue(!examples.isEmpty(), "no examples with an argument file");
        return examples.stream().map(ex -> DynamicTest.dynamicTest(ex.getFileName().toString(), () -> {
            String name = ex.getFileName().toString();
            if (Files.isRegularFile(ex.resolve("build.sh"))) {
                ProcessBuilder pb = new ProcessBuilder("sh", ex.resolve("build.sh").toString()).redirectErrorStream(true);
                pb.environment().put("CONVERT_ONLY", "1");
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor(120, TimeUnit.SECONDS);
                assertEquals(0, p.exitValue(), out);
            }
            List<String> args = new ArrayList<>(ConfigFile.expand(List.of("build", "@" + ex.resolve(name + ".args"))));
            Compiler.Options o = options(args, ex.resolve("build/" + name + "_test.sfc"));
            Compiler.Result r = Compiler.compile(o.withBackend(Compiler.Backend.MIR));
            assertTrue(Files.size(r.rom().rom()) >= 32 * 1024, "no ROM written");
        }));
    }

    /** The options dampf build takes from these arguments (-I, --entry, sources). */
    private static Compiler.Options options(List<String> args, Path out) throws IOException {
        List<Path> includes = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        String entry = "main";
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-I")) {
                includes.add(Path.of(args.get(++i)));
            } else if (a.equals("--entry")) {
                entry = args.get(++i);
            } else if (a.startsWith("-")) {
                throw new IllegalArgumentException("an option the test does not know: " + a);
            } else {
                sources.addAll(ConfigFile.sources(Path.of(a)));
            }
        }
        return new Compiler.Options(sources, includes, List.of(), entry, out);
    }
}
