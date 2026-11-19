package dampf.emu;

/**
 * The SNES's sound side as far as the kit's sound effects need it (docs/sound.md): the four ports
 * between the CPU and the SPC700, its 64 KB of audio RAM, the DSP's registers, and the SPC700
 * itself in two parts.
 *
 * <ul>
 * <li>Its boot code (the IPL ROM) as behaviour, not as code: Nintendo's 64 bytes are not part of
 * the kit. It follows the transfer protocol fullsnes documents ("SNES APU Main CPU
 * Communication Port"): $BBAA on ports 0 and 1; a block begins with its address on ports 2 and 3, a
 * non-zero byte on port 1 and a kick on port 0, which it echoes; then byte n arrives with n on
 * port 0 (the low 8 bits), echoed; a value on port 0 that is ahead of the next index by 1 to 127
 * starts the next block (or, with 0 on port 1, jumps to the address). It answers at once.
 * <li>After the jump, an SPC700 that runs the code in the audio RAM, at 1.024 MHz against the
 * CPU's master clock, with the opcodes the kit's driver uses (lib/sound/sfx_voices.h); any other
 * opcode stops with its address, so a changed driver cannot pass unnoticed.
 * </ul>
 *
 * The DSP is its registers: a write to $F3 goes to the register $F2 names, and to the
 * {@link DspListener}. No sound is made here; Rachael's DSP (lib/rachael/dsp.c) makes it.
 */
public final class Apu {
    /** Told of every write the SPC700 makes to a DSP register. */
    public interface DspListener {
        void write(int reg, int value);
    }

    private static final long MASTER_HZ = 21_477_272L;
    private static final long SPC_HZ = 1_024_000L;

    private final byte[] aram = new byte[65536];
    private final int[] dsp = new int[128];
    private final int[] fromCpu = new int[4];   /* what the CPU wrote, the SPC700 reads at $F4-$F7 */
    private final int[] toCpu = new int[4];     /* what the SPC700 wrote, the CPU reads at $2140-$2143 */
    private DspListener listener = (r, v) -> { };
    private long uploadHash = 2166136261L;

    // the boot code's state
    private boolean booted;     /* jumped to the uploaded code */
    private boolean inBlock;    /* a block has begun: bytes are expected */
    private int dest;           /* the block's address */
    private int index;          /* the next byte's index (8 bits) */
    private int count;          /* bytes of the block so far */

    // the SPC700 (after the jump)
    private int pc;
    private int a;
    private int x;
    private int y;
    private boolean n;
    private boolean z;
    private boolean c;
    private long cycles;        /* SPC700 cycles run since the jump */
    private long startMaster;   /* the master clock at the jump */
    private int dspAddr;

    public Apu() {
        toCpu[0] = 0xAA;
        toCpu[1] = 0xBB;
    }

    public void onDspWrite(DspListener l) {
        this.listener = l;
    }

    /** The audio RAM. */
    public byte[] aram() {
        return aram;
    }

    /**
     * The FNV-1a of every byte written into the audio RAM from $0200 on, each as its address (low
     * byte, high byte) and its value, in the order written: what the uploads brought.
     */
    public long uploadHash() {
        return uploadHash;
    }

    private void hashWrite(int address, int value) {
        if (address >= 0x0200) {
            long h = uploadHash;
            h = ((h ^ (address & 0xFF)) * 16777619L) & 0xFFFFFFFFL;
            h = ((h ^ (address >> 8)) * 16777619L) & 0xFFFFFFFFL;
            uploadHash = ((h ^ value) * 16777619L) & 0xFFFFFFFFL;
        }
    }

    /** The DSP's register, as last written. */
    public int dsp(int reg) {
        return dsp[reg & 0x7F];
    }

    /** The CPU reads port {@code p} (0..3) at this master clock. */
    public int cpuRead(int p, long master) {
        catchUp(master);
        return toCpu[p & 3];
    }

    /** The CPU writes port {@code p}. */
    public void cpuWrite(int p, int value, long master) {
        catchUp(master);
        fromCpu[p & 3] = value & 0xFF;
        if (!booted && (p & 3) == 0) {
            boot(value & 0xFF, master);
        }
    }

    /** The boot code's answer to a new value on port 0. */
    private void boot(int p0, long master) {
        if (!inBlock) {
            if (p0 == 0xCC) {
                block(p0, master);
            }
            return;
        }
        if (p0 == index) {
            aram[(dest + count) & 0xFFFF] = (byte) fromCpu[1];
            hashWrite((dest + count) & 0xFFFF, fromCpu[1]);
            toCpu[0] = p0;
            index = (index + 1) & 0xFF;
            count++;
            return;
        }
        int ahead = (p0 - index) & 0xFF;
        if (ahead >= 1 && ahead <= 127) {
            block(p0, master);
        }
        // else: a value behind the index, as while the CPU changes ports: ignored
    }

