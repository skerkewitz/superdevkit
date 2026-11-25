package dampf.target;

/**
 * The SNES facts the code generators and the checks of the compiler ask about: where the frames,
 * the direct page and the stack lie in low RAM (docs/memory-map.md). The code generators are
 * plain 65816 and ask here instead of knowing; the interrupt contexts' pages are {@link
 * ContextAreas}, the cycles {@link Cycles65816}, the ROM's layout {@code dampf.snes.RomBuilder}.
 *
 * <p>The answers are numbers and booleans: the target knows nothing of the compiler's own types,
 * so the compiler depends on it and not the other way round.
 */
public final class SnesTarget {
    private SnesTarget() {}

    /**
     * The data bank: the runtime sets DBR to it at start-up, and it never changes (ADR-6F0R69B6).
     * WRAM's first bank; a 16-bit absolute address reaches it.
     */
    public static final int DATA_BANK = 0x7E;
    /**
     * The bank of the variables (globals, static locals, the naive generator's frames): the bank
     * byte of a pointer to one. The code generators ask here wherever they write it.
     */
    public static final int VARIABLE_BANK = DATA_BANK;
    /**
     * The bank byte of a pointer to a frame place: low RAM ($0000-$1FFF) is the same memory in
     * bank 0 and at the start of WRAM's first bank, and a pointer to it carries this one.
     */
    public static final int FRAME_BANK = 0x7E;

    /** How the code reaches memory at a fixed address. */
    public enum Reach {
        /** With a 16-bit address, through the data bank ({@code lda abs}, {@code lda abs,x}). */
        DATA_BANK,
        /** With a 24-bit long address ({@code lda f:long}, {@code lda f:long,x}). */
        LONG
    }

    /** A variable: through the data bank while the variables are in it, else long. */
    public static Reach variable() {
        return VARIABLE_BANK == DATA_BANK ? Reach.DATA_BANK : Reach.LONG;
    }

    /** Constant data in ROM: long, since the data bank is RAM. */
    public static Reach romData() {
        return Reach.LONG;
    }

    /**
     * An address given as a number, a pointer made from a constant: an I/O register in bank 0, or
     * save RAM. Long, since the data bank is RAM and an absolute $210B would reach $7E:210B.
     */
    public static Reach number() {
        return Reach.LONG;
    }

    /**
     * The CPU's 8 x 8 bit multiplier, in bank 0: the factors (writing WRMPYB starts it) and the
     * 16-bit product, there 8 CPU cycles after the write. The runtime's routines name the same
     * addresses (runtime.s).
     */
    public static final int WRMPYA = 0x004202;
    public static final int WRMPYB = 0x004203;
    public static final int RDMPY = 0x004216;

    /**
     * Whether a 24-bit address is a register of the PPU, the sound or the CPU that holds no memory
     * and starts no DMA when written: $2100-$217F, $4200-$420A, $420D and $4300-$437F, in the
     * banks that have them. The WRAM port ($2180) and MDMAEN and HDMAEN ($420B, $420C) are not:
     * they write memory.
     */
    public static boolean isPlainRegister(long address) {
        long bank = (address >> 16) & 0xFF, a = address & 0xFFFF;
        return (bank < 0x40 || bank >= 0x80 && bank < 0xC0)
                && (a >= 0x2100 && a <= 0x217F || a >= 0x4200 && a <= 0x420A || a == 0x420D || a >= 0x4300 && a <= 0x437F);
    }

    /** Where code and constant data start in each ROM bank (LoROM: $8000-$FFFF; dampf.snes.RomBuilder). */
    public static final int ROM_START = 0x8000;

    /**
     * Where the frames start: after the runtime's scratch registers ({@code dampf_regs},
     * {@code dampf_ptr}, $0000-$001F) in the main code's direct page, and at the same byte of
     * each interrupt context's page.
     */
    public static final int FRAME_START = 0x20;
    /** The stack, $1E00-$1FFF; the frames and the interrupt contexts' pages lie below it. */
    public static final int STACK = 0x1E00;
    /** The most bytes the frames may take, from {@link #FRAME_START} up to the stack. */
    public static final int MAX_FRAME = STACK - FRAME_START;
    /** The bytes of a direct page. */
    static final int DIRECT_PAGE = 0x100;

    /**
     * Whether {@code bytes} bytes of the frames from {@code offset} (counted from {@link
     * #FRAME_START}) all lie in the direct page, so direct page addressing reaches them: in the
     * main code with D = 0, in an interrupt context relative to its page.
     */
    public static boolean inDirectPage(int offset, int bytes) {
        return FRAME_START + offset + bytes <= DIRECT_PAGE;
    }
}
