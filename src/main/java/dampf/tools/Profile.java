package dampf.tools;

import dampf.target.Cycles65816;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cycles per function from an execution profile: Mesen counts how often each instruction ran
 * (tools/profile.lua), dampf knows what each instruction costs ({@link Cycles65816}).
 *
 * <p>Instructions are assigned to the function whose code contains them (the nearest function
 * label below; functions are the {@code .proc}s of the generated assembly and the runtime's
 * routines). The accumulator width is followed through each function from its
 * {@code SEP #$20}/{@code REP #$20}; index registers are always 16 bit in dampf's code. A
 * conditional branch counts as taken as often as the instruction after it did not run, which is
 * exact for dampf's "branch around a JMP" and for loops.
 *
 * <p>Calls are counted at the callers: every {@code JSL} adds its execution count to the
 * function it targets.
 *
 * <p>The cycles are CPU cycles. If the profile holds the master clocks of the measured stretch
 * ({@code clocks N} line), they are shown too. Functions from files given with
 * {@code --skip file.c} (a test harness, such as the benchmark's bench.c) are left out of the
 * table and the shares. {@code --instructions} adds a table of the cycles per instruction and
 * addressing mode (outside those files): which kinds of instruction the time goes to.
 * {@code --blocks file} writes how often each block ran, for {@code dampf build --block-counts}.
 */
public final class Profile {
    private static final double FRAME = 357_366; // master clocks per NTSC frame

    /** What one function cost; {@code file} is the C file it is defined in (null for the runtime). */
    public record Entry(String name, String file, long calls, long instructions, long cycles) {}

    /** A function's start in ROM: its name and the C file it comes from. */
    record Start(String name, String file) {}

    private Profile() {}

    public static void main(String[] args) throws IOException {
        List<String> positional = new ArrayList<>();
        List<String> skip = new ArrayList<>();
        boolean byInstruction = false;
        Path blocksOut = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--skip") && i + 1 < args.length) {
                skip.add(args[++i]);
            } else if (args[i].equals("--instructions")) {
                byInstruction = true;
            } else if (args[i].equals("--blocks") && i + 1 < args.length) {
                blocksOut = Path.of(args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.size() < 2) {
            System.err.println("usage: dampf profile <rom.sfc> <profile.txt> [top N] [--skip file.c ...] [--instructions] [--blocks file]");
            System.exit(2);
        }
        int top = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : 40;
        Path rom = Path.of(positional.get(0));
        Path counts = Path.of(positional.get(1));
        long clocks = readClocks(counts);
        Map<Integer, long[]> perOpcode = new TreeMap<>();
        List<Entry> all = analyse(rom, counts, perOpcode,
                file -> file == null || skip.stream().noneMatch(file::endsWith));
        List<Entry> entries = all.stream().filter(e -> e.file() == null || skip.stream().noneMatch(f -> e.file().endsWith(f))).toList();
        long allCycles = all.stream().mapToLong(Entry::cycles).sum();
        long totalCycles = entries.stream().mapToLong(Entry::cycles).sum();
        long totalInstr = entries.stream().mapToLong(Entry::instructions).sum();
        System.out.printf("%,d instructions, %,d CPU cycles%s%n", totalInstr, totalCycles,
                clocks > 0 ? String.format(" (measured stretch: %,d master clocks, %.1f frames)", clocks, clocks / FRAME) : "");
        if (!skip.isEmpty()) {
            System.out.printf("left out (%s): %.1f%% of all CPU cycles%n", String.join(", ", skip), 100.0 * (allCycles - totalCycles) / allCycles);
        }
        System.out.printf("%7s %10s %13s %15s %10s  %s%n", "share", "calls", "instructions", "CPU cycles", "cyc/call", "function");
        int shown = 0;
        for (Entry e : entries) {
            if (shown++ == top) {
                break;
            }
            System.out.printf("%6.1f%% %,10d %,13d %,15d %,10d  %s%n", 100.0 * e.cycles() / totalCycles, e.calls(),
                    e.instructions(), e.cycles(), e.calls() > 0 ? e.cycles() / e.calls() : 0, e.name());
        }
        if (blocksOut != null) {
            writeBlockCounts(rom, counts, blocksOut);
        }
        if (byInstruction) {
            System.out.println();
            System.out.printf("%7s %13s %15s  %s%n", "share", "executed", "CPU cycles", "instruction");
            perOpcode.entrySet().stream()
                    .sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]))
                    .limit(top)
                    .forEach(e -> System.out.printf("%6.1f%% %,13d %,15d  %s%n", 100.0 * e.getValue()[1] / totalCycles,
                            e.getValue()[0], e.getValue()[1], Cycles65816.name(e.getKey())));
        }
    }

    private static long readClocks(Path counts) throws IOException {
        for (String line : Files.readAllLines(counts)) {
            if (line.startsWith("clocks ")) {
                return Long.parseLong(line.substring(7).strip());
            }
        }
        return 0;
    }

    /** Functions sorted by cycles, most expensive first. */
    public static List<Entry> analyse(Path romFile, Path countsFile) throws IOException {
        return analyse(romFile, countsFile, new HashMap<>(), file -> true);
    }

    /**
     * Also adds up, per opcode, how often it ran and its cycles ({@code perOpcode}), in the
     * functions whose C file {@code counted} accepts (null: the runtime).
     */
    public static List<Entry> analyse(Path romFile, Path countsFile, Map<Integer, long[]> perOpcode,
                                      java.util.function.Predicate<String> counted) throws IOException {
        byte[] rom = Files.readAllBytes(romFile);
        String base = romFile.getFileName().toString().replaceAll("\\.sfc$", "");
        Path dir = romFile.toAbsolutePath().getParent();
        TreeMap<Integer, Start> functions = functionStarts(dir, base);

        TreeMap<Integer, Long> counts = new TreeMap<>();
        for (String line : Files.readAllLines(countsFile)) {
            String[] p = line.strip().split("\\s+");
            if (p.length == 2 && p[0].matches("\\d+")) {
                counts.merge(Integer.parseInt(p[0]), Long.parseLong(p[1]), Long::sum);
            }
        }

        Map<String, long[]> acc = new HashMap<>(); // calls, instructions, cycles
        Map<String, String> files = new HashMap<>();
        // Mesen counts every byte of an executed instruction, so the code is decoded: each
        // function from its start to the next function, with instruction lengths that follow
        // the accumulator width (SEP/REP #$20). dampf's functions contain no data between
        // instructions, so the walk stays on instruction boundaries.
        List<Integer> startOffsets = new ArrayList<>(functions.keySet());
        for (int i = 0; i < startOffsets.size(); i++) {
            int from = startOffsets.get(i);
            int bankEnd = (from / 0x8000 + 1) * 0x8000;
            int to = Math.min(bankEnd, i + 1 < startOffsets.size() ? startOffsets.get(i + 1) : rom.length);
            Start fn = functions.get(from);
            files.put(fn.name(), fn.file());
            long[] a = acc.computeIfAbsent(fn.name(), k -> new long[3]);
            boolean count = counted.test(fn.file());
            boolean m16 = true; // every function starts with a 16-bit accumulator
            int off = from;
            while (off < to) {
                int op = rom[off] & 0xFF;
                int len = Cycles65816.length(op, m16, true);
                long n = counts.getOrDefault(off, 0L);
                if (n > 0) {
                    a[1] += n;
                    long c;
                    if (Cycles65816.isConditionalBranch(op)) {
                        long next = counts.getOrDefault(off + 2, 0L);
                        long taken = Math.max(0, n - next);
                        c = taken * Cycles65816.cycles(op, m16, true, true) + (n - taken) * Cycles65816.cycles(op, m16, true, false);
                    } else {
                        c = n * Cycles65816.cycles(op, m16, true, false);
                    }
                    a[2] += c;
                    if (count) {
                        long[] o = perOpcode.computeIfAbsent(op, k -> new long[2]);
                        o[0] += n;
                        o[1] += c;
                    }
                    if (op == 0x22 && off + 3 < rom.length) {
                        // JSL: one call of the function it targets
                        int target = (rom[off + 1] & 0xFF) | (rom[off + 2] & 0xFF) << 8 | (rom[off + 3] & 0xFF) << 16;
                        Start callee = functions.get(romOffset(target));
                        if (callee != null) {
                            acc.computeIfAbsent(callee.name(), k -> new long[3])[0] += n;
                        }
                    }
                }
                if ((op == 0xE2 || op == 0xC2) && off + 1 < rom.length && (rom[off + 1] & 0x20) != 0) {
                    m16 = op == 0xC2;
                }
                off += len;
            }
        }
        List<Entry> out = new ArrayList<>();
        acc.forEach((k, v) -> out.add(new Entry(k, files.get(k), v[0], v[1], v[2])));
        out.sort((x, y) -> Long.compare(y.cycles(), x.cycles()));
        return out;
    }

    /**
     * How often each block of the generated code ran: per function (a .proc label) every block
     * label in it (bNN_name, from the label file) with the count of the instruction there. Read by
     * the layout of the code generator from MIR (dampf.blockCounts).
     */
    static void writeBlockCounts(Path romFile, Path countsFile, Path out) throws IOException {
        String base = romFile.getFileName().toString().replaceAll("\\.sfc$", "");
        Path lbl = romFile.toAbsolutePath().getParent().resolve(base + ".lbl");
        Map<Integer, Long> counts = new HashMap<>();
        for (String line : Files.readAllLines(countsFile)) {
            String[] p = line.strip().split("\\s+");
            if (p.length == 2 && p[0].matches("\\d+")) {
                counts.merge(Integer.parseInt(p[0]), Long.parseLong(p[1]), Long::sum);
            }
        }
        TreeMap<Integer, List<String>> labels = new TreeMap<>();
        for (String line : Files.readAllLines(lbl)) {
            String[] p = line.strip().split("\\s+");
            if (p.length >= 3 && p[2].startsWith(".")) {
                int a = Integer.parseInt(p[1], 16);
                if ((a & 0x8000) == 0) {
                    continue;   // RAM, not code
                }
                labels.computeIfAbsent(romOffset(a), k -> new ArrayList<>()).add(p[2].substring(1));
            }
        }
        StringBuilder sb = new StringBuilder();
        String function = null;
        for (Map.Entry<Integer, List<String>> e : labels.entrySet()) {
            for (String name : e.getValue()) {
                if (name.startsWith("_")) {
                    function = name;
                }
            }
            for (String name : e.getValue()) {
                if (function != null && name.matches("b\\d+_.*")) {
                    sb.append(function).append(' ').append(name).append(' ').append(counts.getOrDefault(e.getKey(), 0L)).append('\n');
                }
            }
        }
        Files.writeString(out, sb);
    }

    /** ROM offset of every function: the .procs of the generated modules and the runtime's routines. */
    static TreeMap<Integer, Start> functionStarts(Path dir, String base) throws IOException {
        Map<String, Integer> labels = new HashMap<>();
        for (String line : Files.readAllLines(dir.resolve(base + ".lbl"))) {
            String[] p = line.strip().split("\\s+");
            if (p.length == 3 && p[0].equals("al")) {
                labels.put(p[2].substring(1), Integer.parseInt(p[1], 16));
            }
        }
        Map<String, String> procs = new java.util.LinkedHashMap<>(); // label -> C file
        // "; ---- void f(void)    (file.c:12:6)" comes right before ".segment" and ".proc _f"
        Pattern proc = Pattern.compile("^; ---- .*\\((.+?):\\d+:\\d+\\)\\s*\\n\\s*\\.segment[^\\n]*\\n\\.proc\\s+(\\S+)", Pattern.MULTILINE);
        try (var files = Files.list(dir)) {
            for (Path s : files.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".s") && (n.equals(base + ".s") || n.startsWith(base + "_"));
            }).toList()) {
                Matcher m = proc.matcher(Files.readString(s));
                while (m.find()) {
                    procs.put(m.group(2), m.group(1));
                }
            }
        }
        for (String runtime : List.of("reset", "dampf_mul16", "dampf_mul32", "dampf_udiv16", "dampf_udiv32", "dampf_sdiv32")) {
            procs.put(runtime, null);
        }
        TreeMap<Integer, Start> starts = new TreeMap<>();
        for (Map.Entry<String, String> p : procs.entrySet()) {
            Integer addr = labels.get(p.getKey());
            if (addr != null && addr >= 0x808000) {
                String shown = p.getKey().startsWith("_") ? p.getKey().substring(1) : "(runtime) " + p.getKey();
                starts.put(romOffset(addr), new Start(shown, p.getValue()));
            }
        }
        return starts;
    }

    /** LoROM: bank $80+n, address $8000-$FFFF is file offset n * $8000 + (address - $8000). */
    static int romOffset(int address) {
        return ((address >> 16) & 0x7F) * 0x8000 + (address & 0x7FFF);
    }
}
