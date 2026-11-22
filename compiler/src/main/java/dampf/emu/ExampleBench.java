package dampf.emu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One line of the examples' benchmark (dev/bench_examples.sh): plays an example's ROM with the
 * input of its {@code check.txt} in dampf's emulator and measures the work of its ticks, the
 * master cycles from the end of one tick to the end of the next minus the time the main loop
 * waits (for the vertical blank in {@code sdevkit__wait_vblank}, for the joypads in
 * {@code sdevkit__read_pads}; dampf inlines them, so they are found by their C lines in
 * {@code lib/snes/snes.c}, from the ROM's debug file). Frames per tick cannot show a compiler that got faster in an
 * example that does a fixed amount of work a tick (Mandelbrot); the work can.
 *
 * <pre>
 *   java -cp 'build/install/superdevkit/java/*' dampf.emu.ExampleBench rom.sfc rom.lbl check.txt [work.txt]
 * </pre>
 *
 * prints {@code ticks frames/tick work/tick busiest/tick runtime%}: the ticks played, the frames
 * per tick, the work per tick on average and in the busiest 60 ticks in a row (master cycles; a
 * frame has 357,368), and the share of the work in dampf's runtime routines ({@code dampf_*}:
 * multiplication, division, shifts by a variable). The ROM's timing report ({@code .timing.txt})
 * and debug file ({@code .dbg}) must lie beside it, and the sources it names where it names them.
 * With {@code work.txt} it also writes the work of every tick, one line {@code tick cycles} each.
 */
public final class ExampleBench {
    private static final int WINDOW = 60;
    private static final String SNES_C = "lib/snes/snes.c";

    private ExampleBench() {
    }

    /** What a run measured: the work of every tick, and the numbers of the benchmark's line. */
    record Measured(long[] work, double framesPerTick, long average, long busiest, double runtimeShare) {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            System.err.println("usage: ExampleBench rom.sfc rom.lbl check.txt [work.txt]");
            System.exit(2);
        }
        Measured m = measure(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.printf("%d %.3f %d %d %.1f%n", m.work().length, m.framesPerTick(), m.average(), m.busiest(), m.runtimeShare());
        if (args.length == 4) {
            List<String> lines = new java.util.ArrayList<>();
            for (int t = 0; t < m.work().length; t++) {
                lines.add(t + " " + m.work()[t]);
            }
            Files.write(Path.of(args[3]), lines);
        }
    }

    static Measured measure(Path romFile, Path lbl, Path check) throws IOException {
        byte[] rom = Files.readAllBytes(romFile);
        TreeMap<Integer, String> labels = ExampleProfile.labels(lbl, ExampleProfile.functions(romFile));
        ExampleCheck.Input input = ExampleCheck.parse(check);
        long n = input.ticks();
        Set<Integer> waiting = waitingAddresses(Path.of(romFile.toString().replaceFirst("\\.sfc$", ".dbg")));

        Snes snes = new Snes(rom);
        long[] tick = { -1 };
        long[] work = new long[(int) n];
        long[] runtime = { 0 };
        long[] firstClock = { -1 };
        snes.onPortWrite((port, value, clock) -> {
            if (port != 0x21FD) {
                return;
            }
            if (value == 1) {
                tick[0] = 0;
                firstClock[0] = clock;
            } else if (tick[0] >= 0) {
                tick[0]++;
                if (tick[0] >= n) {
                    snes.stop();
                }
            }
            snes.setPads(tick[0] >= 0 ? input.pad(tick[0]) : 0, 0);
        });
        int[] last = { -1 };
        long[] lastClock = { 0 };
        snes.run((n + 100) * 40 * ExampleCheck.FRAME, address -> {
            long now = snes.masterClock();
            if (last[0] >= 0 && tick[0] >= 0 && tick[0] < n) {
                Map.Entry<Integer, String> e = labels.floorEntry(last[0]);
                String f = e == null ? "" : e.getValue();
                if (!waiting.contains(last[0])) {
                    work[(int) tick[0]] += now - lastClock[0];
                    if (f.startsWith("dampf_")) {
                        runtime[0] += now - lastClock[0];
                    }
                }
            }
            last[0] = address;
            lastClock[0] = now;
        });
        if (tick[0] < n) {
            throw new IOException("played " + Math.max(0, tick[0]) + " of " + n + " ticks");
        }
        long total = 0;
        long busiest = 0;
        long window = 0;
        for (int i = 0; i < n; i++) {
            total += work[i];
            window += work[i];
            if (i >= WINDOW) {
                window -= work[i - WINDOW];
            }
            if (i >= WINDOW - 1) {
                busiest = Math.max(busiest, window);
            }
        }
        double frames = (double) (snes.masterClock() - firstClock[0]) / ExampleCheck.FRAME / n;
        return new Measured(work, frames, total / n, busiest / Math.min(n, WINDOW),
                total == 0 ? 0.0 : 100.0 * runtime[0] / total);
    }

    /**
     * The addresses of the C lines of the main loop's two waiting functions, wherever inlined.
     * The kit's {@code lib/snes/snes.c} is found among the debug file's sources by the end of its
     * path, and read where the debug file says it is (relative to the debug file's folder), so a
     * game built outside the kit's repository can be measured too.
     */
    private static Set<Integer> waitingAddresses(Path dbg) throws IOException {
        String named = null;
        for (String l : Files.readAllLines(dbg)) {
            if (l.startsWith("file\t")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("name=\"([^\"]*)\"").matcher(l);
                if (m.find() && (m.group(1).equals(SNES_C) || m.group(1).endsWith("/" + SNES_C))) {
                    named = m.group(1);
                    break;
                }
            }
        }
        if (named == null) {
            throw new IOException(dbg + ": the ROM has no code from " + SNES_C + " (built without the kit's main loop?)");
        }
        Path snes = dbg.toAbsolutePath().getParent().resolve(named).normalize();
        String key = named.replaceFirst("^(\\.\\./)+", "");   /* as ExampleProfile.cLines names it */
        List<String> src = Files.readAllLines(snes);
        Set<String> lines = new HashSet<>();
        for (String name : List.of("void sdevkit__wait_vblank(void)", "void sdevkit__read_pads(")) {
            int i = 0;
            while (i < src.size() && !src.get(i).startsWith(name)) {
                i++;
            }
            if (i == src.size()) {
                throw new IOException(snes + ": no " + name);
            }
            for (; i < src.size() && !src.get(i).equals("}"); i++) {
                lines.add(key + ":" + (i + 1));
            }
        }
        Set<Integer> at = new HashSet<>();
        for (Map.Entry<Integer, String> e : ExampleProfile.cLines(dbg).entrySet()) {
            if (lines.contains(e.getValue())) {
                at.add(e.getKey());
            }
        }
        if (at.isEmpty()) {
            throw new IOException(dbg + ": no code of the main loop's waiting functions");
        }
        return at;
    }
}
