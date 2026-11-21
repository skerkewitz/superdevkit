package dampf.lir;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The operand of an LIR instruction, typed (ADR-6F8NBFAQ): what a pass asks of an instruction
 * (does it read a frame place, which one, is it indexed) without parsing text. {@link #text}
 * gives the operand as ca65 writes it, and {@link #of} reads that text back: the two agree for
 * every operand dampf writes; anything else is kept as {@link Raw} text.
 */
public sealed interface LOperand {
    /** The symbol the frame places are written with. */
    String FRAME = "dampf_frame";

    String text();

    /** The accumulator ({@code asl a}). */
    record Acc() implements LOperand {
        @Override
        public String text() {
            return "a";
        }
    }

    /**
     * Whose a frame place is (ADR-6F8NBFAQ): word {@code word} of a MIR value (or of a callee's
     * parameter), the incoming place of a φ ({@code incoming}), or byte {@code word} of a frame slot.
     * {@code key} is the MIR object, compared by identity. The phase that assigns the places
     * gives every owned operand its address.
     */
    record Owner(Object key, boolean incoming, int word) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Owner w && w.key == key && w.incoming == incoming && w.word == word;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(key) * 31 + word * 2 + (incoming ? 1 : 0);
        }
    }

    /**
     * An immediate: {@code expr} is what follows the {@code #} ({@code $0001}, {@code .loword(_x)},
     * {@code ^_x}). {@code owner}: the frame slot whose address it is ({@code .loword(dampf_frame+n)}),
     * or null; not part of equality.
     */
    record Imm(String expr, Owner owner) implements LOperand {
        public Imm(String expr) {
            this(expr, null);
        }

        @Override
        public String text() {
            return "#" + expr;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Imm k && k.expr.equals(expr);
        }

        @Override
        public int hashCode() {
            return expr.hashCode();
        }

        /** The value if the immediate is a number ({@code $XXXX}); null otherwise. */
        public Long value() {
            return expr.startsWith("$") && expr.length() > 1 ? Long.parseLong(expr.substring(1), 16) : null;
        }
    }

    /** A memory operand's addressing form: the prefix ca65 is given. */
    enum Width {
        /** No prefix: ca65 chooses (absolute for dampf's symbols). */
        NONE(""),
        /** {@code z:} direct page. */
        DP("z:"),
        /** {@code a:} absolute. */
        ABS("a:"),
        /** {@code f:} long. */
        LONG("f:");

        final String prefix;

        Width(String prefix) {
            this.prefix = prefix;
        }
    }

    /** A memory operand's index register. */
    enum Index {
        NONE(""),
        X(",x"),
        Y(",y");

        final String suffix;

        Index(String suffix) {
            this.suffix = suffix;
        }
    }

    /**
     * Memory: {@code base} (a symbol or a fixed address) plus {@code offset}, in an addressing
     * form, indexed or not; {@code indirect}: {@code [base+offset],y}, the pointer kept at that
     * place (indexed by Y). A frame place is the base {@link #FRAME} with its offset, written
     * with the offset even when it is 0. {@code owner}: whose frame place it is, or null; not part
     * of equality (two operands at one address are the same operand).
     */
    record Mem(Width width, String base, int offset, Index index, boolean indirect, Owner owner) implements LOperand {
        public Mem(Width width, String base, int offset, Index index, boolean indirect) {
            this(width, base, offset, index, indirect, null);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Mem m && m.width == width && m.base.equals(base) && m.offset == offset && m.index == index
                    && m.indirect == indirect;
        }

        @Override
        public int hashCode() {
            return ((base.hashCode() * 31 + offset) * 31 + width.ordinal() * 3 + index.ordinal()) * 2 + (indirect ? 1 : 0);
        }

        /** The same operand with an owner. */
        public Mem owned(Owner o) {
            return new Mem(width, base, offset, index, indirect, o);
        }

        @Override
        public String text() {
            String place = base + (offset != 0 || base.equals(FRAME) ? "+" + offset : "");
            return indirect ? "[" + place + "]" + index.suffix : width.prefix + place + index.suffix;
        }

        /** The same operand k bytes further on. */
        public Mem plus(int k) {
            return new Mem(width, base, offset + k, index, indirect, owner == null ? null : new Owner(owner.key(), owner.incoming(), owner.word() + k));
        }

        /** The same address indexed by another register. */
        public Mem indexed(Index by) {
            return new Mem(width, base, offset, by, indirect, owner);
        }

        /** A place of the frame, read or written directly or through an index or as a pointer. */
        public boolean frame() {
            return base.equals(FRAME);
        }

        /** The frame place this operand is, read or written directly (not indexed, not a pointer); -1 otherwise. */
        public int frameOffset() {
            return frame() && index == Index.NONE && !indirect ? offset : -1;
        }
    }

    /** A label: the target of a branch, a jump or a call. */
    record Label(String name) implements LOperand {
        @Override
        public String text() {
            return name;
        }
    }

    /** Anything else, as its text. */
    record Raw(String text) implements LOperand {}

    Pattern MEM = Pattern.compile("^(z:|a:|f:)?([A-Za-z_$][A-Za-z0-9_]*)(?:\\+(\\d+))?(,x|,y)?$");
    Pattern INDIRECT = Pattern.compile("^\\[([A-Za-z_][A-Za-z0-9_]*)(?:\\+(\\d+))?\\](,y)?$");

    /**
     * The operand of {@code mnemonic} as ca65 writes it; null for none. A branch's, a jump's or a
     * call's operand is a label; a form that does not print back the same is {@link Raw}.
     */
    static LOperand of(String mnemonic, String text) {
        if (text == null) {
            return null;
        }
        LOperand o = parse(mnemonic, text);
        return o.text().equals(text) ? o : new Raw(text);
    }

    private static LOperand parse(String mnemonic, String text) {
        if (text.equals("a")) {
            return new Acc();
        }
        if (text.startsWith("#")) {
            return new Imm(text.substring(1));
        }
        if (switch (mnemonic) {
            case "bra", "brl", "beq", "bne", "bcc", "bcs", "bmi", "bpl", "bvc", "bvs", "jmp", "jml", "jsl", "jsr" -> true;
            default -> false;
        }) {
            return new Label(text);
        }
        Matcher m = MEM.matcher(text);
        if (m.matches()) {
            Width w = m.group(1) == null ? Width.NONE : switch (m.group(1)) {
                case "z:" -> Width.DP;
                case "a:" -> Width.ABS;
                default -> Width.LONG;
            };
            Index ix = m.group(4) == null ? Index.NONE : m.group(4).equals(",x") ? Index.X : Index.Y;
            return new Mem(w, m.group(2), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), ix, false);
        }
        m = INDIRECT.matcher(text);
        if (m.matches()) {
            return new Mem(Width.NONE, m.group(1), m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                    m.group(3) == null ? Index.NONE : Index.Y, true);
        }
        return new Raw(text);
    }
}
