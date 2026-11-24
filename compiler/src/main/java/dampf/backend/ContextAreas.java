package dampf.backend;

import dampf.ast.Interrupt;
import java.util.EnumMap;
import java.util.Map;

/**
 * Where the interrupt contexts lie in low RAM (ADR-6FM9TXV4, docs/memory-map.md). Each context
 * takes whole pages right below the stack at $1E00, the NMI's highest, then the IRQ's: its direct
 * page first, whose first 32 bytes are the context's copy of the runtime's scratch registers
 * (dampf_regs, dampf_ptr), then its frames, laid out from the page's byte $20 as the main code's
 * are from $0020. A context whose frames take at most 224 bytes takes one page. The main code's
 * frames end below the lowest context's page. Both code generators use it; the naive one has no
 * frames there, so each context takes one page.
 */
final class ContextAreas {
    /** The stack, $1E00-$1FFF. */
    static final int STACK = 0x1E00;

    private ContextAreas() {}

    /** Each context's direct page, given the bytes of its frames. */
    static Map<Interrupt, Integer> pages(Map<Interrupt, Integer> frameBytes) {
        Map<Interrupt, Integer> pages = new EnumMap<>(Interrupt.class);
        int top = STACK;
        for (Interrupt c : Interrupt.values()) {
            Integer bytes = frameBytes.get(c);
            if (bytes == null) {
                continue;
            }
            int page = (top - Selection.FRAME_START - bytes) & ~0xFF;
            if (page < 0x100) {
                throw new IllegalStateException("the frames of the " + c + " context need " + bytes + " bytes, more than low RAM has");
            }
            pages.put(c, page);
            top = page;
        }
        return pages;
    }

    /** Where the main code's frames must end: at the lowest context's page, or at the stack. */
    static int mainEnd(Map<Interrupt, Integer> pages) {
        return pages.values().stream().mapToInt(Integer::intValue).min().orElse(STACK);
    }

    /** "$1C00-$1DFF (NMI $1D00, IRQ $1C00)", for messages. */
    static String describe(Map<Interrupt, Integer> pages) {
        StringBuilder each = new StringBuilder();
        pages.forEach((c, p) -> each.append(each.isEmpty() ? "" : ", ").append(c).append(" $").append(Selection.hex(p, 4)));
        return "$" + Selection.hex(mainEnd(pages), 4) + "-$" + Selection.hex(STACK - 1, 4) + " (" + each + ")";
    }
}
