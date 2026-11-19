package dampf.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * C types. Integer types have the meaning they have with gcc/clang on x86-64:
 * char 8, short 16, int 32 bit. That is what makes dampf's results bit-identical to the PC.
 *
 * <p>Sizes are dampf's, not the PC's: a pointer takes 4 bytes (16-bit address, bank, and a zero
 * byte, see docs/memory-map.md) and structs have no padding. Only {@code sizeof} can see the
 * difference.
 */
public sealed interface Type {
    /**
     * The size gcc gives the type on x86-64 (the PC build): pointers are 8 bytes there, and
     * struct fields are aligned to their own alignment, the struct to its largest. dampf itself
     * uses 4-byte pointers and no padding ({@link #size()}).
     */
    static long pcSize(Type t) {
        return switch (t) {
            case Pointer p -> 8;
            case Array a -> pcSize(a.element()) * Math.max(a.length(), 0);
            case Struct s -> {
                long offset = 0;
                long align = 1;
                for (Field f : s.def().fields()) {
                    long fa = pcAlign(f.type());
                    offset = (offset + fa - 1) / fa * fa + pcSize(f.type());
                    align = Math.max(align, fa);
                }
                yield (offset + align - 1) / align * align;
            }
            default -> t.size();
        };
    }

    /** The alignment gcc gives the type on x86-64. */
    static long pcAlign(Type t) {
        return switch (t) {
            case Pointer p -> 8;
            case Array a -> pcAlign(a.element());
            case Struct s -> s.def().fields().stream().mapToLong(f -> pcAlign(f.type())).max().orElse(1);
            case Int i -> Math.max(1, i.size());
            default -> Math.max(1, t.size());
        };
    }

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

    /**
     * A pointer: 4 bytes, the address in the low word, the bank in the third byte.
     * {@code volatileTarget}: it points to {@code volatile} memory (an I/O register), which an
     * optimizer must neither cache nor remove accesses to.
     */
    record Pointer(Type target, boolean volatileTarget) implements Type {
        public Pointer(Type target) {
            this(target, false);
        }

        @Override
        public int size() {
            return 4;
        }

        @Override
        public String toString() {
            return (volatileTarget ? "volatile " : "") + target + " *";
        }
    }

    /** An array; {@code length} is -1 while unknown ({@code extern uint8_t x[];}). */
    record Array(Type element, int length) implements Type {
        @Override
        public int size() {
            return element.size() * Math.max(length, 0);
        }

        @Override
        public String toString() {
            return element + "[" + (length < 0 ? "" : length) + "]";
        }
    }

    record Struct(StructDef def) implements Type {
        @Override
        public int size() {
            return def.size();
        }

        @Override
        public String toString() {
            return "struct " + def.name();
        }
    }

    /** One field of a struct, at a byte offset (dampf packs structs without padding). */
    record Field(String name, Type type, int offset) {}

    /**
     * A struct's fields. Filled in when the definition is complete. Two definitions are equal
     * if their fields are: the same struct defined in a header that several files include.
     */
    final class StructDef {
        private final String name;
        private final List<Field> fields = new ArrayList<>();
        private boolean complete;

        public StructDef(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public List<Field> fields() {
            return fields;
        }

        public boolean complete() {
            return complete;
        }

        public void complete(List<Field> list) {
            fields.addAll(list);
            complete = true;
        }

        public Field field(String n) {
            return fields.stream().filter(f -> f.name().equals(n)).findFirst().orElse(null);
        }

        public int size() {
            return fields.isEmpty() ? 0 : fields.getLast().offset() + fields.getLast().type().size();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof StructDef d) || !name.equals(d.name) || fields.size() != d.fields.size()) {
                return false;
            }
            for (int i = 0; i < fields.size(); i++) {
                Field a = fields.get(i);
                Field b = d.fields.get(i);
                // compare field types by name only: a struct can point to itself
                if (!a.name().equals(b.name()) || a.offset() != b.offset() || !a.type().toString().equals(b.type().toString())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, fields.size());
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
    /** {@code size_t}. It is 64 bit on the PC; sizes and indexes in game code fit 32 bits. */
    Int SIZE = U32;

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

    static boolean isScalar(Type t) {
        return t instanceof Int || t instanceof Pointer;
    }
}
