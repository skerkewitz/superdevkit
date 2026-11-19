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
 *   java -cp 'build/install/dampf/lib/*' dampf.emu.ExampleBench rom.sfc rom.lbl check.txt
 * </pre>
 *
 * prints {@code ticks frames/tick work/tick busiest/tick runtime%}: the ticks played, the frames
 * per tick, the work per tick on average and in the busiest 60 ticks in a row (master cycles; a
 * frame has 357,368), and the share of the work in dampf's runtime routines ({@code dampf_*}:
 * multiplication, division, shifts by a variable). The ROM's timing report ({@code .timing.txt})
 * and debug file ({@code .dbg}) must lie beside it; run it from the top of the repository.
 */
public final class ExampleBench {
    private static final int WINDOW = 60;

    private ExampleBench() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: ExampleBench rom.sfc rom.lbl check.txt");
            System.exit(2);
        }
        byte[] rom = Files.readAllBytes(Path.of(args[0]));
        TreeMap<Integer, String> labels = ExampleProfile.labels(Path.of(args[1]), ExampleProfile.functions(Path.of(args[0])));
        ExampleCheck.Input input = ExampleCheck.parse(Path.of(args[2]));
        long n = input.ticks();
        Set<Integer> waiting = waitingAddresses(Path.of(args[0].replaceFirst("\\.sfc$", ".dbg")));

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
        System.out.printf("%d %.3f %d %d %.1f%n", n, frames, total / n, busiest / Math.min(n, WINDOW),
                total == 0 ? 0.0 : 100.0 * runtime[0] / total);
    }

    /** The addresses of the C lines of the main loop's two waiting functions, wherever inlined. */
    private static Set<Integer> waitingAddresses(Path dbg) throws IOException {
        Path snes = Path.of("lib/snes/snes.c");
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
                lines.add("lib/snes/snes.c:" + (i + 1));
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
