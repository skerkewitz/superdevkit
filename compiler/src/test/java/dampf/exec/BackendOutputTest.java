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
 * for every execution test, a fixed set of random programs and every example, with both code
 * generators. A refactoring of the back end that is meant to move code without changing what it
 * does must leave every hash as it was (docs/testing.md, ADR record 24). A step that changes the code on purpose records new hashes with
 * {@code ./gradlew test --tests '*BackendOutputTest' -PbackendOutput=update}.
 *
 * <p>It also reads every assembly file back as LIR ({@link LParser}): printed again, it must be
 * the same text, and it must pass {@link LVerifier}.
 *
 * <p>And it builds every ROM a second time with dampf's own assembler and linker
 * ({@link dampf.snes.RomBuilder#buildNative}): the image must be the same, byte for byte, and the
 * label file must name the same addresses.
 *
 * <p>Every run writes the assembly to {@code build/backend-output/}; an update also copies it to
 * {@code build/backend-output-ref/}, so a changed hash can be followed with a plain {@code diff -r}.
 */
class BackendOutputTest {
    private static final Path HASHES = Path.of("compiler/src/test/resources/backend-output.txt");
    private static final Path OUT = Path.of("build/backend-output");
    private static final Path REF = Path.of("build/backend-output-ref");
    static final int RANDOM_PROGRAMS = 20;

    @Test
    void theOutputIsAsRecorded() throws Exception {
        Map<String, String> now = new TreeMap<>();
        for (Case c : cases()) {
            for (Compiler.Backend b : Compiler.Backend.values()) {
                if (c.replacements.isEmpty() || b == Compiler.Backend.NAIVE) {
                    record(now, c, b, false);
                }
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
                        boolean layout, Map<String, Path> replacements) {
        Case(String name, List<Path> sources, List<Path> includes, List<String> defines, String entry, boolean layout) {
            this(name, sources, includes, defines, entry, layout, Map.of());
        }
    }

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
        for (Path ex : dampf.ExamplesTest.examples()) {   /* every example, as its build has it */
            String name = ex.getFileName().toString();
            Compiler.Options o;
            try {
                o = dampf.ExamplesTest.options(ex, OUT.resolve("example_" + name + ".sfc"));
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            cases.add(new Case("example_" + name, o.sources(), o.includeDirs(), o.defines(), o.entry(), false));
            Map<String, Path> refs = new TreeMap<>();
            try (Stream<Path> s = Files.list(Path.of("reference"))) {
                s.filter(f -> f.getFileName().toString().startsWith(name + "_") && f.toString().endsWith(".s")).sorted()
                        .forEach(f -> refs.put(f.getFileName().toString().substring(name.length() + 1).replace(".s", ""), f));
            }
            if (!refs.isEmpty()) {                       /* hand-written functions linked in (the naive generator only) */
                cases.add(new Case("example_" + name + "_reference", o.sources(), o.includeDirs(), o.defines(), o.entry(),
                        false, refs));
            }
        }
        return cases;
    }

    private static void record(Map<String, String> hashes, Case c, Compiler.Backend backend, boolean layout)
            throws Exception {
        String key = c.name + "." + (layout ? "layout" : backend.name().toLowerCase());
        Path dir = Files.createDirectories(OUT.resolve(key));
        Compiler.Options o = new Compiler.Options(c.sources, c.includes, c.defines, c.entry, dir.resolve("out.sfc"), c.replacements)
                .withBackend(backend).withCa65(true);
        if (layout) {
            o = o.withBlockCounts((function, block) -> (long) Math.floorMod((function + block).hashCode(), 3));
        }
        Compiler.Result r = Compiler.compile(o);
        List<String> asm = r.assembly();
        for (int i = 0; i < asm.size(); i++) {
            Files.writeString(dir.resolve("module" + i + ".s"), asm.get(i));
            if (i >= asm.size() - c.replacements.size()) {
                continue;   // hand-written assembly, appended as it is
            }
            // the text is LIR: read back, it prints the same and passes the verifier
            LUnit parsed = LParser.parse(asm.get(i));
            assertEquals(asm.get(i), LPrinter.print(parsed), key + " module " + i + ": the LIR text does not read back the same");
            assertEquals(List.of(), LVerifier.verify(parsed), key + " module " + i + ": the LIR read back");
            // the modification time of a source file (.dbg file) changes with every checkout
            hashes.put(key + ".s" + i, hash(asm.get(i).replaceAll("(\\.dbg\\s+file, \"[^\"]*\", \\d+, )\\d+", "$10")));
        }
        // dampf's own assembler and linker build the same ROM as ca65 and ld65
        Path nativeDir = Files.createDirectories(dir.resolve("native"));
        dampf.snes.RomBuilder.Rom own = dampf.snes.RomBuilder.buildNative(asm, nativeDir, "out", o.fastRom());
        byte[] want = Files.readAllBytes(r.rom().rom());
        byte[] got = Files.readAllBytes(own.rom());
        assertEquals(want.length, got.length, key + ": the ROM's size (own linker vs ld65)");
        for (int i = 0; i < want.length; i++) {
            if (want[i] != got[i]) {
                throw new AssertionError(key + ": the ROM differs at $" + Integer.toHexString(i) + " (bank $"
                        + Integer.toHexString(0x80 + i / 0x8000) + ", address $" + Integer.toHexString(0x8000 + i % 0x8000)
                        + "): ld65 $" + Integer.toHexString(want[i] & 0xFF) + ", dampf $" + Integer.toHexString(got[i] & 0xFF));
            }
        }
        assertEquals(new java.util.TreeSet<>(Files.readAllLines(r.rom().labels())), new java.util.TreeSet<>(Files.readAllLines(own.labels())),
                key + ": the labels (own linker vs ld65)");
        compareDebugFiles(key, DbgMeaning.read(r.rom().dbg()), DbgMeaning.read(own.dbg()));
        if (!r.timing().isEmpty()) {
            StringBuilder t = new StringBuilder();
            new TreeMap<>(r.timing()).forEach((f, bw) -> t.append(f).append(' ').append(bw[0]).append(' ').append(bw[1]).append('\n'));
            Files.writeString(dir.resolve("timing.txt"), t);
            hashes.put(key + ".timing", hash(t.toString()));
        }
    }

    /** The debug files say the same: lines and their bytes, symbols, segments, scopes, C symbols. */
    static void compareDebugFiles(String key, DbgMeaning ld65, DbgMeaning own) {
        java.util.Set<String> keys = new java.util.TreeSet<>(ld65.lines().keySet());
        keys.addAll(own.lines().keySet());
        List<String> differ = new ArrayList<>();
        for (String k : keys) {
            if (!java.util.Objects.equals(ld65.lines().get(k), own.lines().get(k))) {
                differ.add(k + ": ld65 " + ld65.lines().get(k) + ", dampf " + own.lines().get(k));
            }
        }
        assertTrue(differ.isEmpty(), key + ": the debug files differ in " + differ.size() + " lines: "
                + differ.subList(0, Math.min(8, differ.size())));
        assertEquals(ld65.symbols(), own.symbols(), key + ": the symbols of the debug files");
        assertEquals(ld65.segments(), own.segments(), key + ": the segments of the debug files");
        assertEquals(ld65.scopes(), own.scopes(), key + ": the scopes of the debug files");
        // ld65 leaves out the symbol of a few static variables whose label is in another module
        // ("?"); dampf's file names it
        java.util.TreeSet<String> ownCsyms = new java.util.TreeSet<>();
        for (String c : own.csyms()) {
            String unknown = c.substring(0, c.lastIndexOf(' ') + 1) + "?";
            ownCsyms.add(!ld65.csyms().contains(c) && ld65.csyms().contains(unknown) ? unknown : c);
        }
        assertEquals(ld65.csyms(), ownCsyms, key + ": the C symbols of the debug files");
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
