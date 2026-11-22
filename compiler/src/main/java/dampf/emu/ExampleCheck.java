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
 * tick from T1 to T2 - 1 may take more than a frame; {@code sram H}: the save RAM at power-on, its
 * bytes in hex from the first on, the rest zero; {@code shot} and {@code splash} lines are for
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
 * <p>It also records the sound as the desktop's check writes it to sound.txt (lib/desktop): every
 * write the SPC700 makes to a DSP register, as "init" (in game_init) or the tick after which it
 * came, the register and the value in hex; after game_init's writes the line "aram H": the FNV-1a
 * of the bytes written into the audio RAM from $0200 on, each its address and value, in the order
 * written ({@link Apu#uploadHash}).
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

    /**
     * The input of a check: how many ticks to play, what is pressed when, and the budgets; and of
     * a recording (lib/desktop), the checksum it recorded for each tick ({@code hash T H} lines,
     * -1 where there is none) and its marks ({@code mark T}). The joypad of every tick is worked
     * out once: a recording has thousands of press lines.
     */
    public record Input(long ticks, List<Press> presses, List<Budget> budgets, long[] recorded, List<Long> marks, int[] pads,
                        byte[] sram) {
        public Input(long ticks, List<Press> presses, List<Budget> budgets) {
            this(ticks, presses, budgets, new long[0], List.of(), pads(ticks, presses), new byte[0]);
        }

        private static int[] pads(long ticks, List<Press> presses) {
            long n = ticks;
            for (Press p : presses) {
                n = Math.max(n, p.to);
            }
            int[] pads = new int[(int) n];
            for (Press p : presses) {
                for (long t = Math.max(0, p.from); t < p.to; t++) {
                    pads[(int) t] |= p.buttons;
                }
            }
            return pads;
        }

        public int pad(long tick) {
            return tick >= 0 && tick < pads.length ? pads[(int) tick] : 0;
        }

        /** The checksum recorded for this tick, or -1. */
        public long recorded(long tick) {
            return tick >= 0 && tick < recorded.length ? recorded[(int) tick] : -1;
        }
    }

    /**
     * What a run gave: the checksum after each tick, the frames the ticks took (from the end of
     * the splash screen), the master cycles of the slowest tick, the master clock at the end of
     * each tick, and for each tick how many vertical blanks had begun when it ended, times two,
     * plus one when it ended inside one ({@code vblankAtEnd}), and the lines of the sound
     * ({@code sound}, as the desktop's sound.txt).
     */
    public record Result(List<Long> hashes, long frames, long slowestTick, List<Long> tickEnds, List<Long> vblankAtEnd,
                         List<String> sound) {
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

    /**
     * Plays a check.txt or a recording of the desktop (lib/desktop, F11) in dampf's emulator and
     * compares the checksum of every tick with the one recorded:
     * <pre>
     *   java -cp 'build/install/superdevkit/java/*' dampf.emu.ExampleCheck rom.sfc recording.txt [hashes.txt [sound.txt]]
     * </pre>
     * prints the first tick that differs (exit status 1) or how many ticks were the same, and writes
     * the checksums in the desktop's format to hashes.txt and the ROM's DSP writes in the format of
     * the desktop's sound.txt to sound.txt, when they are named.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 4) {
            System.err.println("usage: ExampleCheck rom.sfc recording.txt [hashes.txt [sound.txt]]");
            System.exit(2);
        }
        Input input = parse(Path.of(args[1]));
        Result r = run(Files.readAllBytes(Path.of(args[0])), input, 40 * input.ticks() + 2000);
        if (args.length >= 3) {
            Files.write(Path.of(args[2]), r.lines());
        }
        if (args.length == 4) {
            Files.write(Path.of(args[3]), r.sound());
        }
        long compared = 0;
        for (int t = 0; t < r.hashes().size(); t++) {
            long want = input.recorded(t);
            if (want < 0) {
                continue;
            }
            compared++;
            if (want != r.hashes().get(t)) {
                System.out.printf("tick %d has checksum %08x on the SNES, the recording %08x%n", t, r.hashes().get(t), want);
                System.exit(1);
            }
        }
        System.out.printf("%d ticks played, %d checksums the same as the recording%n", r.hashes().size(), compared);
    }

    public static Input parse(Path checkTxt) throws IOException {
        long ticks = 600;
        List<Press> presses = new ArrayList<>();
        List<Budget> budgets = new ArrayList<>();
        Map<Long, Long> recorded = new java.util.HashMap<>();
        List<Long> marks = new ArrayList<>();
        byte[] sram = new byte[0];
        for (String line : Files.readAllLines(checkTxt)) {
            String[] w = line.strip().split("\\s+");
            if (w[0].equals("sram") && w.length == 2) {
                sram = java.util.HexFormat.of().parseHex(w[1]);
            } else if (w[0].equals("seed") || w[0].equals("set")) {
                throw new IOException(checkTxt + ": its '" + w[0] + "' line starts the game in a way only the desktop can"
                        + " (--seed, --set): on the SNES it starts with seed 0 and has no debug registrations");
            } else if (w[0].equals("hash")) {
                recorded.put(Long.parseLong(w[1]), Long.parseLong(w[2], 16));
            } else if (w[0].equals("mark")) {
                marks.add(Long.parseLong(w[1]));
            } else if (w[0].equals("ticks")) {
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
        long[] byTick = new long[recorded.isEmpty() ? 0 : (int) (java.util.Collections.max(recorded.keySet()) + 1)];
        java.util.Arrays.fill(byTick, -1);
        recorded.forEach((t, h) -> byTick[t.intValue()] = h);
        Input plain = new Input(ticks, List.copyOf(presses), List.copyOf(budgets));
        return new Input(ticks, plain.presses(), plain.budgets(), byTick, List.copyOf(marks), plain.pads(), sram);
    }

    /** Plays {@code rom} until {@code input.ticks()} ticks are done or {@code limitFrames} frames passed. */
    public static Result run(byte[] rom, Input input, long limitFrames) throws IOException {
        Snes snes = new Snes(rom);
        if (input.sram().length > snes.sram().length) {
            throw new IOException("the check starts with " + input.sram().length + " bytes of save RAM, the ROM has "
                    + snes.sram().length);
        }
        System.arraycopy(input.sram(), 0, snes.sram(), 0, input.sram().length);
        List<Long> hashes = new ArrayList<>();
        List<Long> ends = new ArrayList<>();
        List<Long> vblankAtEnd = new ArrayList<>();
        long[] hash = { FNV_START };
        int[] bytes = new int[5];
        int[] count = { 0 };
        long[] started = { -1 };
        long[] tickStart = { 0 };
        long[] slowest = { 0 };
        List<String> sound = new ArrayList<>();
        snes.apu().onDspWrite((reg, value) -> sound.add(
                (started[0] < 0 ? "init" : Long.toString(hashes.size() - 1)) + String.format(" %02x %02x", reg, value)));
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
                    sound.add(String.format("aram %08x", snes.apu().uploadHash()));
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
                List.copyOf(vblankAtEnd), List.copyOf(sound));
    }
}
