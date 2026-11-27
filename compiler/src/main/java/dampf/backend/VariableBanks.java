package dampf.backend;

import dampf.target.SnesTarget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which variables live in WRAM's second bank, $7F (ADR-6FVM9QWS): all of them stay in the data
 * bank, $7E, while they fit there, so a program that fits generates the code it always did. When
 * they do not, the largest ones go to $7F, one after the other, until the rest fits into $7E; a
 * variable that does not fit into what is left of $7F is passed over for the next smaller one.
 * Pinned variables stay in $7E: those an assembly function names (it reaches them with 16-bit
 * addresses) and the naive code generator's frames. The program cannot tell which bank a variable
 * is in; a variable in $7F is reached with long addresses, and a pointer to it carries bank $7F.
 */
public final class VariableBanks {
    private VariableBanks() {}

    /** A variable: its symbol, its bytes, whether it must stay in $7E. */
    public record Item(String symbol, int bytes, boolean pinned) {}

    /** The variables do not fit into both banks. */
    public static final class Overflow extends Exception {
        private static final long serialVersionUID = 1L;
        private final String symbol;

        Overflow(String symbol, String message) {
            super(message);
            this.symbol = symbol;
        }

        /** The largest variable left in $7E that found no room in $7F (null if only pinned ones are left). */
        public String symbol() {
            return symbol;
        }
    }

    /** The symbols of the variables that go to bank $7F, in the order they were chosen. */
    public static Set<String> secondBank(List<Item> items) throws Overflow {
        int first = SnesTarget.variableBytes(SnesTarget.VARIABLE_BANK);
        int second = SnesTarget.variableBytes(SnesTarget.SECOND_VARIABLE_BANK);
        long total = 0;
        for (Item i : items) {
            total += i.bytes();
        }
        Set<String> out = new LinkedHashSet<>();
        if (total <= first) {
            return out;
        }
        List<Item> order = new ArrayList<>(items);
        order.sort(Comparator.comparingInt(Item::bytes).reversed());   // stable: the program's order among equals
        long left = total;
        int room = second;
        String largestLeft = null;
        for (Item i : order) {
            if (left <= first) {
                break;
            }
            if (i.pinned()) {
                continue;
            }
            if (i.bytes() <= room) {
                out.add(i.symbol());
                room -= i.bytes();
                left -= i.bytes();
            } else if (largestLeft == null) {
                largestLeft = i.symbol();
            }
        }
        if (left > first) {
            throw new Overflow(largestLeft, String.format(java.util.Locale.ROOT,
                    "the variables need %,d bytes, more than RAM holds: %,d in bank $%02X and %,d in bank $%02X",
                    total, first, SnesTarget.VARIABLE_BANK, second, SnesTarget.SECOND_VARIABLE_BANK));
        }
        return out;
    }
}