    /** A kick: the address from ports 2 and 3, the command on port 1. */
    private void block(int kick, long master) {
        int address = fromCpu[2] | fromCpu[3] << 8;
        toCpu[0] = kick;
        if (fromCpu[1] == 0) {
            booted = true;
            inBlock = false;
            pc = address;
            startMaster = master;
            cycles = 0;
            return;
        }
        inBlock = true;
        dest = address;
        index = 0;
        count = 0;
    }

    // ------------------------------------------------------------------ the SPC700

    /** Runs the SPC700 up to the CPU's master clock. */
    private void catchUp(long master) {
        if (!booted) {
            return;
        }
        long target = (master - startMaster) * SPC_HZ / MASTER_HZ;
        while (cycles < target) {
            step();
        }
    }

    private int read(int address) {
        address &= 0xFFFF;
        if (address >= 0xF4 && address <= 0xF7) {
            return fromCpu[address - 0xF4];
        }
        if (address == 0xF2) {
            return dspAddr;
        }
        if (address == 0xF3) {
            return dsp[dspAddr & 0x7F];
        }
        return aram[address] & 0xFF;
    }

    private void write(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        aram[address] = (byte) value;       /* the RAM under the registers takes the write too */
        hashWrite(address, value);
        if (address >= 0xF4 && address <= 0xF7) {
            toCpu[address - 0xF4] = value;
        } else if (address == 0xF2) {
            dspAddr = value;
        } else if (address == 0xF3) {
            if (dspAddr < 0x80) {
                dsp[dspAddr] = value;
                listener.write(dspAddr, value);
            }
        }
    }

    private int fetch() {
        int v = aram[pc] & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private void compare(int reg, int value) {
        int r = reg - value;
        c = r >= 0;
        n = (r & 0x80) != 0;
        z = (r & 0xFF) == 0;
    }

    private void flags(int v) {
        n = (v & 0x80) != 0;
        z = (v & 0xFF) == 0;
    }

    private void branch(boolean take) {
        int rel = (byte) fetch();
        cycles += 2;
        if (take) {
            pc = (pc + rel) & 0xFFFF;
            cycles += 2;
        }
    }

    /** One instruction: the opcodes of the kit's driver; any other stops the emulation. */
    private void step() {
        int at = pc;
        int op = fetch();
        switch (op) {
            case 0xF8 -> {          // mov x,dp
                x = read(fetch());
                flags(x);
                cycles += 3;
            }
            case 0xE4 -> {          // mov a,dp
                a = read(fetch());
                flags(a);
                cycles += 3;
            }
            case 0xEB -> {          // mov y,dp
                y = read(fetch());
                flags(y);
                cycles += 3;
            }
            case 0x8F -> {          // mov dp,#imm
                int imm = fetch();
                write(fetch(), imm);
                cycles += 5;
            }
            case 0xFA -> {          // mov dp,dp (source first)
                int src = fetch();
                int v = read(src);
                write(fetch(), v);
                cycles += 5;
            }
            case 0xD8 -> {          // mov dp,x
                write(fetch(), x);
                cycles += 4;
            }
            case 0xC4 -> {          // mov dp,a
                write(fetch(), a);
                cycles += 4;
            }
            case 0xCB -> {          // mov dp,y
                write(fetch(), y);
                cycles += 4;
            }
            case 0x3E -> {          // cmp x,dp
                compare(x, read(fetch()));
                cycles += 3;
            }
            case 0x64 -> {          // cmp a,dp
                compare(a, read(fetch()));
                cycles += 3;
            }
            case 0x7E -> {          // cmp y,dp
                compare(y, read(fetch()));
                cycles += 3;
            }
            case 0xF0 -> branch(z);         // beq
            case 0xD0 -> branch(!z);        // bne
            case 0x10 -> branch(!n);        // bpl
            case 0x30 -> branch(n);         // bmi
            case 0x2F -> {                  // bra
                int rel = (byte) fetch();
                pc = (pc + rel) & 0xFFFF;
                cycles += 4;
            }
            case 0x00 -> cycles += 2;       // nop
            default -> throw new IllegalStateException(String.format(
                    "the SPC700 met opcode $%02X at $%04X, which dampf's emulator does not run "
                            + "(it runs the opcodes of the kit's sound driver, lib/sound/sfx_voices.h)", op, at));
        }
    }
}
