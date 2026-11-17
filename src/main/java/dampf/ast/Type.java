package dampf.ast;

/**
 * C types. Integer types have the meaning they have with gcc/clang on x86-64:
 * char 8, short 16, int 32 bit. That is what makes dampf's results bit-identical to the PC.
 */
public sealed interface Type {
    /** Size in bytes on the SNES. */
    int size();

    record Void() implements Type {
        @Override
        public int size() {
            return 0;
        }

        @Override
        public String toString() {
            return "void";
        }
    }

    record Int(int bits, boolean signed) implements Type {
        @Override
        public int size() {
            return bits / 8;
        }

        public long min() {
            return signed ? -(1L << (bits - 1)) : 0;
        }

        public long max() {
            return signed ? (1L << (bits - 1)) - 1 : (1L << bits) - 1;
        }

        /** Wraps a value into this type, as a conversion to it does on the PC. */
        public long wrap(long v) {
            long mask = (1L << bits) - 1;
            long u = v & mask;
            return signed && u > max() ? u - (1L << bits) : u;
        }

        @Override
        public String toString() {
            return (signed ? "int" : "uint") + bits + "_t";
        }
    }

    Void VOID = new Void();
    Int I8 = new Int(8, true);
    Int U8 = new Int(8, false);
    Int I16 = new Int(16, true);
    Int U16 = new Int(16, false);
    /** C's {@code int}: the type of integer constants and the target of the integer promotions. */
    Int I32 = new Int(32, true);
    Int U32 = new Int(32, false);

    /** The integer promotions: everything narrower than int becomes int. */
    static Int promote(Int t) {
        return t.bits() < 32 ? I32 : t;
    }

    /** The usual arithmetic conversions for two operands (there is no type wider than 32 bit). */
    static Int common(Int a, Int b) {
        Int pa = promote(a);
        Int pb = promote(b);
        return !pa.signed() || !pb.signed() ? U32 : I32;
    }
}
