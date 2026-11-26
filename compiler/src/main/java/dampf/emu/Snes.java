package dampf.emu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Just enough of a Super Nintendo to run dampf's programs: the 65816 ({@link Cpu65816}), a LoROM
 * cartridge, 128 KB of WRAM with its mirrors, and the master-cycle timing of the bus. No picture,
 * no sound output; of DMA only the time it takes.
 *
 * <p>Timing, in master cycles (21.477 MHz on NTSC): an access costs 8 for WRAM, SlowROM and most of
 * the address space, 6 for I/O registers at $2000-$3FFF and $4200-$5FFF (and FastROM once
 * MEMSEL is set), 12 for the old joypad registers at $4000-$41FF; an internal CPU cycle costs 6.
 * DRAM refresh halts the CPU for 40 master cycles per scanline of 1364. A general DMA ({@code
 * MDMAEN}, $420B) halts it for 8 master cycles a byte, 8 a channel and 8 more (fullsnes, "SNES DMA
 * Transfer"), by the byte counts written to {@code DASn}; what it copies is not modelled. HDMA is
 * not modelled.
 *
 * <p>Of the picture only its timing: 262 lines a frame (NTSC), the vertical blank from line 225
 * ({@code HVBJOY} bit 7), the horizontal blank from dot 274 to dot 1 (bit 6, as roughly as the
 * horizontal position below: what the game's IRQ waits for, sdevkit/irq.h), the automatic joypad
 * read in the vertical blank's first three lines (bit 0, when
 * {@code NMITIMEN} bit 0 is set), the line counter latched by {@code SLHV} and read at
 * {@code OPVCT}, and the joypads at {@code $4218}-{@code $421B} from {@link #setPads}. Enough for
 * the kit's main loop (lib/snes/main.c), which waits for the vertical blank by polling.
 *
 * <p>Interrupts, as {@code NMITIMEN} ($4200) enables them: the NMI at the start of the vertical
 * blank (line 225; {@code RDNMI} $4210 bit 7 is set then, cleared by reading it or at line 0, and
 * enabling the NMI while it is set gives one at once), and the timer IRQ ({@code TIMEUP} $4211 bit 7,
 * held until it is read or the timer is switched off): at the start of line {@code VTIME}
 * ($4209/$420A) with the vertical timer (or at once, when a write makes the line VTIME's in the
 * middle of it), at dot {@code HTIME} ($4207/$4208) of every line with
 * the horizontal one, at that dot of line {@code VTIME} with both. A dot is 4 master cycles from
 * the start of the line, the DRAM refresh counted at the line's end, so a horizontal position is
 * only about right (on the console the refresh lies near dot 134). The CPU takes an interrupt
 * between two instructions ({@link Cpu65816#interrupt}), the IRQ only while its I flag is clear.
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
    /** HVBJOY's horizontal blank, bit 6: from dot 274 of a line to dot 1 of the next (fullsnes). */
    private static final int HBLANK_FROM = 4 * 274;
    private static final int HBLANK_TO = 4;

    /** Notified of writes to $21FA-$21FF. */
    public interface PortListener {
        void write(int port, int value, long masterClock);
    }

    private final byte[] rom;
    private final byte[] wram = new byte[128 * 1024];
    /** The cartridge's save RAM, as the LoROM header announces it (empty without), at $70:0000. */
    private final byte[] sram;
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

    // the interrupts: NMITIMEN's NMI bit and timer mode (0 off, 1 H, 2 V, 3 HV), the timers'
    // positions, RDNMI's and TIMEUP's flags, and an NMI the CPU has still to take
    private boolean nmiEnabled;
    private int timerMode;
    private int htime = 0x1FF;
    private int vtime = 0x1FF;
    private boolean nmiFlag;
    private boolean irqFlag;
    /** The vertical timer's condition, V = VTIME: the IRQ comes when it becomes true. */
    private boolean vMatch;
    private boolean nmiPending;
    /** The native-mode vectors of the NMI and the IRQ. */
    private static final int NMI_VECTOR = 0xFFEA;
    private static final int IRQ_VECTOR = 0xFFEE;
    private final int[] pads = new int[2];
    /** The DMA channels' byte counts, DASn ($43n5/$43n6): 0 is 64 KB. */
    private final int[] das = new int[8];

    // the CPU's multiplier and divider ($4202-$4206 -> $4214-$4217). The multiplier adds one bit of
    // its second factor per CPU cycle, lowest first, so a read before the 8th cycle gets a partial
    // product, as on the hardware; the divider's results have no delay
    private int mulA = 0xFF;
    private int dividend = 0xFFFF;
    private int quotient;
    private int product;
    private long cpuCycle;
    private long mulClock;
    private int mulSteps;
    private int mulBits;
    private int mulShift;

    public Snes(byte[] rom) {
        this.rom = rom;
        this.hiRom = isHiRom(rom);
        this.sram = new byte[sramSize(rom, hiRom)];
    }

    /**
     * The save RAM a LoROM header announces: a cartridge type with RAM ($02: ROM, RAM and battery,
     * or $01) and the RAM size as 2^n KB at $FFD8; 0 bytes otherwise.
     */
    static int sramSize(byte[] rom, boolean hiRom) {
        if (hiRom || rom.length < 0x8000) {
            return 0;
        }
        int type = rom[0x7FD6] & 0xFF;
        int size = rom[0x7FD8] & 0xFF;
        return (type == 0x01 || type == 0x02) && size > 0 && size <= 5 ? 1024 << size : 0;
    }

    /** The save RAM, to fill before the run (a recording's start) or to read after it. */
    public byte[] sram() {
        return sram;
    }

    /** The index into the save RAM for an address of banks $70-$7D (and $F0-$FD), below $8000; -1 if none. */
    private int sramIndex(int bank, int offset) {
        int b = bank & 0x7F;
        if (sram.length == 0 || b < 0x70 || b > 0x7D || offset >= 0x8000 || hiRom) {
            return -1;
        }
        return ((b - 0x70) << 15 | offset) % sram.length;
    }

    /**
     * HiROM (64 KB banks: banks $C0-$FF and $40-$7D all ROM, the upper halves of $00-$3F and
     * $80-$BF too) if the cartridge header at $FFC0 says so (map mode $21 or $31), as for the
     * Calypsi benchmark ROM; else LoROM, as dampf builds. A header counts when its checksum and
     * complement agree: in a LoROM of more than 64 KB, $FFD5 is any byte of the game's (a
     * program of dampf's was taken for HiROM when its data put $21 there). When neither header
     * or both agree, the map modes decide, LoROM's first.
     */
    static boolean isHiRom(byte[] rom) {
        boolean lo = header(rom, 0x7FC0, 0x20), hi = header(rom, 0xFFC0, 0x21);
        if (lo != hi) {
            return hi;
        }
        return rom.length > 0xFFD5 && (rom[0xFFD5] & 0xEF) == 0x21 && (rom[0x7FD5] & 0xEF) != 0x20;
    }

    /** A header at {@code at} with this map mode (FastROM's bit aside), its checksum and complement agreeing. */
    private static boolean header(byte[] rom, int at, int mode) {
        if (rom.length < at + 0x40) {
            return false;
        }
        int complement = (rom[at + 0x1C] & 0xFF) | (rom[at + 0x1D] & 0xFF) << 8;
        int checksum = (rom[at + 0x1E] & 0xFF) | (rom[at + 0x1F] & 0xFF) << 8;
        return (rom[at + 0x15] & 0xEF) == mode && (complement ^ checksum) == 0xFFFF;
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
        int s = sramIndex(bank, offset);
        if (s >= 0) {
            return sram[s] & 0xFF;
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
            if (nmiPending) {
                nmiPending = false;
                cpu.interrupt(cpu.e ? 0xFFFA : NMI_VECTOR);
            } else if (irqFlag && !cpu.i) {
                cpu.interrupt(cpu.e ? 0xFFFE : IRQ_VECTOR);
            }
            if (steps != null) {
                steps.before(cpu.pbr << 16 | cpu.pc);
            }
            cpu.step();
        }
        return done;
    }

    /**
     * Runs on from the current state, without a reset, until the master clock passes {@code limit}
     * or the listener calls {@link #stop}: then the instruction it was called for does not run.
     * Returns whether the listener stopped it. For running single functions (the check of an
     * assembly function against its C body).
     */
    public boolean resume(long limit, StepListener steps) {
        done = false;
        while (master < limit) {
            steps.before(cpu.pbr << 16 | cpu.pc);
            if (done) {
                return true;
            }
            cpu.step();
        }
        return false;
    }

    // ------------------------------------------------------------------ the bus

    private void tick(int cycles) {
        long before = lineClock;
        master += cycles;
        lineClock += cycles;
        int dot = 4 * htime;
        if ((timerMode & 1) != 0 && before < dot && lineClock >= dot && (timerMode == 1 || scanline == vtime)) {
            irqFlag = true;
        }
        if (lineClock >= LINE - REFRESH) {
            master += REFRESH;
            lineClock -= LINE - REFRESH;
            scanline = scanline + 1 == LINES ? 0 : scanline + 1;
            if (scanline == VBLANK_FIRST_LINE) {
                vblanks++;
                nmiFlag = true;
                nmiPending |= nmiEnabled;
            } else if (scanline == 0) {
                nmiFlag = false;
            }
            if ((timerMode & 1) != 0 && dot <= lineClock && (timerMode == 1 || scanline == vtime)) {
                irqFlag = true;
            }
            verticalTimer();
        }
    }

    /**
     * The vertical timer alone: its condition V = VTIME holds for the whole line, and the IRQ comes
     * when it becomes true, at the start of the line or at a write of VTIME or NMITIMEN that makes
     * it true in the middle of one (as in Mesen).
     */
    private void verticalTimer() {
        boolean match = timerMode == 2 && scanline == vtime;
        irqFlag |= match && !vMatch;
        vMatch = match;
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

    /** Runs the multiplier up to the CPU cycle {@code until}. */
    private void runMultiplier(long until) {
        while (mulSteps > 0 && mulClock < until) {
            mulClock++;
            mulSteps--;
            if ((mulBits & 1) != 0) {
                product = (product + mulShift) & 0xFFFF;
            }
            mulBits >>= 1;
            mulShift <<= 1;
        }
    }

    @Override
    public int read(int address) {
        cpuCycle++;
        tick(speed(address));
        int bank = address >> 16;
        int offset = address & 0xFFFF;
        if (bank == 0x7E || bank == 0x7F) {
            return wram[(bank - 0x7E) << 16 | offset] & 0xFF;
        }
        int s = sramIndex(bank, offset);
        if (s >= 0) {
            return sram[s] & 0xFF;
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
            case 0x4210 -> {
                int v = (nmiFlag ? 0x80 : 0) | 0x02;   // RDNMI: the flag, and the CPU's version 2
                nmiFlag = false;
                yield v;
            }
            case 0x4211 -> {
                int v = irqFlag ? 0x80 : 0;             // TIMEUP: reading it ends the IRQ
                irqFlag = false;
                yield v;
            }
            case 0x4212 -> (scanline >= VBLANK_FIRST_LINE ? 0x80 : 0)
                    | (lineClock >= HBLANK_FROM || lineClock < HBLANK_TO ? 0x40 : 0)
                    | (autoJoypad && scanline >= VBLANK_FIRST_LINE && scanline < VBLANK_FIRST_LINE + 3 ? 1 : 0);
            case 0x4218 -> pads[0] & 0xFF;
            case 0x4219 -> pads[0] >> 8;
            case 0x421A -> pads[1] & 0xFF;
            case 0x421B -> pads[1] >> 8;
            case 0x4214 -> quotient & 0xFF;
            case 0x4215 -> quotient >> 8;
            case 0x4216 -> {
                runMultiplier(cpuCycle - 1);   // a read sees the cycles before its own
                yield product & 0xFF;
            }
            case 0x4217 -> {
                runMultiplier(cpuCycle - 1);
                yield product >> 8;
            }
            default -> 0;
        };
    }

    @Override
    public void write(int address, int value) {
        cpuCycle++;
        tick(speed(address));
        int bank = address >> 16;
        int offset = address & 0xFFFF;
        if (bank == 0x7E || bank == 0x7F) {
            wram[(bank - 0x7E) << 16 | offset] = (byte) value;
            return;
        }
        int s = sramIndex(bank, offset);
        if (s >= 0) {
            sram[s] = (byte) value;
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
        if (offset >= 0x4300 && offset < 0x4380) {
            int ch = (offset >> 4) & 7;
            switch (offset & 0xF) {
                case 5 -> das[ch] = (das[ch] & 0xFF00) | value;
                case 6 -> das[ch] = (das[ch] & 0x00FF) | value << 8;
                default -> { }
            }
            return;
        }
        switch (offset) {
            case 0x420B -> dma(value);
            case 0x420D -> fastRom = (value & 1) != 0;
            case 0x4200 -> {
                autoJoypad = (value & 1) != 0;
                boolean was = nmiEnabled;
                nmiEnabled = (value & 0x80) != 0;
                nmiPending |= nmiEnabled && !was && nmiFlag;
                timerMode = (value >> 4) & 3;
                if (timerMode == 0) {
                    irqFlag = false;
                }
                verticalTimer();
            }
            case 0x4207 -> htime = (htime & 0x100) | value;
            case 0x4208 -> htime = (htime & 0xFF) | (value & 1) << 8;
            case 0x4209 -> {
                vtime = (vtime & 0x100) | value;
                verticalTimer();
            }
            case 0x420A -> {
                vtime = (vtime & 0xFF) | (value & 1) << 8;
                verticalTimer();
            }
            case 0x4202 -> mulA = value;
            case 0x4203 -> {
                product = 0;
                mulSteps = 8;
                mulBits = value;
                mulShift = mulA;
                mulClock = cpuCycle;
                quotient = value; // the hardware leaves the second factor in the quotient register
            }
            case 0x4204 -> dividend = (dividend & 0xFF00) | value;
            case 0x4205 -> dividend = (dividend & 0x00FF) | value << 8;
            case 0x4206 -> {
                mulSteps = 0;
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

    /** The time of a general DMA on the channels set in {@code channels}; their counts end at 0. */
    private void dma(int channels) {
        if (channels == 0) {
            return;
        }
        long cycles = 8;
        for (int ch = 0; ch < 8; ch++) {
            if ((channels >> ch & 1) != 0) {
                cycles += 8 + 8L * (das[ch] == 0 ? 0x10000 : das[ch]);
                das[ch] = 0;
            }
        }
        while (cycles > 0) {
            int c = (int) Math.min(cycles, 64);
            tick(c);
            cycles -= c;
        }
    }

    @Override
    public void idle() {
        cpuCycle++;
        tick(6);
    }
}
