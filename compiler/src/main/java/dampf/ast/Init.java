package dampf.ast;

import java.util.List;

/**
 * The initial contents of a global object, as a list of items at byte offsets. Bytes not
 * covered by an item are zero.
 */
public record Init(List<Item> items) {
    /**
     * One scalar: a number ({@code address} and {@code function} null), the address of an object
     * plus a byte offset (a pointer constant: {@code &x}, an array name, a string literal), or
     * the address of a function (an entry of a function table).
     */
    public record Item(int offset, int size, long value, Variable address, long addressOffset, Function function) {
        public Item(int offset, int size, long value, Variable address, long addressOffset) {
            this(offset, size, value, address, addressOffset, null);
        }

        public static Item number(int offset, int size, long value) {
            return new Item(offset, size, value, null, 0);
        }

        public static Item address(int offset, Variable target, long targetOffset) {
            return new Item(offset, 4, 0, target, targetOffset);
        }

        public static Item function(int offset, Function f) {
            return new Item(offset, 4, 0, null, 0, f);
        }
    }
}
