package dampf.mir;

/** Something an instruction can use: a constant, a parameter, an address, or an instruction's result. */
public sealed interface Value permits Value.Const, Value.Param, Value.GlobalAddr, Value.SlotAddr, Value.Undef, Instr {
    MType type();

    /** A constant; {@code bits} is already wrapped to the type. */
    record Const(MType type, long bits) implements Value {
        public Const {
            bits = type.wrap(bits);
        }

        @Override
        public String toString() {
            return type == MType.I1 ? (bits != 0 ? "true" : "false") : Long.toString(type.signed(bits));
        }
    }

    /** A function parameter. */
    final class Param implements Value {
        private final String name;
        private final MType type;
        /** The interpreter's slot for this parameter's value. */
        int index = -1;
        /** A range every caller's argument is known to lie in (set by ParamRanges and PointerParams); null if none. */
        public Ranges.Range known;

        public Param(String name, MType type) {
            this.name = name;
            this.type = type;
        }

        public String name() {
            return name;
        }

        @Override
        public MType type() {
            return type;
        }

        @Override
        public String toString() {
            return "%" + name;
        }
    }

    /** The address of a global object (also static locals, string literals, constant data). */
    record GlobalAddr(String symbol) implements Value {
        @Override
        public MType type() {
            return MType.PTR;
        }

        @Override
        public String toString() {
            return "@" + symbol;
        }
    }

    /** The address of a frame slot: a local in memory (an array, a struct, a local whose address is taken). */
    record SlotAddr(Slot slot) implements Value {
        @Override
        public MType type() {
            return MType.PTR;
        }

        @Override
        public String toString() {
            return "$" + slot.name();
        }
    }

    /** A value that is never defined (reading an uninitialised local); any bits will do. */
    record Undef(MType type) implements Value {
        @Override
        public String toString() {
            return "undef";
        }
    }

    /** A frame slot of a function: {@code size} bytes of memory for one activation. */
    record Slot(String name, int size) {}
}
