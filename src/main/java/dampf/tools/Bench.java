package dampf.tools;

import dampf.emu.Snes;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs Blubber Blasen's compiler benchmark ROM (bench.c) in dampf's emulator and prints what the
 * game's bench.lua writes from Mesen: one line per tick, "tick state clocks hashes", where clocks
 * are the master cycles between the start ($21FC) and end ($21FF) markers and hashes the three
 * checksums ($21FD/$21FE pairs). The output can go straight into the game's report.py.
 */
public final class Bench {
    private Bench() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: dampf bench <bench.sfc>");
            System.exit(2);
        }
        Snes snes = Snes.load(Path.of(args[0]));
        StringBuilder out = new StringBuilder();
        int[] state = new int[1];
        long[] t0 = new long[1];
        long[] clocks = new long[1];
        int[] lo = new int[1];
        int[] tick = new int[1];
        StringBuilder hashes = new StringBuilder();
        snes.onPortWrite((port, value, time) -> {
            switch (port) {
                case 0x21FC -> {
                    state[0] = value;
                    t0[0] = time;
                }
                case 0x21FF -> clocks[0] = time - t0[0];
                case 0x21FD -> lo[0] = value;
                case 0x21FE -> hashes.append(String.format("%04X", lo[0] + 256 * value));
                case 0x21FA -> {
                    out.append(tick[0]++).append(' ').append(state[0]).append(' ').append(clocks[0]).append(' ').append(hashes).append('\n');
                    hashes.setLength(0);
                }
                default -> { }
            }
        });
        boolean done = snes.run(Long.MAX_VALUE);
        System.out.print(out);
        if (!done) {
            System.err.println("the benchmark did not finish");
            System.exit(1);
        }
    }
}
