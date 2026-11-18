package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.lir.LParser;
import dampf.lir.LPrinter;
import dampf.lir.LUnit;
import dampf.lir.LVerifier;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The back end's output, frozen: a hash of the assembly dampf writes (and of the static timing)
 * for every execution test, a fixed set of random programs and the whole game, with both code
 * generators. A refactoring of the back end that is meant to move code without changing what it
 * does must leave every hash as it was (docs/transition-to-lir.md, "How we keep from breaking
 * things"). A step that changes the code on purpose records new hashes with
 * {@code ./gradlew test --tests '*BackendOutputTest' -PbackendOutput=update}.
 *
 * <p>It also reads every assembly file back as LIR ({@link LParser}): printed again, it must be
 * the same text, and it must pass {@link LVerifier}.
 *
 * <p>Every run writes the assembly to {@code build/backend-output/}; an update also copies it to
 * {@code build/backend-output-ref/}, so a changed hash can be followed with a plain {@code diff -r}.
 */
class BackendOutputTest {
    private static final Path HASHES = Path.of("src/test/resources/backend-output.txt");
    private static final Path OUT = Path.of("build/backend-output");
    private static final Path REF = Path.of("build/backend-output-ref");
    private static final Path GAME = Path.of("examples/blubberblasen");
    static final int RANDOM_PROGRAMS = 20;

    @Test
    void theOutputIsAsRecorded() throws Exception {
        Map<String, String> now = new TreeMap<>();
        for (Case c : cases()) {
            for (Compiler.Backend b : Compiler.Backend.values()) {
                record(now, c, b, false);
            }
            if (c.layout) {
                record(now, c, Compiler.Backend.MIR, true);
            }
        }
        boolean update = "update".equals(System.getProperty("dampf.backendOutput"));
        if (update) {
            StringBuilder text = new StringBuilder();
            now.forEach((k, v) -> text.append(k).append(' ').append(v).append('\n'));
            Files.writeString(HASHES, text);
            copy(OUT, REF);
            return;
        }
        Map<String, String> was = new TreeMap<>();
        for (String line : Files.readAllLines(HASHES)) {
            if (!line.isBlank()) {
                String[] kv = line.split(" ");
                was.put(kv[0], kv[1]);
            }
        }
        List<String> differ = new ArrayList<>();
        for (String k : now.keySet()) {
            if (!now.get(k).equals(was.get(k))) {
                differ.add(k);
            }
        }
        for (String k : was.keySet()) {
            if (!now.containsKey(k)) {
                differ.add(k + " (gone)");
            }
        }
        assertTrue(differ.isEmpty(), "the back end's output changed for " + differ.size() + " of " + now.size()
                + ": " + differ + "\nCompare build/backend-output with build/backend-output-ref (from the last update);"
                + " if the change is meant, run with -PbackendOutput=update.");
    }

    private record Case(String name, List<Path> sources, List<Path> includes, List<String> defines, String entry,
                        boolean layout) {}

    private static List<Case> cases() throws IOException, URISyntaxException {
        List<Case> cases = new ArrayList<>();
        Path exec = Path.of(BackendOutputTest.class.getResource("/exec").toURI());
        try (Stream<Path> s = Files.list(exec)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".c")).sorted().toList()) {
                cases.add(new Case("exec_" + p.getFileName().toString().replace(".c", ""), List.of(p), List.of(), List.of(),
                        "test_main", true));
            }
        }
        Path random = Files.createDirectories(OUT.resolve("random-sources"));
        for (long seed = 1; seed <= RANDOM_PROGRAMS; seed++) {
            Path src = random.resolve("random" + seed + ".c");
            Files.writeString(src, new RandomProgram(seed).generate(10, 30));
            cases.add(new Case("random" + seed, List.of(src), List.of(), List.of(), "test_main", false));
        }
        List<Path> game = new ArrayList<>();
        for (String dir : List.of("src/game", "generated")) {
            try (Stream<Path> s = Files.list(GAME.resolve(dir))) {
                s.filter(p -> p.toString().endsWith(".c")).sorted().forEach(game::add);
            }
        }
        game.add(GAME.resolve("bench/bench.c"));
        List<String> defines = new ArrayList<>();
        for (String f : List.of("setMode", "setBrightness", "setScreenOn", "setScreenOff", "setColorEffect",
                "setColorIntensity", "setPaletteColor", "dmaCopyVram", "dmaCopyCGram", "dmaClearVram", "bgSetGfxPtr",
                "bgSetMapPtr", "bgSetScroll", "bgSetEnable", "bgSetDisable", "bgSetEnableSub", "bgSetDisableSub",
                "bgInitTileSet", "bgInitMapSet", "oamInit", "oamInitGfxSet", "oamInitGfxAttr", "oamSet", "oamSetXY",
                "oamSetEx", "oamSetVisible", "oamClear", "snesRegWrite", "snesHdmaSet", "snesHdmaEnable", "snesDmaFits",
                "oamMemory")) {
            defines.add(f + "=bb_" + f);
        }
        cases.add(new Case("game", game, List.of(GAME.resolve("src"), GAME.resolve("generated")), defines, "main", false));
        return cases;
    }

    private static void record(Map<String, String> hashes, Case c, Compiler.Backend backend, boolean layout)
            throws Exception {
        String key = c.name + "." + (layout ? "layout" : backend.name().toLowerCase());
        Path dir = Files.createDirectories(OUT.resolve(key));
        Compiler.Options o = new Compiler.Options(c.sources, c.includes, c.defines, c.entry, dir.resolve("out.sfc"))
                .withBackend(backend);
        if (layout) {
            o = o.withBlockCounts((function, block) -> (long) Math.floorMod((function + block).hashCode(), 3));
        }
        Compiler.Result r = Compiler.compile(o);
        List<String> asm = r.assembly();
        for (int i = 0; i < asm.size(); i++) {
            Files.writeString(dir.resolve("module" + i + ".s"), asm.get(i));
            // the text is LIR: read back, it prints the same and passes the verifier
            LUnit parsed = LParser.parse(asm.get(i));
            assertEquals(asm.get(i), LPrinter.print(parsed), key + " module " + i + ": the LIR text does not read back the same");
            assertEquals(List.of(), LVerifier.verify(parsed), key + " module " + i + ": the LIR read back");
            // the modification time of a source file (.dbg file) changes with every checkout
            hashes.put(key + ".s" + i, hash(asm.get(i).replaceAll("(\\.dbg\\s+file, \"[^\"]*\", \\d+, )\\d+", "$10")));
        }
        if (!r.timing().isEmpty()) {
            StringBuilder t = new StringBuilder();
            new TreeMap<>(r.timing()).forEach((f, bw) -> t.append(f).append(' ').append(bw[0]).append(' ').append(bw[1]).append('\n'));
            Files.writeString(dir.resolve("timing.txt"), t);
            hashes.put(key + ".timing", hash(t.toString()));
        }
    }

    private static String hash(String text) throws NoSuchAlgorithmException {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d, 0, 12);
    }

    private static void copy(Path from, Path to) throws IOException {
        if (Files.exists(to)) {
            try (Stream<Path> s = Files.walk(to)) {
                for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path q = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(q);
                } else if (p.getFileName().toString().startsWith("module") || p.getFileName().toString().equals("timing.txt")) {
                    Files.copy(p, q);
                }
            }
        }
    }
}
