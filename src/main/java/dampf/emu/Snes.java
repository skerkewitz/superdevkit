package dampf.emu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Just enough of a Super Nintendo to run dampf's programs: the 65816 ({@link Cpu65816}), a LoROM
 * cartridge, 128 KB of WRAM with its mirrors, and the master-cycle timing of the bus. No picture,
 * no sound, no DMA, no interrupts.
 *
 * <p>Timing, in master cycles (21.477 MHz on NTSC): an access costs 8 for WRAM, SlowROM and most of
 * the address space, 6 for I/O registers at $2000-$3FFF and $4200-$5FFF (and FastROM once
 * MEMSEL is set), 12 for the old joypad registers at $4000-$41FF; an internal CPU cycle costs 6.
 * DRAM refresh halts the CPU for 40 master cycles per scanline of 1364.
 *
 * <p>Writes to the B-bus ports $21FA-$21FF, which no hardware uses, are passed to a
 * {@link PortListener}: dampf's runtime writes 1 to $21FB when the program is done, and the
 * game's benchmark marks its ticks there.
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
    private PortListener ports = (p, v, t) -> { };
    private boolean done;
    private boolean fastRom;
    private long master;
    private long lineClock;

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

    public void onPortWrite(PortListener listener) {
        this.ports = listener;
    }

    /** Master cycles since power-on. */
    public long masterClock() {
        return master;
    }

    public boolean done() {
        return done;
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
        return switch (offset) {
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
        if (offset >= 0x21FA && offset <= 0x21FF) {
            if (offset == 0x21FB) {
                done = true;
            }
            ports.write(offset, value, master);
            return;
        }
        switch (offset) {
            case 0x420D -> fastRom = (value & 1) != 0;
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
