package dampf.emu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Just enough of a Super Nintendo to run dampf's programs: the 65816 ({@link Cpu65816}), a LoROM
 * cartridge, 128 KB of WRAM with its mirrors, and the master-cycle timing of the bus. No picture,
 * no sound output, no DMA, no interrupts.
 *
 * <p>Timing, in master cycles (21.477 MHz on NTSC): an access costs 8 for WRAM, SlowROM and most of
 * the address space, 6 for I/O registers at $2000-$3FFF and $4200-$5FFF (and FastROM once
 * MEMSEL is set), 12 for the old joypad registers at $4000-$41FF; an internal CPU cycle costs 6.
 * DRAM refresh halts the CPU for 40 master cycles per scanline of 1364.
 *
 * <p>Of the picture only its timing: 262 lines a frame (NTSC), the vertical blank from line 225
 * ({@code HVBJOY} bit 7), the automatic joypad read in its first three lines (bit 0, when
 * {@code NMITIMEN} bit 0 is set), the line counter latched by {@code SLHV} and read at
 * {@code OPVCT}, and the joypads at {@code $4218}-{@code $421B} from {@link #setPads}. Enough for
 * the kit's main loop (lib/snes/main.c), which waits for the vertical blank by polling.
 *
 * <p>The sound side is {@link Apu}: the ports $2140-$2143 (mirrored up to $217F), the audio RAM,
 * the SPC700's boot code and the kit's driver.
 *
 * <p>Writes to the B-bus ports $21FA-$21FF, which no hardware uses, are passed to a
 * {@link PortListener}: dampf's runtime writes 1 to $21FB when the program is done, the game's
 * benchmark marks its ticks at $21FA, and the kit's checks use $21FC and $21FD
 * ({@link ExampleCheck}).
 */
public final class Snes implements Bus {
    /** NTSC: master cycles per scanline, of which the CPU loses 40 to DRAM refresh. */
    private static final int LINE = 1364;
    private static final int REFRESH = 40;

    /** Notified of writes to $21FA-$21FF. */
    public interface PortListener {
        void write(int port, int value, long masterClock);
    }

    private final byte[] rom;
    private final byte[] wram = new byte[128 * 1024];
    private final Cpu65816 cpu = new Cpu65816(this);
    private final Apu apu = new Apu();
    private PortListener ports = (p, v, t) -> { };
    private boolean done;
    private boolean fastRom;
    private long master;
    private long lineClock;

    // the picture's timing (NTSC): the line the PPU is at, the counter latched by SLHV
    private static final int LINES = 262;
    private static final int VBLANK_FIRST_LINE = 225;
    private int scanline;
    private long vblanks;
    private int latchedLine;
    private boolean opvctHigh;
    private boolean autoJoypad;
    private final int[] pads = new int[2];

    // the CPU's multiplier and divider ($4202-$4206 -> $4214-$4217), results without the hardware's delay
    private int mulA = 0xFF;
    private int dividend = 0xFFFF;
    private int quotient;
    private int product;

    public Snes(byte[] rom) {
        this.rom = rom;
        this.hiRom = isHiRom(rom);
    }

    /**
     * HiROM (64 KB banks: banks $C0-$FF and $40-$7D all ROM, the upper halves of $00-$3F and
     * $80-$BF too) if the cartridge header at $FFC0 says so (map mode $21 or $31), as for the
     * Calypsi benchmark ROM; else LoROM, as dampf builds.
     */
    static boolean isHiRom(byte[] rom) {
        return rom.length > 0xFFD5 && ((rom[0xFFD5] & 0xEF) == 0x21);
    }

    private final boolean hiRom;

    public static Snes load(Path sfc) throws IOException {
        return new Snes(Files.readAllBytes(sfc));
    }

    public Cpu65816 cpu() {
        return cpu;
    }

    /** The sound side: the SPC700, its RAM and the DSP's registers. */
    public Apu apu() {
        return apu;
    }

    public void onPortWrite(PortListener listener) {
        this.ports = listener;
    }

    /** The joypads from now on, in the SNES's bit layout ($4218 is pad 1). */
    public void setPads(int pad1, int pad2) {
        pads[0] = pad1 & 0xFFFF;
        pads[1] = pad2 & 0xFFFF;
    }

    /** The line the picture is at, 0..261. */
    public int scanline() {
        return scanline;
    }

    /** How many vertical blanks have begun since power-on. */
    public long vblanks() {
        return vblanks;
    }

    /** Whether the picture is in its vertical blank now. */
    public boolean inVblank() {
        return scanline >= VBLANK_FIRST_LINE;
    }

    /** A byte of the address space as the CPU sees it, without time passing or side effects. */
    public int peek(int address) {
        int bank = (address >> 16) & 0xFF;
        int offset = address & 0xFFFF;
        if (bank == 0x7E || bank == 0x7F) {
            return wram[(bank - 0x7E) << 16 | offset] & 0xFF;
        }
        if (hiRom && ((bank & 0x40) != 0 || offset >= 0x8000)) {
            return rom[(((bank & 0x3F) << 16) | offset) % rom.length] & 0xFF;
        }
        if (offset >= 0x8000) {
            return rom[(((bank & 0x7F) << 15) | (offset & 0x7FFF)) % rom.length] & 0xFF;
        }
        if ((bank & 0x40) == 0 && offset < 0x2000) {
            return wram[offset] & 0xFF;
        }
        return 0;
    }

    /** Master cycles since power-on. */
    public long masterClock() {
        return master;
    }

    public boolean done() {
        return done;
    }

    /** Ends {@link #run} after the current instruction (a check that has seen enough). */
    public void stop() {
        done = true;
    }

    /** WRAM, bank $7E and $7F as one array ($7E0000 is index 0). */
    public byte[] wram() {
        return wram;
    }

    /** Resets and runs until the program writes $21FB or the master clock passes {@code limit}. */
    public boolean run(long limit) {
        return run(limit, null);
    }

    /** Called before every instruction with its 24-bit address (for profiles). */
    public interface StepListener {
        void before(int address);
    }

    public boolean run(long limit, StepListener steps) {
        cpu.reset();
        while (!done && master < limit) {
            if (steps != null) {
                steps.before(cpu.pbr << 16 | cpu.pc);
            }
            cpu.step();
        }
        return done;
    }

    // ------------------------------------------------------------------ the bus

    private void tick(int cycles) {
        master += cycles;
        lineClock += cycles;
        if (lineClock >= LINE - REFRESH) {
            master += REFRESH;
            lineClock -= LINE - REFRESH;
            scanline = scanline + 1 == LINES ? 0 : scanline + 1;
            if (scanline == VBLANK_FIRST_LINE) {
                vblanks++;
            }
        }
    }

    /** Master cycles of one access, by address (fullsnes, "SNES Memory Map"). */
    private int speed(int address) {
        int bank = address >> 16;
        int offset = address & 0xFFFF;
        if ((bank & 0x40) != 0) {
            // $40-$7F and $C0-$FF: ROM or WRAM all through
            return bank >= 0xC0 && fastRom ? 6 : 8;
        }
        if (offset < 0x2000) {
            return 8;
        }
        if (offset < 0x4000) {
            return 6;
        }
        if (offset < 0x4200) {
            return 12;
        }
        if (offset < 0x6000) {
            return 6;
        }
        if (offset < 0x8000) {
            return 8;
        }
        return bank >= 0x80 && fastRom ? 6 : 8;
    }

    @Override
    public int read(int address) {
        tick(speed(address));
        int bank = address >> 16;
        int offset = address & 0xFFFF;
        if (bank == 0x7E || bank == 0x7F) {
            return wram[(bank - 0x7E) << 16 | offset] & 0xFF;
        }
        if (hiRom && ((bank & 0x40) != 0 || offset >= 0x8000)) {
            return rom[(((bank & 0x3F) << 16) | offset) % rom.length] & 0xFF; // HiROM
        }
        if (offset >= 0x8000) {
            return rom[(((bank & 0x7F) << 15) | (offset & 0x7FFF)) % rom.length] & 0xFF; // LoROM
        }
        if ((bank & 0x40) == 0) {
            // banks $00-$3F and $80-$BF: the first 8 KB of WRAM, then the I/O registers
            return offset < 0x2000 ? wram[offset] & 0xFF : io(offset);
        }
        return 0; // open bus, not modelled
    }

    private int io(int offset) {
        if (offset >= 0x2140 && offset <= 0x217F) {
            return apu.cpuRead(offset & 3, master);
        }
        return switch (offset) {
            case 0x2137 -> {
                latchedLine = scanline;     // SLHV: latch the counters
                yield 0;
            }
            case 0x213D -> {
                int v = opvctHigh ? latchedLine >> 8 : latchedLine & 0xFF;
                opvctHigh = !opvctHigh;
                yield v;
            }
            case 0x213F -> {
                opvctHigh = false;          // STAT78: the byte order starts again
                yield 0;
            }
            case 0x4212 -> (scanline >= VBLANK_FIRST_LINE ? 0x80 : 0)
                    | (autoJoypad && scanline >= VBLANK_FIRST_LINE && scanline < VBLANK_FIRST_LINE + 3 ? 1 : 0);
            case 0x4218 -> pads[0] & 0xFF;
            case 0x4219 -> pads[0] >> 8;
            case 0x421A -> pads[1] & 0xFF;
            case 0x421B -> pads[1] >> 8;
            case 0x4214 -> quotient & 0xFF;
            case 0x4215 -> quotient >> 8;
            case 0x4216 -> product & 0xFF;
            case 0x4217 -> product >> 8;
            default -> 0;
        };
    }

    @Override
    public void write(int address, int value) {
        tick(speed(address));
        int bank = address >> 16;
        int offset = address & 0xFFFF;
        if (bank == 0x7E || bank == 0x7F) {
            wram[(bank - 0x7E) << 16 | offset] = (byte) value;
            return;
        }
        if ((bank & 0x40) != 0) {
            return; // ROM
        }
        if (offset < 0x2000) {
            wram[offset] = (byte) value;
            return;
        }
        if (offset >= 0x2140 && offset <= 0x217F) {
            apu.cpuWrite(offset & 3, value, master);
            return;
        }
        if (offset >= 0x21FA && offset <= 0x21FF) {
            if (offset == 0x21FB) {
                done = true;
            }
            ports.write(offset, value, master);
            return;
        }
        switch (offset) {
            case 0x420D -> fastRom = (value & 1) != 0;
            case 0x4200 -> autoJoypad = (value & 1) != 0;
            case 0x4202 -> mulA = value;
            case 0x4203 -> {
                product = mulA * value;
                quotient = value; // the hardware leaves the second factor in the quotient register
            }
            case 0x4204 -> dividend = (dividend & 0xFF00) | value;
            case 0x4205 -> dividend = (dividend & 0x00FF) | value << 8;
            case 0x4206 -> {
                if (value == 0) {
                    quotient = 0xFFFF;
                    product = dividend;
                } else {
                    quotient = dividend / value;
                    product = dividend % value;
                }
            }
            default -> { } // PPU and other registers: not modelled
        }
    }

    @Override
    public void idle() {
        tick(6);
    }
}
