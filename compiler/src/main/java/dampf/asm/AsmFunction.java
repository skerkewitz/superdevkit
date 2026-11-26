package dampf.asm;

import dampf.frontend.CompileError;
import dampf.frontend.SourcePos;
import dampf.target.Cycles65816;
import dampf.target.SnesTarget;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a function written by hand in 65816 assembly (DAMPF_ASM, docs/hints.md) and checks it
 * against dampf's calling convention: what dampf needs to call it like a compiled leaf function.
 *
 * <p>The file is assembled with dampf's own assembler ({@link Assembler}, the part of ca65's syntax
 * it reads); the analysis then follows the machine code from the function's label: its control
 * flow from the branches and jumps the assembler resolved, the accumulator and index widths
 * through {@code SEP}, {@code REP}, {@code PHP} and {@code PLP}, and what is on the stack. It
 * rejects
 * <ul>
 * <li>calls ({@code JSR}, {@code JSL}), jumps out of the function's segment, indirect jumps, and
 *     any return but {@code RTL};</li>
 * <li>an {@code RTL} with another CPU state than at the entry: A, X and Y 16 bit, DBR and D as
 *     they were (DBR {@link SnesTarget#DATA_BANK}, D 0), the interrupt flag as it was, decimal mode off, nothing left on
 *     the stack;</li>
 * <li>a place of the frames other than its own: its parameters ({@code _<f>__<p>}) and its locals
 *     ({@code _<f>__locals+n}), each within its bytes; the runtime's scratch registers
 *     ({@code dampf_regs}, {@code dampf_ptr}), globals and I/O registers are allowed;</li>
 * <li>a backward branch or jump without a bound, written as a comment on its line:
 *     {@code ; dampf: at most N} (the loop that starts at its target runs at most N rounds each
 *     time it is entered);</li>
 * <li>an immediate assembled for another width than the register has there (the {@code .a8},
 *     {@code .a16}, {@code .i8}, {@code .i16} directives must follow the {@code SEP} and
 *     {@code REP}).</li>
 * </ul>
 * From the same walk it computes the best and the worst case in CPU cycles with the cycle table
 * of the C code ({@link Cycles65816}), loops collapsed innermost first as in the code generator's
 * timing, and the bytes of locals the code uses.
 */
public final class AsmFunction {
    /** The suffix of the symbol of an assembly function's own locals: {@code _<f>__locals}. */
    public static final String LOCALS = "__locals";

    /**
     * The bytes of a parameter's place in the convention: 2 for 8 and 16 bits (an 8-bit value in
     * the low byte), 4 for 32 bits and pointers (address, bank, 0).
     */
    public static int paramBytes(int size) {
        return size <= 2 ? 2 : 4;
    }

    /** A parameter: the symbol of its place and how many bytes the place has. */
    public record Param(String symbol, int bytes) {}

    /**
     * What dampf knows of the function: its label, its parameters in order, and the symbols of
     * places in other functions' frames (which the code must not touch).
     */
    public record Spec(String name, String label, List<Param> params, Set<String> otherFrames) {}

    /** What the analysis found: the bytes of locals the code uses, the best and worst case in CPU cycles. */
    public record Result(String label, int localsBytes, long best, long worst) {}

    private static final Pattern ASSEMBLER_ERROR = Pattern.compile("^[^:]*:(\\d+): (.*)$", Pattern.DOTALL);
    private static final Pattern BOUND = Pattern.compile("dampf:\\s*at most\\s+(\\d+)\\b");
    private static final Pattern LABEL_AND_CODE = Pattern.compile("^((?:@?[A-Za-z_][A-Za-z0-9_]*)?):\\s+([a-zA-Z].*)$");
    private static final Set<String> BRANCHES = Set.of("bpl", "bmi", "bvc", "bvs", "bcc", "bcs", "bne", "beq");
    private static final Set<String> INDEX_OPS = Set.of("ldx", "ldy", "stx", "sty", "cpx", "cpy");

    private final String file;
    private final String[] source;
    private final Spec spec;
    private final ObjectFile obj;
    private ObjectFile.Section section;
    private final Map<Integer, ObjectFile.AsmLine> lineAt = new HashMap<>();
    private final Map<Integer, ObjectFile.Fixup> fixupAt = new HashMap<>();
    private final Map<String, Param> params = new HashMap<>();
    private int localsEnd;

    private AsmFunction(String file, String text, Spec spec, ObjectFile obj) {
        this.file = file;
        this.source = text.split("\n", -1);
        this.spec = spec;
        this.obj = obj;
        for (Param p : spec.params()) {
            params.put(p.symbol(), p);
        }
    }

    /**
     * Assembles {@code text} (the file {@code file}, for messages) and analyses the function
     * {@code spec} in it.
     */
    public static Result analyse(String file, String text, Spec spec) throws CompileError {
        return new AsmFunction(file, text, spec, assemble(file, text)).run();
    }

    /** The file assembled on its own; what the assembler does not read is a compile error at its line. */
    public static ObjectFile assemble(String file, String text) throws CompileError {
        try {
            return Assembler.assemble(file, text, Map.of());
        } catch (IllegalArgumentException e) {
            Matcher m = ASSEMBLER_ERROR.matcher(e.getMessage() == null ? "" : e.getMessage());
            int line = m.matches() ? Integer.parseInt(m.group(1)) : 1;
            String why = m.matches() ? m.group(2) : String.valueOf(e.getMessage());
            throw new CompileError(new SourcePos(file, line, 1), why
                    + " (dampf's assembler reads the part of ca65 that dampf writes: instructions, labels, "
                    + ".proc, .segment, .import, .export, .byte, .word, .res, equates, .a8/.a16/.i8/.i16; "
                    + "no macros, no .include)");
        }
    }

    // ------------------------------------------------------------------ the state of the CPU

    /** A byte on the stack: what pushed it, and for P, DBR and D the state it holds. */
    private record Token(char kind, int data) {}

    private static final int ORIGINAL = 0;
    private static final int CHANGED = 1;
    private static final int SET = 1;
    private static final int CLEAR = 2;

    /** What the analysis follows: widths, the stack, DBR, D, the interrupt and decimal flags. */
    private record State(boolean m16, boolean x16, List<Token> stack, int dbr, int d, int i, int dec) {
        State with(boolean m, boolean x) {
            return new State(m, x, stack, dbr, d, i, dec);
        }

        State flags(boolean m, boolean x, int newI, int newDec) {
            return new State(m, x, stack, dbr, d, newI, newDec);
        }

        State push(List<Token> bytes) {
            List<Token> s = new ArrayList<>(stack);
            s.addAll(bytes);
            return new State(m16, x16, List.copyOf(s), dbr, d, i, dec);
        }

        State popped(int n) {
            return new State(m16, x16, List.copyOf(stack.subList(0, stack.size() - n)), dbr, d, i, dec);
        }

        State dbr(int v) {
            return new State(m16, x16, stack, v, d, i, dec);
        }

        State d(int v) {
            return new State(m16, x16, stack, dbr, v, i, dec);
        }

        String describe() {
            return "A " + (m16 ? 16 : 8) + " bit, X/Y " + (x16 ? 16 : 8) + " bit, " + stack.size() + " byte(s) pushed";
        }
    }

    private static final State ENTRY = new State(true, true, List.of(), ORIGINAL, ORIGINAL, ORIGINAL, ORIGINAL);

    // ------------------------------------------------------------------ the walk

    /** A way out of an instruction: to another instruction (an offset) or the return (-1), with its cycles. */
    private record Edge(int to, long best, long worst) {}

    private static final int RET = -1;

    private final Map<Integer, State> stateAt = new TreeMap<>();
    private final Map<Integer, List<Edge>> edges = new TreeMap<>();

    private Result run() throws CompileError {
        ObjectFile.Symbol entry = obj.symbols.get(spec.label());
        if (entry == null || !entry.isLabel()) {
            throw error(1, "the file does not define the label " + spec.label() + " of '" + spec.name()
                    + "' (DAMPF_ASM: the function's code starts at its label)");
        }
        int entryLine = obj.defLine.getOrDefault(spec.label(), 1);
        if (!obj.exports.contains(spec.label())) {
            throw error(entryLine, "the file does not export " + spec.label() + " (.export " + spec.label() + ")");
        }
        section = obj.sections.get(entry.section());
        if (!section.name.startsWith("C_")) {
            throw error(entryLine, spec.label() + " is in the segment " + section.name
                    + "; code goes into a segment of its own named C_..., by convention .segment \"C" + spec.label() + "\"");
        }
        for (ObjectFile.AsmLine l : obj.asmLines) {
            if (l.section().equals(section.name)) {
                lineAt.put(l.offset(), l);
            }
        }
        for (ObjectFile.Fixup f : section.fixups) {
            fixupAt.put(f.offset(), f);
        }
        walk(entry.offset());
        long[] t = timing(entry.offset());
        return new Result(spec.label(), localsEnd, t[0], t[1]);
    }

    private void walk(int start) throws CompileError {
        Deque<Integer> work = new ArrayDeque<>();
        stateAt.put(start, ENTRY);
        work.add(start);
        byte[] bytes = section.bytes();
        while (!work.isEmpty()) {
            int at = work.removeFirst();
            State s = stateAt.get(at);
            ObjectFile.AsmLine line = lineAt.get(at);
            if (line == null || at >= bytes.length) {
                throw error(lastLineBefore(at), "the code runs past the end of its segment without an RTL");
            }
            if (isData(line.line())) {
                throw error(line.line(), "the code runs into data");
            }
            int op = bytes[at] & 0xFF;
            String name = Cycles65816.name(op);
            int sp = name.indexOf(' ');
            String mnemonic = sp < 0 ? name : name.substring(0, sp);
            String mode = sp < 0 ? "" : name.substring(sp + 1);
            int length = Cycles65816.length(op, s.m16(), s.x16());
            if (length != line.size()) {
                throw error(line.line(), "the immediate is assembled with " + (line.size() - 1) + " byte(s), but "
                        + (isIndexOp(mnemonic) ? "X and Y are " + (s.x16() ? 16 : 8) : "A is " + (s.m16() ? 16 : 8))
                        + " bit here: the .a8/.a16 and .i8/.i16 directives must follow SEP and REP");
            }
            List<Edge> out = new ArrayList<>();
            State next = step(at, line.line(), op, mnemonic, mode, s, bytes);
            long c = Cycles65816.cycles(op, s.m16(), s.x16(), false);
            long worst = c + (!s.x16() && Cycles65816.pageCrossing(op) ? 1 : 0);
            switch (mnemonic) {
                case "rtl" -> {
                    returns(line.line(), s);
                    out.add(new Edge(RET, c, c));
                }
                case "bra", "brl" -> out.add(new Edge(target(at, line.line()), c, c));
                case "jmp", "jml" -> {
                    if (!mode.equals("abs") && !mode.equals("long")) {
                        throw error(line.line(), "an indirect jump, which dampf cannot follow; an assembly function "
                                + "has to show its control flow (branches and JMP to labels)");
                    }
                    out.add(new Edge(target(at, line.line()), c, c));
                }
                default -> {
                    if (BRANCHES.contains(mnemonic)) {
                        long taken = Cycles65816.cycles(op, s.m16(), s.x16(), true);
                        out.add(new Edge(target(at, line.line()), taken, taken));
                    }
                    out.add(new Edge(at + length, c, worst));
                }
            }
            edges.put(at, out);
            for (Edge e : out) {
                if (e.to() == RET) {
                    continue;
                }
                State known = stateAt.get(e.to());
                if (known == null) {
                    stateAt.put(e.to(), next);
                    work.add(e.to());
                } else if (!known.equals(next)) {
                    ObjectFile.AsmLine l = lineAt.get(e.to());
                    throw error(l == null ? line.line() : l.line(), "the ways that meet here come with different states ("
                            + known.describe() + ", and from line " + line.line() + ": " + next.describe()
                            + "); dampf needs the register widths and the stack the same on every way");
                }
            }
        }
    }

    private static boolean isIndexOp(String mnemonic) {
        return INDEX_OPS.contains(mnemonic);
    }

    /** The state after the instruction at {@code at}; rejects what an assembly function may not do. */
    private State step(int at, int line, int op, String mnemonic, String mode, State s, byte[] bytes) throws CompileError {
        switch (mnemonic) {
            case "jsr", "jsl" -> throw error(line, "calls " + operandText(at, mode, bytes)
                    + ": an assembly function is a leaf and calls nothing (DAMPF_ASM)");
            case "rts", "rti" -> throw error(line, mnemonic.toUpperCase(java.util.Locale.ROOT)
                    + ": dampf calls the function with JSL, so it returns with RTL");
            case "brk", "cop", "stp", "wai", "wdm" -> throw error(line, mnemonic.toUpperCase(java.util.Locale.ROOT)
                    + " is not allowed in an assembly function");
            case "xce" -> throw error(line, "XCE: the function runs in native mode and stays there");
            case "mvn", "mvp" -> throw error(line, mnemonic.toUpperCase(java.util.Locale.ROOT)
                    + " is not supported in an assembly function yet (its length and its target bank are not followed); "
                    + "write a loop with a bound");
            case "txs", "tcs" -> throw error(line, mnemonic.toUpperCase(java.util.Locale.ROOT)
                    + " sets the stack pointer, which dampf cannot follow");
            case "sep", "rep" -> {
                int v = immediate(at, line, bytes, mnemonic);
                boolean on = mnemonic.equals("sep");
                boolean m = (v & 0x20) != 0 ? !on : s.m16();
                boolean x = (v & 0x10) != 0 ? !on : s.x16();
                int i = (v & 0x04) != 0 ? (on ? SET : CLEAR) : s.i();
                int dec = (v & 0x08) != 0 ? (on ? SET : ORIGINAL) : s.dec();
                return s.flags(m, x, i, dec);
            }
            case "sei" -> {
                return s.flags(s.m16(), s.x16(), SET, s.dec());
            }
            case "cli" -> {
                return s.flags(s.m16(), s.x16(), CLEAR, s.dec());
            }
            case "sed" -> {
                return s.flags(s.m16(), s.x16(), s.i(), SET);
            }
            case "cld" -> {
                return s.flags(s.m16(), s.x16(), s.i(), ORIGINAL);
            }
            case "php" -> {
                return s.push(List.of(new Token('P', (s.m16() ? 1 : 0) | (s.x16() ? 2 : 0) | s.i() << 2 | s.dec() << 4)));
            }
            case "plp" -> {
                Token t = pull(line, s, 1).getFirst();
                if (t.kind() != 'P') {
                    throw error(line, "PLP pulls a byte that PHP did not push: dampf cannot follow the register widths");
                }
                return s.popped(1).flags((t.data() & 1) != 0, (t.data() & 2) != 0, (t.data() >> 2) & 3, (t.data() >> 4) & 3);
            }
            case "pha" -> {
                return s.push(other(s.m16() ? 2 : 1));
            }
            case "phx", "phy" -> {
                return s.push(other(s.x16() ? 2 : 1));
            }
            case "phb" -> {
                return s.push(List.of(new Token('B', s.dbr())));
            }
            case "phd" -> {
                return s.push(List.of(new Token('D', s.d()), new Token('D', s.d())));
            }
            case "phk" -> {
                return s.push(other(1));
            }
            case "pea", "pei", "per" -> {
                if (mnemonic.equals("pei")) {
                    access(at, line, mode, 2, false, s, bytes);
                }
                return s.push(other(2));
            }
            case "pla" -> {
                pull(line, s, s.m16() ? 2 : 1);
                return s.popped(s.m16() ? 2 : 1);
            }
            case "plx", "ply" -> {
                pull(line, s, s.x16() ? 2 : 1);
                return s.popped(s.x16() ? 2 : 1);
            }
            case "plb" -> {
                Token t = pull(line, s, 1).getFirst();
                return s.popped(1).dbr(t.kind() == 'B' ? t.data() : CHANGED);
            }
            case "pld" -> {
                List<Token> t = pull(line, s, 2);
                boolean restored = t.get(0).kind() == 'D' && t.get(1).kind() == 'D' && t.get(0).data() == t.get(1).data();
                return s.popped(2).d(restored ? t.get(0).data() : CHANGED);
            }
            case "tcd" -> {
                return s.d(CHANGED);
            }
            default -> {
                if (!mode.isEmpty() && !mode.equals("a") && !mode.equals("#imm") && !BRANCHES.contains(mnemonic)
                        && !mnemonic.equals("bra") && !mnemonic.equals("brl") && !mnemonic.startsWith("j")) {
                    int width = isIndexOp(mnemonic) ? (s.x16() ? 2 : 1) : (s.m16() ? 2 : 1);
                    access(at, line, mode, width, true, s, bytes);
                }
                return s;
            }
        }
    }

    private static List<Token> other(int n) {
        List<Token> l = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            l.add(new Token('O', 0));
        }
        return l;
    }

    /** The top n bytes of the stack (the one pulled first last); an error if fewer were pushed. */
    private List<Token> pull(int line, State s, int n) throws CompileError {
        if (s.stack().size() < n) {
            throw error(line, "pulls more than the function pushed: below it is the return address");
        }
        return s.stack().subList(s.stack().size() - n, s.stack().size());
    }

    /** The checks at an RTL: the state as it was at the entry. */
    private void returns(int line, State s) throws CompileError {
        if (!s.stack().isEmpty()) {
            throw error(line, "returns with " + s.stack().size() + " byte(s) still on the stack");
        }
        if (!s.m16() || !s.x16()) {
            throw error(line, "returns with " + (!s.m16() ? "an 8-bit accumulator" : "8-bit index registers")
                    + "; dampf's code expects A, X and Y 16 bit (REP #$30)");
        }
        if (s.dbr() != ORIGINAL) {
            throw error(line, "returns with another data bank; DBR must be $" + String.format("%02X", SnesTarget.DATA_BANK)
                    + " again (PHB ... PLB)");
        }
        if (s.d() != ORIGINAL) {
            throw error(line, "returns with another direct page; D must be 0 again (PHD ... PLD)");
        }
        if (s.i() != ORIGINAL) {
            throw error(line, "returns with the interrupt flag changed (SEI/CLI); keep it with PHP ... PLP");
        }
        if (s.dec() != ORIGINAL) {
            throw error(line, "returns in decimal mode (CLD)");
        }
    }

    /** The number of an immediate operand (SEP, REP). */
    private int immediate(int at, int line, byte[] bytes, String mnemonic) throws CompileError {
        if (fixupAt.containsKey(at + 1)) {
            throw error(line, mnemonic.toUpperCase(java.util.Locale.ROOT) + " needs a number, so that dampf can follow the register widths");
        }
        return bytes[at + 1] & 0xFF;
    }

    /** The offset in the segment a branch or jump goes to; an error if it leaves the segment. */
    private int target(int at, int line) throws CompileError {
        ObjectFile.Fixup f = fixupAt.get(at + 1);
        Long t = f == null ? null : f.expr().eval(this::offsetInSection);
        if (t == null) {
            throw error(line, "jumps out of the function's segment " + section.name
                    + ": an assembly function calls nothing and ends with RTL");
        }
        return (int) (long) t;
    }

    /** The offset of a label of the function's segment (also through equates of the file), else null. */
    private Long offsetInSection(String name) {
        ObjectFile.Symbol s = obj.symbols.get(name);
        if (s == null) {
            return null;
        }
        if (s.isLabel()) {
            return s.section().equals(section.name) ? (long) s.offset() : null;
        }
        return s.value().eval(this::offsetInSection);
    }

    private String operandText(int at, String mode, byte[] bytes) {
        ObjectFile.Fixup f = fixupAt.get(at + 1);
        if (f != null) {
            return Printer.text(f.expr());
        }
        int n = Cycles65816.length(bytes[at] & 0xFF, true, true) - 1;
        long v = 0;
        for (int k = 0; k < n && at + 1 + k < bytes.length; k++) {
            v |= (long) (bytes[at + 1 + k] & 0xFF) << (8 * k);
        }
        return "$" + Long.toHexString(v).toUpperCase(java.util.Locale.ROOT) + (mode.isEmpty() ? "" : " (" + mode + ")");
    }

    // ------------------------------------------------------------------ what the code touches

    /**
     * Checks a memory access: {@code width} bytes at the operand (for the indirect modes, the
     * pointer's bytes in the direct page). {@code data}: width is the data's (else the operand
     * itself, as for PEI).
     */
    private void access(int at, int line, String mode, int width, boolean data, State s, byte[] bytes) throws CompileError {
        if (mode.equals("sr,s") || mode.equals("(sr,s),y")) {
            int n = bytes[at + 1] & 0xFF;
            int bytesThere = mode.equals("sr,s") ? width : 2;
            if (fixupAt.containsKey(at + 1) || n < 1 || n + bytesThere - 1 > s.stack().size()) {
                throw error(line, "reaches into the stack beyond what the function pushed (the return address and the caller's)");
            }
            return;
        }
        boolean pointer = mode.startsWith("(") || mode.startsWith("[");
        int n = pointer ? (mode.startsWith("[") ? 3 : 2) : width;
        boolean indexed = !pointer && (mode.endsWith(",x") || mode.endsWith(",y")) || mode.equals("(dp,x)");
        boolean direct = mode.startsWith("dp") || pointer;
        if (direct && s.d() != ORIGINAL) {
            throw error(line, "uses the direct page while D is not 0");
        }
        ObjectFile.Fixup f = fixupAt.get(at + 1);
        if (f == null) {
            int size = mode.startsWith("long") ? 3 : direct ? 1 : 2;
            long v = 0;
            for (int k = 0; k < size; k++) {
                v |= (long) (bytes[at + 1 + k] & 0xFF) << (8 * k);
            }
            fixedAddress(line, v, size);
            return;
        }
        place(line, resolve(f.expr()), n, indexed);
    }

    /** A fixed address: the frames' area and the stack are not the function's. */
    private void fixedAddress(int line, long v, int size) throws CompileError {
        long low;
        if (size == 3) {
            if (!SnesTarget.inLowRam(v)) {
                return;
            }
            low = v & 0xFFFF;
        } else {
            low = v;
        }
        if (low >= SnesTarget.FRAME_START && low < SnesTarget.STACK) {
            throw error(line, "a fixed address in the frames (" + range(SnesTarget.FRAME_START, SnesTarget.STACK)
                    + "): an assembly function uses its parameters ("
                    + String.join(", ", spec.params().stream().map(Param::symbol).toList()) + ") and "
                    + spec.label() + LOCALS + "+n");
        }
        if (low >= SnesTarget.STACK && low < SnesTarget.LOW_RAM_END) {
            throw error(line, "a fixed address in the stack (" + range(SnesTarget.STACK, SnesTarget.LOW_RAM_END) + ")");
        }
    }

    /** "$0020-$1DFF": the addresses from {@code start} up to {@code end}, without it. */
    private static String range(int start, int end) {
        return String.format("$%04X-$%04X", start, end - 1);
    }

    /** The expression with the file's equates put in (labels and imported names stay). */
    private Expr resolve(Expr e) {
        return e.mapSymbols(name -> {
            ObjectFile.Symbol s = obj.symbols.get(name);
            return s != null && !s.isLabel() ? resolve(s.value()) : new Expr.Sym(name);
        });
    }

    /** Checks an access to {@code n} bytes at a symbolic address. */
    private void place(int line, Expr e, int n, boolean indexed) throws CompileError {
        List<String> names = new ArrayList<>();
        collect(e, names);
        String own = null;
        for (String name : names) {
            if (obj.symbols.containsKey(name)) {
                continue;   // a label of the file: its own data
            }
            if (name.equals("dampf_frame")) {
                throw error(line, "reaches into the frames (dampf_frame); an assembly function uses its parameters and "
                        + spec.label() + LOCALS);
            }
            if (spec.otherFrames().contains(name)) {
                throw error(line, "touches " + name + ", a place in another function's frame");
            }
            if (params.containsKey(name) || name.equals(spec.label() + LOCALS)) {
                own = name;
            }
        }
        if (own == null) {
            return;   // a global, an I/O register, the scratch registers
        }
        if (names.size() > 1) {
            throw error(line, "an address made of " + own + " and other symbols: dampf cannot check it");
        }
        if (indexed) {
            throw error(line, "an indexed access to " + own + ": dampf cannot check which bytes of the frame it reaches;"
                    + " keep arrays in a segment of the file (BSS) instead");
        }
        String found = own;
        Long k = e.eval(x -> x.equals(found) ? 0L : null);
        if (k == null || k < 0) {
            throw error(line, "an address before " + own + ", outside the function's own places");
        }
        Param p = params.get(own);
        if (p != null) {
            if (k + n > p.bytes()) {
                throw error(line, "reaches beyond the " + p.bytes() + " bytes of the parameter " + own
                        + " (an access of " + n + " byte(s) at offset " + k + ")");
            }
            return;
        }
        localsEnd = (int) Math.max(localsEnd, k + n);
    }

    private static void collect(Expr e, List<String> names) {
        switch (e) {
            case Expr.Sym s -> {
                if (!names.contains(s.name())) {
                    names.add(s.name());
                }
            }
            case Expr.Unary u -> collect(u.e(), names);
            case Expr.Binary b -> {
                collect(b.a(), names);
                collect(b.b(), names);
            }
            case Expr.Num n -> { }
        }
    }

    // ------------------------------------------------------------------ cycles

    /** A cost interval in CPU cycles. */
    private record Bound(long best, long worst) {
        Bound plus(Bound o) {
            return new Bound(best + o.best, worst + o.worst);
        }

        Bound union(Bound o) {
            return new Bound(Math.min(best, o.best), Math.max(worst, o.worst));
        }
    }

    /** A loop: its head, its last instruction (the last branch back), its bound in rounds. */
    private record Loop(int head, int end, long rounds, int line) {
        boolean contains(int at) {
            return at >= head && at <= end;
        }
    }

    /** An instruction, or a loop collapsed into one node (entered at its head). */
    private static final class Node {
        final int entry;
        final Map<Integer, Bound> out = new LinkedHashMap<>();

        Node(int entry) {
            this.entry = entry;
        }
    }

    private long[] timing(int start) throws CompileError {
        List<Loop> loops = loops();
        for (Loop l : loops) {
            if (start > l.head() && start <= l.end()) {
                throw error(l.line(), "the function's label " + spec.label() + " lies inside this loop; a loop is entered at its first instruction");
            }
        }
        Map<Integer, Node> node = new HashMap<>();
        for (Map.Entry<Integer, List<Edge>> e : edges.entrySet()) {
            Node n = new Node(e.getKey());
            for (Edge x : e.getValue()) {
                n.out.merge(x.to(), new Bound(x.best(), x.worst()), Bound::union);
            }
            node.put(e.getKey(), n);
        }
        // innermost first: the smaller of two nested loops
        loops.sort((a, b) -> Integer.compare(a.end() - a.head(), b.end() - b.head()));
        for (Loop loop : loops) {
            Node head = node.get(loop.head());
            Map<Integer, Bound> paths = paths(head, loop, node);
            Bound round = paths.remove(loop.head());
            Node collapsed = new Node(loop.head());
            for (Map.Entry<Integer, Bound> e : paths.entrySet()) {
                Bound out = e.getValue();
                long worst = round == null ? out.worst() : (loop.rounds() - 1) * round.worst() + out.worst();
                collapsed.out.put(e.getKey(), new Bound(out.best(), worst));
            }
            for (int at : edges.keySet()) {
                if (loop.contains(at)) {
                    node.put(at, collapsed);
                }
            }
        }
        Map<Integer, Bound> all = paths(node.get(start), null, node);
        Bound r = all.get(RET);
        if (r == null) {
            throw error(lineAt.get(start).line(), "the function never returns: no way ends in RTL");
        }
        return new long[] {r.best(), r.worst()};
    }

    /**
     * Shortest and longest ways from {@code start} to everything outside {@code loop} (or
     * everything, if null) and, for a loop, back to its head. Inner loops are collapsed and every
     * other edge goes forward, so the nodes in the order of their addresses are in topological order.
     */
    private Map<Integer, Bound> paths(Node start, Loop loop, Map<Integer, Node> node) {
        Map<Node, Bound> arrive = new IdentityHashMap<>();
        arrive.put(start, new Bound(0, 0));
        Map<Integer, Bound> result = new LinkedHashMap<>();
        List<Node> order = new ArrayList<>();
        Set<Node> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int at : edges.keySet()) {
            Node n = node.get(at);
            if (n.entry >= start.entry && (loop == null || loop.contains(at)) && seen.add(n)) {
                order.add(n);
            }
        }
        order.sort((a, b) -> Integer.compare(a.entry, b.entry));
        for (Node u : order) {
            Bound a = arrive.get(u);
            if (a == null) {
                continue;
            }
            for (Map.Entry<Integer, Bound> e : u.out.entrySet()) {
                Bound c = a.plus(e.getValue());
                int target = e.getKey();
                Node v = target == RET ? null : node.get(target);
                if (v == start && loop != null) {
                    result.merge(start.entry, c, Bound::union);   // a way around the loop
                } else if (v == u) {
                    continue;   // a collapsed inner loop's way back to itself, counted inside it
                } else if (v != null && (loop == null || loop.contains(target))) {
                    if (v.entry <= u.entry) {
                        throw new IllegalStateException("a backward edge that is no loop's: " + u.entry + " -> " + target);
                    }
                    arrive.merge(v, c, Bound::union);
                } else {
                    result.merge(target == RET ? RET : v.entry, c, Bound::union);
                }
            }
        }
        return result;
    }

    /**
     * The loops: every backward branch or jump needs a bound in a comment on its line; the loops
     * of one head are one loop (the smallest bound counts); loops nest and are entered at their
     * head only.
     */
    private List<Loop> loops() throws CompileError {
        Map<Integer, Loop> byHead = new TreeMap<>();
        for (Map.Entry<Integer, List<Edge>> e : edges.entrySet()) {
            for (Edge x : e.getValue()) {
                if (x.to() == RET || x.to() > e.getKey()) {
                    continue;
                }
                int line = lineAt.get(e.getKey()).line();
                long rounds = bound(line);
                Loop old = byHead.get(x.to());
                Loop loop = new Loop(x.to(), Math.max(e.getKey(), old == null ? e.getKey() : old.end()),
                        old == null ? rounds : Math.min(rounds, old.rounds()), old == null ? line : old.line());
                byHead.put(x.to(), loop);
            }
        }
        List<Loop> loops = new ArrayList<>(byHead.values());
        for (Loop a : loops) {
            for (Loop b : loops) {
                boolean disjoint = a.end() < b.head() || b.end() < a.head();
                boolean nested = a.contains(b.head()) && a.contains(b.end()) || b.contains(a.head()) && b.contains(a.end());
                if (!disjoint && !nested) {
                    throw error(b.line(), "this loop overlaps the loop of line " + a.line()
                            + " without lying inside it; dampf times loops that nest");
                }
            }
        }
        for (Map.Entry<Integer, List<Edge>> e : edges.entrySet()) {
            for (Edge x : e.getValue()) {
                for (Loop l : loops) {
                    if (x.to() != RET && x.to() != l.head() && l.contains(x.to()) && !l.contains(e.getKey())) {
                        throw error(lineAt.get(e.getKey()).line(), "jumps into the middle of the loop of line " + l.line()
                                + "; a loop is entered at its first instruction");
                    }
                }
            }
        }
        return loops;
    }

    /** The bound in the comment of a backward branch's line. */
    private long bound(int line) throws CompileError {
        String comment = comment(source[line - 1]);
        Matcher m = comment == null ? null : BOUND.matcher(comment);
        if (m == null || !m.find()) {
            throw error(line, "a loop without a bound: write how many rounds it runs at most next to the branch back, "
                    + "as '; dampf: at most 16'");
        }
        long n = Long.parseLong(m.group(1));
        if (n < 1) {
            throw error(line, "a loop runs at least once: 'dampf: at most " + n + "'");
        }
        return n;
    }

    private static String comment(String line) {
        boolean quoted = false;
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == '"' || c == '\'') {
                quoted = !quoted;
            } else if (c == ';' && !quoted) {
                return line.substring(k + 1);
            }
        }
        return null;
    }

    /** Whether the line holds data (.byte, .word, .res), not an instruction. */
    private boolean isData(int line) {
        String text = source[line - 1];
        String c = comment(text);
        String code = (c == null ? text : text.substring(0, text.length() - c.length() - 1)).strip();
        Matcher m = LABEL_AND_CODE.matcher(code);
        if (m.matches()) {
            code = m.group(2);
        } else if (code.contains(":") && !code.startsWith(".")) {
            code = code.substring(code.indexOf(':') + 1).strip();
        }
        return code.startsWith(".");
    }

    private int lastLineBefore(int at) {
        int best = 1;
        int bestOffset = -1;
        for (ObjectFile.AsmLine l : lineAt.values()) {
            if (l.offset() < at && l.offset() > bestOffset) {
                best = l.line();
                bestOffset = l.offset();
            }
        }
        return best;
    }

    private CompileError error(int line, String message) {
        return new CompileError(new SourcePos(file, line, 1), message);
    }

    /** Expressions as text, for messages. */
    private static final class Printer {
        private Printer() {}

        static String text(Expr e) {
            return switch (e) {
                case Expr.Num n -> "$" + Long.toHexString(n.value()).toUpperCase(java.util.Locale.ROOT);
                case Expr.Sym s -> s.name();
                case Expr.Unary u -> u.op() + text(u.e());
                case Expr.Binary b -> text(b.a()) + b.op() + text(b.b());
            };
        }
    }

    /** The symbols an expression names (for tests and messages). */
    static Set<String> names(Expr e) {
        List<String> l = new ArrayList<>();
        collect(e, l);
        return new HashSet<>(l);
    }
}
