package dampf.asm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link Assembler} makes of one source file: its segments (bytes, and the places where a
 * value is filled in when the addresses are known), its symbols, and what it exports and imports.
 * Symbol names are full names: a global name as written, a name inside {@code .proc p} as
 * {@code p::name}, a cheap local label with the normal label before it ({@code p::b3::@L1}), an
 * unnamed label as {@code :n} (the n-th of the file).
 */
public final class ObjectFile {
    /** A value filled in at link time: {@code size} bytes at {@code offset} of the segment. */
    public record Fixup(int offset, int size, Kind kind, Expr expr, String where) {
        public enum Kind {
            /** The value's low bytes; the value must fit (unless the expression cuts it itself). */
            VALUE,
            /** A branch: the distance from the end of the instruction (size 1 or 2). */
            RELATIVE
        }
    }

    /** One segment's part of this file. */
    public static final class Section {
        public final String name;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        public final List<Fixup> fixups = new ArrayList<>();
        /** Bytes reserved without contents (.res in a segment that is not written to the ROM). */
        int reserved;

        Section(String name) {
            this.name = name;
        }

        public int size() {
            return bytes.size() + reserved;
        }

        public byte[] bytes() {
            return bytes.toByteArray();
        }

        void emit(int b) {
            if (reserved > 0) {
                throw new IllegalStateException("data after .res without contents in " + name);
            }
            bytes.write(b & 0xFF);
        }
    }

    /** A symbol: a label (segment and offset) or an equate (an expression). */
    public record Symbol(String name, String section, int offset, Expr value, boolean zeropage) {
        public boolean isLabel() {
            return section != null;
        }
    }

    /** A line of the C source a range of code comes from (from {@code .dbg line}). */
    public record Line(String section, int offset, int size, String file, int line) {}

    /** A function from {@code .dbg func} (the name in C, its type string, its label). */
    public record DbgFunc(String name, String type, String label) {}

    /** A static variable of a function from {@code .dbg sym} (in the function opened last). */
    public record DbgSym(String function, String name, String type, String label) {}

    /** The bytes one line of this file made: line number, segment, offset, size. */
    public record AsmLine(int line, String section, int offset, int size) {}

    /** A {@code .proc}: its name (full), segment, where its code starts, how many bytes it has. */
    public record Proc(String name, String section, int offset, int size) {}

    public final String name;
    public final Map<String, Section> sections = new LinkedHashMap<>();
    public final Map<String, Symbol> symbols = new LinkedHashMap<>();
    public final Set<String> exports = new LinkedHashSet<>();
    public final Set<String> imports = new LinkedHashSet<>();
    public final List<Line> lines = new ArrayList<>();
    /** {@code .dbg file} names with size and modification time. */
    public final Map<String, long[]> dbgFiles = new LinkedHashMap<>();
    public final List<DbgFunc> dbgFuncs = new ArrayList<>();
    public final List<DbgSym> dbgSyms = new ArrayList<>();
    public final List<AsmLine> asmLines = new ArrayList<>();
    /** The line of this file where each symbol is defined. */
    public final Map<String, Integer> defLine = new LinkedHashMap<>();
    public final List<Proc> procs = new ArrayList<>();

    ObjectFile(String name) {
        this.name = name;
    }

    Section section(String name) {
        return sections.computeIfAbsent(name, Section::new);
    }
}
