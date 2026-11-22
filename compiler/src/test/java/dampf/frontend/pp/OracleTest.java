package dampf.frontend.pp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.frontend.SourceMap;
import dampf.frontend.SystemHeaders;
import dampf.util.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * dampf's preprocessor against clang's ({@code clang -E}, the tests' reference on the PC,
 * ADR-6FD2DGP0) on every C file dampf compiles in the repository: the test programs, the examples
 * and the library. Both must give the same tokens, and every token the same file and line. A file
 * clang cannot preprocess (a header the build generates first) must fail in dampf too.
 */
class OracleTest {
    @TestFactory
    Stream<DynamicTest> theSameTokensOnTheSameLinesAsClang() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String dir : List.of("compiler/src/test/resources/exec", "compiler/src/test/resources/spelling",
                "compiler/src/test/resources/style", "lib/snes")) {
            files.addAll(cFiles(Path.of(dir)));
        }
        try (Stream<Path> examples = Files.list(Path.of("examples"))) {
            for (Path ex : examples.sorted().toList()) {
                files.addAll(cFiles(ex.resolve("src")));
            }
        }
        return Concurrently.tests(files, Path::toString, OracleTest::compare);
    }

    private static List<Path> cFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".c")).sorted().toList();
        }
    }

    private static void compare(Path f) throws Exception {
        List<Path> dirs = new ArrayList<>(List.of(Path.of("lib/include"), Path.of("lib/snes")));
        Path gen = f.getParent().resolveSibling("build").resolve("gen");
        if (Files.isDirectory(gen)) {
            dirs.add(gen);
        }
        List<String> cmd = new ArrayList<>(List.of("clang", "-E", "-std=c99", "-nostdinc", "-undef", "-D__DAMPF__=1",
                "-I" + SystemHeaders.directory()));
        for (Path d : dirs) {
            cmd.add("-I" + d);
        }
        cmd.add(f.toString());
        Processes.Result clang = Processes.run(null, Duration.ofMinutes(1), cmd);
        String ours;
        try {
            ours = new Preprocess(SystemHeaders.directory(), dirs, List.of()).run(f);
        } catch (PpException e) {
            assertTrue(clang.exit() != 0, "dampf failed where clang did not: " + e.getMessage() + " at " + e.file() + ":" + e.line());
            return;
        }
        assertEquals(0, clang.exit(), "clang failed where dampf did not: " + clang.err());
        List<String> a = placed(clang.out(), f.toString());
        List<String> b = placed(ours, f.toString());
        for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
            assertEquals(a.get(k), b.get(k), "token " + k + " (clang, then dampf)");
        }
        assertEquals(a.size(), b.size(), "number of tokens");
    }

    /** Every token of preprocessed text as "file:line token"; markers and pragmas left out. */
    static List<String> placed(String text, String main) {
        SourceMap map = SourceMap.of(text, main);
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        for (int k = 0; k < lines.length; k++) {
            if (lines[k].stripLeading().startsWith("#")) {
                continue;
            }
            var p = map.map(k + 1, 0);
            for (Token t : Lexer.lexLine(lines[k], "x", 1, 1)) {
                out.add(p.file() + ":" + p.line() + " " + t.text());
            }
        }
        return out;
    }
}
