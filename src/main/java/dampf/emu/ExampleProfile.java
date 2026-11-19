package dampf.emu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where an example's time goes, in dampf's emulator: plays the ROM with the input of its
 * {@code check.txt} (as {@link ExampleCheck} does) and charges the master cycles of every
 * instruction between two ticks to the function it belongs to, by the labels of the ROM's
 * {@code .lbl} file and the functions of its timing report ({@code .timing.txt} beside the ROM);
 * calls are charged to the callee, not the caller, and waiting for the vertical blank to the
 * function that waits. No Mesen needed.
 *
 * <pre>
 *   java -cp 'build/install/dampf/lib/*' dampf.emu.ExampleProfile rom.sfc rom.lbl check.txt FROM TO [TOP]
 * </pre>
 *
 * prints the frames the ticks FROM to TO - 1 took and the TOP (default 25) functions by their share;
 * when the ROM's debug file lies beside it ({@code rom.dbg}, the one Mesen reads), also the TOP C
 * lines, which tells where inside a function, and inside the functions dampf inlined, the time goes.
 */
public final class ExampleProfile {
    private ExampleProfile() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.err.println("usage: ExampleProfile rom.sfc rom.lbl check.txt FROM TO [TOP]");
            System.exit(2);
        }
        byte[] rom = Files.readAllBytes(Path.of(args[0]));
        TreeMap<Integer, String> labels = labels(Path.of(args[1]), functions(Path.of(args[0])));
        ExampleCheck.Input input = ExampleCheck.parse(Path.of(args[2]));
        long from = Long.parseLong(args[3]);
        long to = Long.parseLong(args[4]);
        int top = args.length > 5 ? Integer.parseInt(args[5]) : 25;

        Path dbg = Path.of(args[0].replaceFirst("\\.sfc$", ".dbg"));
        Map<Integer, String> lines = Files.exists(dbg) ? cLines(dbg) : Map.of();
        Map<String, Long> lineCycles = new HashMap<>();
        Snes snes = new Snes(rom);
        long[] ticks = { -1 };
        long[] window = { 0, 0 };
        Map<String, Long> cycles = new HashMap<>();
        snes.onPortWrite((port, value, clock) -> {
            if (port != 0x21FD) {
                return;
            }
            if (value == 1) {
                ticks[0] = 0;
            } else if (ticks[0] >= 0) {
                ticks[0]++;
                if (ticks[0] == from) {
                    window[0] = clock;
                }
                if (ticks[0] == to) {
                    window[1] = clock;
                    snes.stop();
                }
            }
            snes.setPads(ticks[0] >= 0 ? input.pad(ticks[0]) : 0, 0);
        });
        int[] last = { -1 };
        long[] lastClock = { 0 };
        snes.run((to + 100) * 40 * ExampleCheck.FRAME, address -> {
            long now = snes.masterClock();
            if (last[0] >= 0 && ticks[0] >= from && ticks[0] < to) {
                Map.Entry<Integer, String> e = labels.floorEntry(last[0]);
                cycles.merge(e == null ? "?" : e.getValue(), now - lastClock[0], Long::sum);
                String line = lines.get(last[0]);
                if (line != null) {
                    lineCycles.merge(line, now - lastClock[0], Long::sum);
                }
            }
            last[0] = address;
            lastClock[0] = now;
        });
        long total = window[1] - window[0];
        System.out.printf("ticks %d to %d: %.3f frames per tick%n", from, to, (double) total / (to - from) / ExampleCheck.FRAME);
        List<Map.Entry<String, Long>> rows = new ArrayList<>(cycles.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(top, rows.size()); i++) {
            Map.Entry<String, Long> r = rows.get(i);
            System.out.printf("%6.1f %%  %10d  %s%n", 100.0 * r.getValue() / total, r.getValue() / (to - from), r.getKey());
        }
        if (!lineCycles.isEmpty()) {
            System.out.println("C lines:");
            List<Map.Entry<String, Long>> byLine = new ArrayList<>(lineCycles.entrySet());
            byLine.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (int i = 0; i < Math.min(top, byLine.size()); i++) {
                Map.Entry<String, Long> r = byLine.get(i);
                System.out.printf("%6.1f %%  %10d  %s%n", 100.0 * r.getValue() / total, r.getValue() / (to - from), r.getKey());
            }
        }
    }

    /**
     * The C line of every ROM address, from the debug file: "seg" gives a segment's address, "span"
     * a range of it, and a "line" of type 1 (C, not assembly) its file, its number and its spans.
     */
    static Map<Integer, String> cLines(Path dbg) throws IOException {
        Map<Integer, Integer> segStart = new HashMap<>();
        Map<Integer, int[]> spans = new HashMap<>();
        Map<Integer, String> files = new HashMap<>();
        List<String> lineRecords = new ArrayList<>();
        for (String l : Files.readAllLines(dbg)) {
            int tab = l.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String kind = l.substring(0, tab);
            Map<String, String> f = new HashMap<>();
            for (String kv : l.substring(tab + 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    f.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
            switch (kind) {
                case "seg" -> segStart.put(Integer.parseInt(f.get("id")), Integer.decode(f.get("start")));
                case "span" -> spans.put(Integer.parseInt(f.get("id")),
                        new int[] { Integer.parseInt(f.get("seg")), Integer.parseInt(f.get("start")), Integer.parseInt(f.get("size")) });
                case "file" -> files.put(Integer.parseInt(f.get("id")), f.get("name").replace("\"", "").replaceFirst("^(\\.\\./)+", ""));
                case "line" -> {
                    if ("1".equals(f.get("type")) && f.containsKey("span")) {
                        lineRecords.add(f.get("file") + ":" + f.get("line") + ":" + f.get("span"));
                    }
                }
                default -> {
                }
            }
        }
        Map<Integer, String> at = new HashMap<>();
        for (String r : lineRecords) {
            String[] p = r.split(":", 3);
            String name = files.getOrDefault(Integer.parseInt(p[0]), "?") + ":" + p[1];
            for (String id : p[2].split("\\+")) {
                int[] sp = spans.get(Integer.parseInt(id));
                Integer base = sp == null ? null : segStart.get(sp[0]);
                if (base != null) {
                    for (int i = 0; i < sp[2]; i++) {
                        at.put(base + sp[1] + i, name);
                    }
                }
            }
        }
        return at;
    }

    /**
     * The functions of the ROM, from the timing report dampf writes beside it ({@code rom.timing.txt});
     * null without one (the naive code generator writes none), and then every C label counts.
     */
    static java.util.Set<String> functions(Path rom) throws IOException {
        Path timing = rom.resolveSibling(rom.getFileName().toString().replaceFirst("\\.sfc$", ".timing.txt"));
        if (!Files.exists(timing)) {
            return null;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String line : Files.readAllLines(timing)) {
            String[] w = line.strip().split("\\s+");
            if (w.length >= 3 && !line.startsWith("#") && w[2].startsWith("_")) {
                names.add(w[2]);
            }
        }
        return names;
    }

    /** "al 80C218 ._name": the start address of each function (data labels are left out). */
    static TreeMap<Integer, String> labels(Path lbl, java.util.Set<String> functions) throws IOException {
        TreeMap<Integer, String> m = new TreeMap<>();
        for (String line : Files.readAllLines(lbl)) {
            String[] w = line.strip().split("\\s+");
            if (w.length == 3 && w[0].equals("al") && w[2].startsWith(".")
                    && (functions == null ? w[2].startsWith("._") : functions.contains(w[2].substring(1)) || w[2].startsWith(".dampf_"))) {   /* the runtime's routines too */
                m.put(Integer.parseInt(w[1], 16), w[2].substring(w[2].startsWith("._") ? 2 : 1));
            }
        }
        return m;
    }
}
