package dampf.mir;

/**
 * Types of MIR values. Integers carry no signedness: the operations do (sdiv/udiv, ashr/lshr,
 * slt/ult, sext/zext). {@code I1} is a truth value (comparisons, branch conditions); {@code PTR}
 * a pointer, 24 bits of address held like dampf's 4-byte pointers.
 */
public enum MType {
    VOID(0), I1(1), I8(1), I16(2), I32(4), PTR(4);

    /** Bytes in memory. */
    public final int size;

    MType(int size) {
        this.size = size;
    }

    /** Bits of an integer type (I1: 1); PTR is handled as 32 bits. */
    public int bits() {
        return switch (this) {
            case I1 -> 1;
            case I8 -> 8;
            case I16 -> 16;
            case I32, PTR -> 32;
            case VOID -> 0;
        };
    }

    /** Wraps a value to this type's bits (unsigned representation). */
    public long wrap(long v) {
        return bits() == 0 ? 0 : v & ((1L << bits()) - 1);
    }

    /** The value as a signed number of this type's width. */
    public long signed(long v) {
        long u = wrap(v);
        int b = bits();
        return b > 0 && (u >> (b - 1)) != 0 ? u - (1L << b) : u;
    }

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
