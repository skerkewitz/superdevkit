package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.emu.Snes;
import dampf.util.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The chess example (examples/schach) on the SNES: a test program of its own, built by clang and by
 * dampf, run on the PC and in dampf's emulator; the results (globals named by the program) must be
 * the same. Prints the CPU cycles per unit of work, the measure of the example's speed; with the
 * environment variable SCHACH_PROFILE=1 also the cycles per function, and it writes the ROM and
 * its execution counts to build/schach/ for {@code dampf profile build/schach/<name>.sfc
 * build/schach/<name>.profile.txt}, the cycles per C line.
 *
 * test/perft_snes.c: perft over the five standard positions, the counts the known ones;
 * test/search_snes.c: searches with a node limit, the same moves, scores and node counts;
 * test/end_snes.c: the ends of games (mate, stalemate, 50 moves, repetition, material).
 */
class SchachTest {
    private static final Path TEST = Path.of("examples/schach/test");
    private static final long FRAME = 357_366;

    @TempDir
    Path tmp;

    @Test
    void perftCountsAreRightOnTheSnes() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            Run run = run("perft_snes.c", Map.of("counts", 5, "end_hash", 5, "wrong", 1, "sp", 1, "mv_count", 1, "board", 128,
                    "hist_len", 1), 2000);
            assertEquals("0", run.pc().get("wrong"), "perft counts on the PC: " + run.pc());
            long leaves = 0;
            for (int i = 0; i < 5; i++) {
                leaves += Long.parseLong(run.pc().get("counts[" + i + "]"));
            }
            run.print("perft", leaves, "leaves", "leaf");
        });
    }

    @Test
    void searchGivesTheSameMovesOnTheSnes() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            Run run = run("search_snes.c", Map.of("move_from", 4, "move_to", 4, "move_flag", 4, "scores", 4, "depths", 4, "node_counts", 4,
                    "total_nodes", 1, "given_back", 1), 3000);
            assertEquals("1", run.pc().get("given_back"), "the board after each search");
            run.print("search", Long.parseLong(run.pc().get("total_nodes")), "nodes", "node");
        });
    }

    @Test
    void theEndsOfAGameAreRightOnTheSnes() {
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> {
            Run run = run("end_snes.c", Map.of("ends", 18, "end_wrong", 1), 600);
            assertEquals("0", run.pc().get("end_wrong"), "the ends on the PC: " + run.pc());
        });
    }

    /** What a test program did on both sides, and the CPU cycles test_main took on the SNES. */
    private record Run(Map<String, String> pc, long cycles, Map<String, Long> self) {
        void print(String what, long units, String plural, String unit) {
            System.out.printf("schach %s: %d %s, %d CPU cycles, %d cycles per %s%n", what, units, plural, cycles,
                    cycles / Math.max(1, units), unit);
            self.entrySet().stream().limit(15).forEach(e -> System.out.printf("  %-24s %5.1f %%%n", e.getKey(),
                    100.0 * e.getValue() / cycles));
        }
    }

    /**
     * Builds the program, runs test_main on the PC and on the SNES, and compares the globals named
     * (name and element count; scalars of 1, 2 or 4 bytes, read as the PC's printf gives them).
     */
    private Run run(String program, Map<String, Integer> globals, long frames) throws Exception {
        Path src = TEST.resolve(program);
        boolean profile = "1".equals(System.getenv("SCHACH_PROFILE"));
        Path rom = profile ? Files.createDirectories(Path.of("build/schach")).resolve(program.replace(".c", ".sfc"))
                : tmp.resolve("t.sfc");
        Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main", rom)
                .withBackend(Compiler.Backend.MIR));
        Map<String, Integer> sizes = new HashMap<>();
        for (var v : r.program().globals()) {
            if (globals.containsKey(v.name())) {
                sizes.put(v.name(), v.type().size() / globals.get(v.name()));
            }
        }
        Map<String, String> pc = pc(src, globals);
        Map<String, Long> labels = labels(r.rom().labels());
        Snes snes = Snes.load(r.rom().rom());
        long start = labels.get("_test_main"), stop = labels.get("dampf_done");
        TreeMap<Long, String> code = new TreeMap<>();
        labels.forEach((name, address) -> {
            if ((address & 0xFFFF) >= 0x8000 && (address >> 16) >= 0x80 && (name.startsWith("_") || name.startsWith("dampf_"))) {
                code.merge(address, name, (x, y) -> x.startsWith("__") ? y : x);   // a function, not a linker symbol
            }
        });
        Map<String, Long> self = new HashMap<>();
        Map<Integer, Long> executed = new TreeMap<>();
        long[] at = {-1, -1, 0};
        String[] current = {null};
        assertTrue(snes.run(frames * FRAME, address -> {
            long now = snes.cpu().cycles;
            if (address == start && at[0] < 0) {
                at[0] = now;
            } else if (address == stop && at[0] >= 0 && at[1] < 0) {
                at[1] = now;
            }
            if (profile) {
                if (current[0] != null) {
                    self.merge(current[0], now - at[2], Long::sum);
                }
                Map.Entry<Long, String> f = code.floorEntry((long) address);
                current[0] = f == null ? "?" : f.getValue();
                at[2] = now;
                if ((address & 0xFFFF) >= 0x8000 && (address >> 16) >= 0x80) {   // LoROM: the offset in the file
                    executed.merge(((address >> 16) & 0x7F) * 0x8000 + (address & 0x7FFF), 1L, Long::sum);
                }
            }
        }), program + " did not finish within " + frames + " frames");
        if (profile) {                   // for dampf profile: the cycles per C line
            StringBuilder text = new StringBuilder("clocks " + snes.masterClock() + "\n");
            executed.forEach((offset, count) -> text.append(offset).append(' ').append(count).append('\n'));
            Files.writeString(rom.resolveSibling(program.replace(".c", ".profile.txt")), text);
        }
        byte[] wram = snes.wram();
        for (Map.Entry<String, Integer> g : globals.entrySet()) {
            int size = sizes.get(g.getKey());
            for (int i = 0; i < g.getValue(); i++) {
                String name = g.getValue() == 1 ? g.getKey() : g.getKey() + "[" + i + "]";
                long base = labels.get("_" + g.getKey()) + (long) i * size;
                long v = 0;
                for (int b = 0; b < size; b++) {
                    v |= (long) (wram[(int) (base & 0xFFFF) + b] & 0xFF) << (8 * b);
                }
                assertEquals(pc.get(name), Long.toString(v), name + " on the PC and on the SNES (dampf's emulator)");
            }
        }
        Map<String, Long> sorted = new LinkedHashMap<>();
        self.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return new Run(pc, at[1] - at[0], sorted);
    }

    /** The program built by clang with the undefined behaviour sanitizer; the globals as unsigned numbers. */
    private Map<String, String> pc(Path src, Map<String, Integer> globals) throws IOException {
        StringBuilder h = new StringBuilder("#include <stdio.h>\n#include \"" + src.toAbsolutePath() + "\"\nint main(void)\n{\n"
                + "    test_main();\n");
        for (Map.Entry<String, Integer> g : globals.entrySet()) {
            for (int i = 0; i < g.getValue(); i++) {
                String name = g.getValue() == 1 ? g.getKey() : g.getKey() + "[" + i + "]";
                h.append("    printf(\"%s %llu\\n\", \"").append(name).append("\", (unsigned long long)(uint32_t)")
                        .append(name).append(");\n");
            }
        }
        h.append("    return 0;\n}\n");
        Path c = tmp.resolve("pc.c"), exe = tmp.resolve("pc");
        Files.writeString(c, h);
        Processes.check(tmp, Duration.ofMinutes(1), List.of("clang", "-std=c99", "-O1", "-Wall", "-fsanitize=undefined",
                "-fno-sanitize-recover=all", "-idirafter", dampf.frontend.SystemHeaders.directory().toString(),
                "-o", exe.toString(), c.toString()));
        Map<String, String> out = new HashMap<>();
        for (String line : Processes.check(tmp, Duration.ofMinutes(2), List.of(exe.toString())).strip().split("\n")) {
            int sp = line.lastIndexOf(' ');
            out.put(line.substring(0, sp), line.substring(sp + 1));
        }
        return out;
    }

    private static Map<String, Long> labels(Path file) throws IOException {
        Map<String, Long> m = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 3 && parts[0].equals("al")) {
                m.put(parts[2].substring(1), Long.parseLong(parts[1], 16));
            }
        }
        return m;
    }
}
