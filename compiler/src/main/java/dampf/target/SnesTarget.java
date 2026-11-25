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
