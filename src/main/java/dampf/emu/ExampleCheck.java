package dampf.emu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plays a game of the kit in dampf's emulator with the input of a {@code check.txt} (the format
 * of the examples' checks: {@code ticks N}, {@code press T1 T2 buttons...}, {@code budget T1 T2}: no
 * tick from T1 to T2 - 1 may take more than a frame; {@code shot} and {@code splash} lines are for
 * the picture and ignored here) and records the checksum of every
 * tick, as the desktop's check (lib/desktop) and Mesen's (dev/example_check.lua) do: the bytes the
 * game names with {@code sdevkit_dbg_hash}, then the sprite table, FNV-1a with 32 bits.
 *
 * <p>The kit's main loop (lib/snes/main.c) tells the checks where they are: it writes 1 to $21FD
 * after {@code game_init} and 0 after each {@code game_tick}; {@code sdevkit_dbg_hash} writes the
 * address and the size of what it names to $21FC (five bytes), and the memory is read here, so
 * hashing costs the game no time. The pad of tick T is set when tick T - 1 ends, before the main
 * loop reads it.
 *
 * <p>The emulator has no PPU and no DMA (a DMA does not take the CPU's time here), so the frames
 * per tick are close to Mesen's, not equal.
 */
public final class ExampleCheck {
    private static final long FNV_START = 2166136261L;
    private static final long FNV_PRIME = 16777619L;
    /** Master cycles of an NTSC frame: 262 lines of 1364. */
    public static final long FRAME = 262L * 1364;

    private static final Map<String, Integer> BUTTONS = Map.ofEntries(
            Map.entry("b", 0x8000), Map.entry("y", 0x4000), Map.entry("select", 0x2000), Map.entry("start", 0x1000),
            Map.entry("up", 0x0800), Map.entry("down", 0x0400), Map.entry("left", 0x0200), Map.entry("right", 0x0100),
            Map.entry("a", 0x0080), Map.entry("x", 0x0040), Map.entry("l", 0x0020), Map.entry("r", 0x0010));

    /** One {@code press} line: the buttons held on pad 1 for ticks {@code from} to {@code to - 1}. */
    public record Press(long from, long to, int buttons) {}

    /** A {@code budget FROM TO} line: no tick from {@code from} to {@code to - 1} may take more than a frame. */
    public record Budget(long from, long to) {}

    /** The input of a check: how many ticks to play, what is pressed when, and the budgets. */
    public record Input(long ticks, List<Press> presses, List<Budget> budgets) {
        public int pad(long tick) {
            int p = 0;
            for (Press press : presses) {
                if (tick >= press.from && tick < press.to) {
                    p |= press.buttons;
                }
            }
            return p;
        }
    }

    /**
     * What a run gave: the checksum after each tick, the frames the ticks took (from the end of
     * the splash screen), the master cycles of the slowest tick, and the master clock at the end
     * of each tick.
     */
    /**
     * {@code vblankAtEnd}: for each tick, how many vertical blanks had begun when it ended, times
     * two, plus one when it ended inside one.
     */
    public record Result(List<Long> hashes, long frames, long slowestTick, List<Long> tickEnds, List<Long> vblankAtEnd) {
        public double framesPerTick() {
            return hashes.isEmpty() ? 0 : (double) frames / hashes.size();
        }

        /** The most frames any {@code window} ticks in a row took, and the first tick of them. */
        public long[] slowestWindow(int window) {
            long most = 0;
            long at = 0;
            for (int i = window; i < tickEnds.size(); i++) {
                long d = tickEnds.get(i) - tickEnds.get(i - window);
                if (d > most) {
                    most = d;
                    at = i - window + 1;
                }
            }
            return new long[] { most, at };
        }

        /**
         * The ticks of a budget that took more than a frame. A tick starts at the first vertical
         * blank after the tick before it ended (the main loop waits for one to begin; if that tick
         * ended inside one, it waits for the next), and it missed its frame when another one began
         * before it ended. (Measured from the tick before, a short tick, which ends early in its
         * frame, makes the next normal one look a frame and a half long.)
         */
        public List<Long> overBudget(Budget b) {
            List<Long> over = new ArrayList<>();
            for (long t = Math.max(1, b.from()); t < b.to() && t < vblankAtEnd.size(); t++) {
                long before = vblankAtEnd.get((int) t - 1), end = vblankAtEnd.get((int) t);
                long start = (before >> 1) + ((before & 1) != 0 ? 2 : 1);   /* the vertical blank it began at */
                if ((end >> 1) > start) {
                    over.add(t);
                }
            }
            return over;
        }

        /** The lines of the desktop's hashes.txt: "tick hash", the hash in eight hex digits. */
        public List<String> lines() {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < hashes.size(); i++) {
                l.add(i + " " + String.format("%08x", hashes.get(i)));
            }
            return l;
        }
    }

    private ExampleCheck() {
    }

    public static Input parse(Path checkTxt) throws IOException {
        long ticks = 600;
        List<Press> presses = new ArrayList<>();
        List<Budget> budgets = new ArrayList<>();
        for (String line : Files.readAllLines(checkTxt)) {
            String[] w = line.strip().split("\\s+");
            if (w[0].equals("ticks")) {
                ticks = Long.parseLong(w[1]);
            } else if (w[0].equals("budget")) {
                budgets.add(new Budget(Long.parseLong(w[1]), Long.parseLong(w[2])));
            } else if (w[0].equals("press")) {
                int b = 0;
                for (int i = 3; i < w.length; i++) {
                    Integer bit = BUTTONS.get(w[i]);
                    if (bit == null) {
                        throw new IOException(checkTxt + ": unknown button '" + w[i] + "'");
                    }
                    b |= bit;
                }
                presses.add(new Press(Long.parseLong(w[1]), Long.parseLong(w[2]), b));
            }
        }
        return new Input(ticks, List.copyOf(presses), List.copyOf(budgets));
    }

    /** Plays {@code rom} until {@code input.ticks()} ticks are done or {@code limitFrames} frames passed. */
    public static Result run(byte[] rom, Input input, long limitFrames) throws IOException {
        Snes snes = new Snes(rom);
        List<Long> hashes = new ArrayList<>();
        List<Long> ends = new ArrayList<>();
        List<Long> vblankAtEnd = new ArrayList<>();
        long[] hash = { FNV_START };
        int[] bytes = new int[5];
        int[] count = { 0 };
        long[] started = { -1 };
        long[] tickStart = { 0 };
        long[] slowest = { 0 };
        snes.onPortWrite((port, value, clock) -> {
            if (port == 0x21FC) {
                bytes[count[0]++] = value & 0xFF;
                if (count[0] == 5) {
                    count[0] = 0;
                    int address = bytes[0] | bytes[1] << 8 | bytes[2] << 16;
                    int size = bytes[3] | bytes[4] << 8;
                    long h = hash[0];
                    for (int i = 0; i < size; i++) {
                        h = ((h ^ snes.peek(address + i)) * FNV_PRIME) & 0xFFFFFFFFL;
                    }
                    hash[0] = h;
                }
            } else if (port == 0x21FD) {
                if (value == 1) {
                    started[0] = clock;
                    tickStart[0] = clock;
                    hash[0] = FNV_START;
                } else if (started[0] >= 0) {
                    hashes.add(hash[0]);
                    ends.add(clock);
                    vblankAtEnd.add(snes.vblanks() * 2 + (snes.inVblank() ? 1 : 0));
                    hash[0] = FNV_START;
                    slowest[0] = Math.max(slowest[0], clock - tickStart[0]);
                    tickStart[0] = clock;
                    if (hashes.size() >= input.ticks()) {
                        snes.stop();
                    }
                }
                snes.setPads(started[0] >= 0 ? input.pad(hashes.size()) : 0, 0);
            }
        });
        snes.run(limitFrames * FRAME);
        if (hashes.size() < input.ticks()) {
            throw new IOException("played " + hashes.size() + " of " + input.ticks() + " ticks within " + limitFrames
                    + " frames" + (started[0] < 0 ? " (game_init never returned: the splash screen or the game hangs?)" : ""));
        }
        return new Result(List.copyOf(hashes), (snes.masterClock() - started[0]) / FRAME, slowest[0], List.copyOf(ends),
                List.copyOf(vblankAtEnd));
    }
}
