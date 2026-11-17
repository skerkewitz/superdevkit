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
        if (!(args.length == 1 || args.length == 5 && args[1].equals("--profile"))) {
            System.err.println("usage: dampf bench <bench.sfc> [--profile counts.txt fromTick toTick]");
            System.exit(2);
        }
        Snes snes = Snes.load(Path.of(args[0]));
        // the profile: how often each instruction in ROM ran between two ticks, as tools/profile.lua writes it
        long[] counts = args.length == 5 ? new long[0x400000] : null;
        int from = args.length == 5 ? Integer.parseInt(args[3]) : 0;
        int to = args.length == 5 ? Integer.parseInt(args[4]) : 0;
        long[] window = new long[2];
        boolean[] counting = {from == 0};
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
                    if (counts != null) {
                        if (tick[0] + 1 == from) {
                            counting[0] = true;
                            window[0] = time;
                        } else if (tick[0] + 1 == to) {
                            counting[0] = false;
                            window[1] = time;
                        }
                    }
                    out.append(tick[0]++).append(' ').append(state[0]).append(' ').append(clocks[0]).append(' ').append(hashes).append('\n');
                    hashes.setLength(0);
                }
                default -> { }
            }
        });
        // far more than the benchmark ever needs (about 15,000 frames), so a program that hangs fails
        boolean done = snes.run(200_000L * 357_366, counts == null ? null : address -> {
            if (counting[0] && (address & 0xFFFF) >= 0x8000) {
                counts[(address >> 16 & 0x7F) * 0x8000 + (address & 0x7FFF)]++;
            }
        });
        if (counts != null) {
            StringBuilder p = new StringBuilder("clocks " + (window[1] - window[0]) + "\n");
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) {
                    p.append(i).append(' ').append(counts[i]).append('\n');
                }
            }
            java.nio.file.Files.writeString(Path.of(args[2]), p);
        }
        System.out.print(out);
        if (!done) {
            System.err.println("the benchmark did not finish");
            System.exit(1);
        }
    }
}
