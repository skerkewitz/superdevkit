package dampf.ast;

/**
 * The interrupt a function handles, marked with {@code DAMPF_INTERRUPT(nmi)} or
 * {@code DAMPF_INTERRUPT(irq)} from {@code <dampf.h>}. Each handler is the root of a context of its
 * own: the functions it reaches get frames, a direct page and the runtime's scratch registers
 * apart from the main code's and the other context's (ADR-6FM9TXV4). Built in the compiler, not
 * yet offered to games (ADR-6FKTVDE8).
 */
public enum Interrupt {
    /** The NMI, at the start of the vertical blank (the kit's, later). */
    NMI("nmi"),
    /** The timer IRQ (the game's, later). */
    IRQ("irq");

    /** The name in the marker and in the symbols of the context ({@code dampf_irq_handler}). */
    public final String key;

    Interrupt(String key) {
        this.key = key;
    }

    /** The interrupt of a marker's name, or null. */
    public static Interrupt of(String key) {
        for (Interrupt i : values()) {
            if (i.key.equals(key)) {
                return i;
            }
        }
        return null;
    }

    /** As the messages name it: "the IRQ handler". */
    public String handler() {
        return "the " + name() + " handler";
    }
}
