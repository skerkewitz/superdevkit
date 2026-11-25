package dampf.target;

import dampf.ast.Interrupt;
import java.util.EnumMap;
import java.util.Map;

/**
 * Where the interrupt contexts lie in low RAM (ADR-6FM9TXV4, docs/memory-map.md). Each context
 * takes whole pages right below the stack ({@link SnesTarget#STACK}), the NMI's highest, then the
 * IRQ's: its direct page first, whose first 32 bytes are the context's copy of the runtime's
 * scratch registers (dampf_regs, dampf_ptr), then its frames, laid out from the page's byte
 * {@link SnesTarget#FRAME_START} as the main code's are from $0020. A context whose frames take at most 224 bytes takes one page. The main code's
 * frames end below the lowest context's page. Both code generators use it; the naive one has no
 * frames there, so each context takes one page.
 */
public final class ContextAreas {
    private ContextAreas() {}

    /** Each context's direct page, given the bytes of its frames. */
    public static Map<Interrupt, Integer> pages(Map<Interrupt, Integer> frameBytes) {
        Map<Interrupt, Integer> pages = new EnumMap<>(Interrupt.class);
        int top = SnesTarget.STACK;
        for (Interrupt c : Interrupt.values()) {
            Integer bytes = frameBytes.get(c);
            if (bytes == null) {
                continue;
            }
            int page = (top - SnesTarget.FRAME_START - bytes) & ~0xFF;
            if (page < SnesTarget.DIRECT_PAGE) {
                throw new IllegalStateException("the frames of the " + c + " context need " + bytes + " bytes, more than low RAM has");
            }
            pages.put(c, page);
            top = page;
        }
        return pages;
    }

    /** Where the main code's frames must end: at the lowest context's page, or at the stack. */
    public static int mainEnd(Map<Interrupt, Integer> pages) {
        return pages.values().stream().mapToInt(Integer::intValue).min().orElse(SnesTarget.STACK);
    }

    /** "$1C00-$1DFF (NMI $1D00, IRQ $1C00)", for messages. */
    public static String describe(Map<Interrupt, Integer> pages) {
        StringBuilder each = new StringBuilder();
        pages.forEach((c, p) -> each.append(each.isEmpty() ? "" : ", ").append(c).append(" $").append(String.format("%04X", p)));
        return String.format("$%04X-$%04X (%s)", mainEnd(pages), SnesTarget.STACK - 1, each);
    }
}
