package dampf.backend;

import dampf.ast.Children;
import dampf.ast.Expr;
import dampf.ast.Function;
import dampf.ast.Init;
import dampf.ast.Program;
import dampf.ast.Stmt;
import dampf.ast.Type;
import dampf.ast.Variable;
import dampf.frontend.CompileError;
import dampf.frontend.SourcePos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The naive code generator: the reference that every optimized build is compared against.
 * It is meant to be obviously correct, not fast.
 *
 * <p><b>Machine state.</b> A, X and Y are always 16 bit (M=16 X=16); 8-bit stores switch M for
 * one instruction. DBR is $7E: variables are addressed with 16-bit addresses, ROM data with
 * 24-bit long addresses (docs/memory-map.md). Every variable has a fixed address: globals, and
 * also parameters, locals and temporaries ("static frames"), which is correct because there is
 * no recursion. Parameters are stored into the callee's frame; a result comes back in A (in A
 * and X, low and high word, for 32 bits and pointers).
 *
 * <p><b>ROM banks.</b> Every function and every constant object gets its own segment
 * ({@code C<label>}, {@code R<label>}), which the ROM builder packs into 32 KB LoROM banks, so
 * no function and no object crosses a bank boundary. Calls are {@code JSL}/{@code RTL}, because
 * caller and callee may be in different banks.
 *
 * <p><b>Values.</b> An expression is evaluated in one of two ways:
 * <ul>
 * <li>{@link #genA}: A holds the low 16 bits of the value. For an 8-bit type A holds the value
 *     zero- or sign-extended to 16 bits.</li>
 * <li>{@link #gen32}: the full 32-bit value in a 4-byte slot in RAM. Pointers are 32-bit values
 *     too: the address in the low word, the bank in the third byte.</li>
 * </ul>
 * C computes in 32-bit {@code int} after the integer promotions. The low 16 bits of the result
 * of {@code + - * & | ^ ~ <<} and unary minus depend only on the low 16 bits of the operands, so
 * these are computed in 16 bits whenever only the low 16 bits are wanted. Everything else
 * ({@code / % >>}, comparisons, truth tests of 32-bit values) is computed on the full 32 bits,
 * except when both operands are known to fit into the same 16-bit range (from their types),
 * where the 16-bit operation gives the same result.
 *
 * <p><b>Objects in memory</b> are reached through a {@link Place}: a fixed address, a fixed
 * address plus an index (through X), or a pointer (copied into the direct page, through
 * {@code [dp],y}). No object crosses a bank boundary, so address arithmetic is 16-bit.
 *
 * <p><b>Temporaries</b> are 4-byte slots in the function's frame. They live for one
 * statement-level expression; the next one reuses them.
 */
public final class NaiveCodeGen {
    private static final String P = "z:dampf_ptr";      // direct page pointer for [dp],y
    private static final String Q = "z:dampf_ptr+4";    // the second one (copy destination)
    private static final String VIA_P = "[dampf_ptr],y";    // ca65 picks direct page addressing for the imported zp symbol
    private static final String VIA_Q = "[dampf_ptr+4],y";

    private final Program program;
    private final Function entry;
    private final java.util.function.Function<String, String> debugName;
    /** Where globals() and frames() write: the RAM part, or the block of one ROM object. */
    private AsmWriter asm;
    /** One block of assembly per banked segment (a function, or a constant object in ROM). */
    private final List<AsmWriter> banked = new ArrayList<>();
    /** The function whose block contains the entry point. */
    private int entryBlock = -1;
    private final Map<String, List<String>> sourceLines = new HashMap<>();
    private final Map<String, Path> sourceFiles = new LinkedHashMap<>();
    private final Map<Function, Integer> tempCount = new HashMap<>();

    private AsmWriter out;
    private Function current;
    private int tempsUsed;
    private int labels;

    /** break and continue targets of the enclosing loops and switches. */
    private record Jumps(String breakLabel, String continueLabel) {}

    private final Deque<Jumps> jumps = new ArrayDeque<>();

    /**
     * @param debugName maps a source file path to the name written into the debug information
     *                  (relative to where the .dbg file goes, so Mesen finds the source)
     */
    public NaiveCodeGen(Program program, Function entry, java.util.function.Function<String, String> debugName) {
        this(program, entry, java.util.Set.of(), debugName);
    }

    /**
     * @param replaced functions that hand-written assembly provides: their parameters and frames
     *                 are generated, their code is not
     */
    public NaiveCodeGen(Program program, Function entry, java.util.Set<String> replaced,
                        java.util.function.Function<String, String> debugName) {
        this.program = program;
        this.entry = entry;
        this.replaced = java.util.Set.copyOf(replaced);
        this.debugName = debugName;
    }

    private final java.util.Set<String> replaced;

    /**
     * The interrupt context of each function an interrupt handler reaches
     * ({@link dampf.check.CallGraph#contexts}, ADR-6FM9TXV4). The naive code has no frames in low
     * RAM: a context's own direct page gives it its own scratch registers, and its functions
     * multiply in software.
     */
    private Map<Function, dampf.ast.Interrupt> contexts = Map.of();

    /** The contexts of the functions interrupt handlers reach, before {@link #generate}. */
    public NaiveCodeGen interrupts(Map<Function, dampf.ast.Interrupt> contextOf) {
        this.contexts = Map.copyOf(contextOf);
        return this;
    }

    /** The interrupt handlers, by interrupt. */
    private Map<dampf.ast.Interrupt, Function> handlers() {
        Map<dampf.ast.Interrupt, Function> h = new java.util.TreeMap<>();
        for (Function f : program.functions()) {
            if (f.interrupt() != null && f.isDefined()) {
                h.put(f.interrupt(), f);
            }
        }
        return h;
    }

    /** The runtime's 16-bit multiplication for the current function: in software in interrupt code. */
    private String mul16() {
        return contexts.containsKey(current) ? "dampf_mul16_soft" : "dampf_mul16";
    }

    /** Where the frame of a function written in assembly starts: dampf_frame, as in the code from MIR. */
    private static final int ASM_FRAME = 0x20;

    /** At most this many segments per assembly module: ca65 allows about 256 per object file. */
    private static final int SEGMENTS_PER_MODULE = 200;

    /**
     * Generates the program as one or more ca65 modules. The first holds the variables and the
     * static frames; every function and every constant object is a segment of its own, and the
     * modules share their labels through {@code .global}.
     */
    public List<String> generate() throws CompileError {
        checkEntry(entry);
        for (Function f : program.functions()) {
            if (f.isDefined() && !replaced.contains(f.name())) {
                AsmWriter code = new AsmWriter();
                function(f, code);
                if (code.unknownInstructions() > 0) {
                    throw new IllegalStateException("the cycle table does not know an instruction in " + f.name());
                }
                if (f == entry) {
                    entryBlock = banked.size();
                }
                banked.add(code);
            }
        }
        AsmWriter ram = new AsmWriter();
        asm = ram;
        globals();
        frames();

        List<String> modules = new ArrayList<>();
        int chunks = Math.max(1, (banked.size() + SEGMENTS_PER_MODULE - 1) / SEGMENTS_PER_MODULE);
        for (int m = 0; m < chunks; m++) {
            AsmWriter w = new AsmWriter();
            prelude(w, m, chunks);
            if (m == 0) {
                w.append(ram);
                w.blank();   // (as when the text was copied in)
                handlerExports(w);
            }
            for (int i = m * SEGMENTS_PER_MODULE; i < Math.min(banked.size(), (m + 1) * SEGMENTS_PER_MODULE); i++) {
                if (i == entryBlock) {
                    w.blank();
                    w.raw("        .export dampf_entry");
                    w.raw("dampf_entry = " + entry.label() + "             ; the runtime calls this after start-up");
                }
                w.append(banked.get(i));
                w.blank();
            }
            dampf.lir.LVerifier.check(w.unit());
            modules.add(w.toString());
        }
        return modules;
    }

    /**
     * The symbols the runtime's interrupt entry stubs import: each handler and its direct page,
     * one page each below the stack ({@link ContextAreas}).
     */
    private void handlerExports(AsmWriter w) {
        Map<dampf.ast.Interrupt, Integer> none = new java.util.EnumMap<>(dampf.ast.Interrupt.class);
        handlers().keySet().forEach(c -> none.put(c, 0));
        Map<dampf.ast.Interrupt, Integer> pages = ContextAreas.pages(none);
        handlers().forEach((c, f) -> {
            w.raw("        .export dampf_" + c.key + "_handler, dampf_" + c.key + "_dp");
            w.raw("dampf_" + c.key + "_handler = " + f.label() + "             ; the runtime's " + c + " entry calls this");
            w.raw("dampf_" + c.key + "_dp = $" + String.format("%04X", pages.get(c)) + "                   ; with the direct page here");
            w.blank();
        });
    }

    /** The entry function must be {@code void name(void)}, or {@code int main(void)}. */
    public static void checkEntry(Function entry) throws CompileError {
        boolean intMain = entry.name().equals("main") && entry.returnType().equals(Type.I32);
        if (!(entry.returnType() instanceof Type.Void || intMain) || !entry.paramTypes().isEmpty()) {
            throw new CompileError(entry.pos(), "the entry function '" + entry.name() + "' must be 'void " + entry.name()
                    + "(void)' (or 'int main(void)', whose result is ignored)");
        }
    }

    /** What every module starts with: CPU settings, imports, the labels all modules share, the source files. */
    private void prelude(AsmWriter w, int module, int modules) {
        w.raw("; Generated by dampf, naive code generator" + (modules > 1 ? " (module " + (module + 1) + " of " + modules + ")" : "") + ".");
        w.raw("; A/X/Y are 16 bit (A goes to 8 bit where SEP #$20 says so), DBR = $7E; every variable has a fixed address (static frames).");
        w.blank();
        w.raw("        .p816");
        w.raw("        .smart  off");
        w.raw("        .a16");
        w.raw("        .i16");
        w.raw("        .importzp dampf_regs, dampf_ptr");
        w.raw("        .import dampf_mul16, dampf_mul32, dampf_udiv16, dampf_udiv32, dampf_sdiv32");
        if (!handlers().isEmpty()) {
            w.raw("        .import dampf_mul16_soft, dampf_mul32_soft");
        }
        if (modules > 1 || !replaced.isEmpty()) {
            // exported by the module that defines a label, imported by the others
            List<String> all = new ArrayList<>();
            for (Function f : program.functions()) {
                if (f.isDefined() && replaced.contains(f.name())) {
                    all.add(f.label());
                    f.params().forEach(v -> all.add(v.label()));
                    all.add(f.label() + dampf.asm.AsmFunction.LOCALS);
                } else if (f.isDefined()) {
                    all.add(f.label());
                    f.params().forEach(v -> all.add(v.label()));
                    f.locals().forEach(v -> all.add(v.label()));
                    for (int i = 0; i < tempCount.getOrDefault(f, 0); i++) {
                        all.add(tempLabel(f, i));
                    }
                }
            }
            program.globals().forEach(v -> all.add(v.label()));
            for (int i = 0; i < all.size(); i += 8) {
                w.raw("        .global " + String.join(", ", all.subList(i, Math.min(all.size(), i + 8))));
            }
        }
        for (Map.Entry<String, Path> e : sourceFiles.entrySet()) {
            w.raw(fileDirective(e.getKey(), e.getValue()));
        }
        w.blank();
        w.raw("R0  = dampf_regs+0                  ; runtime routine registers (dampf_runtime.s)");
        w.raw("R2  = dampf_regs+2");
        w.raw("R4  = dampf_regs+4");
        w.raw("R6  = dampf_regs+6");
        w.raw("R8  = dampf_regs+8");
        w.raw("R10 = dampf_regs+10");
        w.raw("R12 = dampf_regs+12");
        w.raw("R14 = dampf_regs+14");
    }

    private String fileDirective(String file, Path path) {
        long size = 0;
        long mtime = 0;
        try {
            size = Files.size(path);
            mtime = Files.getLastModifiedTime(path).toMillis() / 1000;
        } catch (IOException e) {
            // size and time only help a debugger notice a changed file
        }
        return "        .dbg    file, \"" + debugName.apply(file) + "\", " + size + ", " + mtime;
    }

    // ---------------------------------------------------------------- data

    private void globals() {
        for (Variable v : program.globals()) {
            Init init = program.initialValues().get(v);
            boolean zero = init == null || init.items().stream().allMatch(i -> i.address() == null && i.value() == 0);
            String segment = v.inRom() ? romSegment(v.label()) : zero ? "BSS" : "DATA";
            AsmWriter ram = asm;
            if (v.inRom()) {
                asm = new AsmWriter();
            }
            asm.blank();
            asm.raw("        .segment \"" + segment + "\"");
            String what = (v.kind() == Variable.Kind.STATIC_LOCAL ? "static " : "") + v.type() + " " + v.name();
            if (segment.equals("BSS")) {
                asm.raw(pad(v.label() + ":", 28) + ".res    " + v.type().size() + pad("", 4) + "; " + what);
                continue;
            }
            asm.raw(v.label() + ":" + pad("", Math.max(1, 27 - v.label().length())) + "; " + what);
            data(v.type().size(), init == null ? List.of() : init.items());
            if (v.inRom()) {
                banked.add(asm);
                asm = ram;
            }
        }
    }

    /** Emits an object's bytes: numbers as .byte, addresses as a low word plus bank byte. */
    private void data(int size, List<Init.Item> items) {
        TreeMap<Integer, Init.Item> addresses = new TreeMap<>();
        byte[] bytes = new byte[size];
        for (Init.Item i : items) {
            if (i.address() != null) {
                addresses.put(i.offset(), i);
            } else {
                for (int b = 0; b < i.size(); b++) {
                    bytes[i.offset() + b] = (byte) (i.value() >> (8 * b));
                }
            }
        }
        StringBuilder line = new StringBuilder();
        int count = 0;
        for (int o = 0; o < size; o++) {
            Init.Item a = addresses.get(o);
            if (a != null) {
                flushBytes(line);
                count = 0;
                String target = a.address().label() + offset(a.addressOffset());
                if (a.address().inRom()) {
                    asm.raw("        .word   .loword(" + target + ")");
                    asm.raw("        .byte   ^(" + target + "), 0");
                } else {
                    asm.raw("        .word   " + target);
                    asm.raw("        .byte   $7E, 0                  ; bank $7E: RAM");
                }
                o += 3;
                continue;
            }
            line.append(count == 0 ? "" : ",").append('$').append(hex(bytes[o] & 0xFF, 2));
            if (++count == 16) {
                flushBytes(line);
                count = 0;
            }
        }
        flushBytes(line);
    }

    private void flushBytes(StringBuilder line) {
        if (!line.isEmpty()) {
            asm.raw("        .byte   " + line);
            line.setLength(0);
        }
    }

    private void frames() {
        for (Function f : program.functions()) {
            if (f.isDefined() && replaced.contains(f.name())) {
                // hand-written assembly (DAMPF_ASM): its frame at the start of the frames' area,
                // in the convention of both code generators (a leaf: no other such frame is live)
                asm.blank();
                asm.note("frame of " + f.name() + "(), hand-written assembly");
                int at = 0;
                for (Variable v : f.params()) {
                    asm.raw(pad(v.label(), 28) + "= $" + hex(ASM_FRAME + at, 4) + "   ; parameter " + v.type() + " " + v.name());
                    at += dampf.asm.AsmFunction.paramBytes(v.type().size());
                }
                asm.raw(pad(f.label() + dampf.asm.AsmFunction.LOCALS, 28) + "= $" + hex(ASM_FRAME + at, 4) + "   ; its locals");
                continue;
            }
            int temps = tempCount.getOrDefault(f, 0);
            if (!f.isDefined() || f.params().isEmpty() && f.locals().isEmpty() && temps == 0) {
                continue;
            }
            asm.blank();
            asm.raw("        .segment \"BSS\"");
            asm.note("static frame of " + f.name() + "()");
            for (Variable v : f.params()) {
                asm.raw(pad(v.label() + ":", 28) + ".res    " + v.type().size() + pad("", 4) + "; parameter " + v.type() + " " + v.name());
            }
            for (Variable v : f.locals()) {
                asm.raw(pad(v.label() + ":", 28) + ".res    " + v.type().size() + pad("", 4) + "; local " + v.type() + " " + v.name());
            }
            for (int i = 0; i < temps; i++) {
                asm.raw(pad(tempLabel(f, i) + ":", 28) + ".res    4    ; temporary");
            }
        }
    }

    // ---------------------------------------------------------------- functions and statements

    private void function(Function f, AsmWriter code) throws CompileError {
        current = f;
        tempCount.put(f, 0);
        out = code;
        out.blank();
        out.raw("; ---- " + signature(f) + "    (" + f.definitionPos() + ")");
        out.raw("        .segment \"" + codeSegment(f.label()) + "\"");
        out.raw(".proc " + f.label());
        out.raw("        .dbg    func, \"" + f.name() + "\", \"00\", extern, \"" + f.label() + "\"");
        for (Variable p : f.params()) {
            out.raw("        .dbg    sym, \"" + p.name() + "\", \"00\", static, \"" + p.label() + "\"");
        }
        for (Variable v : f.locals()) {
            out.raw("        .dbg    sym, \"" + v.name() + "\", \"00\", static, \"" + v.label() + "\"");
        }
        Stmt.Block body = f.body();
        block(body);
        List<Stmt> sts = body.statements();
        if (sts.isEmpty() || !(sts.getLast() instanceof Stmt.Return)) {
            sourceLine(body.end());
            out.ins("rtl", null, "end of " + f.name() + "()");
        }
        out.raw(".endproc");
    }

    private static String signature(Function f) {
        StringBuilder s = new StringBuilder(f.returnType() + " " + f.name() + "(");
        if (f.params().isEmpty()) {
            s.append("void");
        }
        for (int i = 0; i < f.params().size(); i++) {
            s.append(i > 0 ? ", " : "").append(f.params().get(i).type()).append(' ').append(f.params().get(i).name());
        }
        return s.append(')').toString();
    }

    private void block(Stmt.Block b) throws CompileError {
        for (Stmt s : b.statements()) {
            statement(s);
        }
    }

    private void statement(Stmt s) throws CompileError {
        switch (s) {
            case Stmt.Block b -> block(b);
            case Stmt.ExprStmt e -> {
                sourceLine(s.pos());
                effect(e.expr());
            }
            case Stmt.Return r -> {
                sourceLine(s.pos());
                if (r.value() != null) {
                    beginExpr();
                    if (r.value().type().size() == 4) {
                        String v = gen32(r.value());
                        out.ins("lda", v + "+2", "result, high word ...");
                        out.ins("tax", null, null);
                        out.ins("lda", v, "... and low word");
                    } else {
                        genA(r.value());
                    }
                }
                out.ins("rtl", null, r.value() == null ? "return" : "return " + ExprPrinter.print(r.value()));
            }
            case Stmt.If i -> {
                sourceLine(s.pos());
                String otherwise = label();
                beginExpr();
                branch(i.cond(), otherwise, false);
                statement(i.then());
                if (i.otherwise() == null) {
                    place(otherwise);
                } else {
                    String end = label();
                    out.ins("jmp", end, "skip the else branch");
                    place(otherwise);
                    statement(i.otherwise());
                    place(end);
                }
            }
            case Stmt.While w -> {
                String top = label();
                String end = label();
                place(top);
                sourceLine(s.pos());
                beginExpr();
                branch(w.cond(), end, false);
                loopBody(w.body(), end, top);
                out.ins("jmp", top, "loop");
                place(end);
            }
            case Stmt.DoWhile d -> {
                String top = label();
                String cont = label();
                String end = label();
                place(top);
                loopBody(d.body(), end, cont);
                place(cont);
                sourceLine(d.cond().pos());
                beginExpr();
                branch(d.cond(), top, true);
                place(end);
            }
            case Stmt.For f -> {
                if (f.init() != null) {
                    statement(f.init());
                }
                String top = label();
                String cont = label();
                String end = label();
                place(top);
                if (f.cond() != null) {
                    sourceLine(f.cond().pos());
                    beginExpr();
                    branch(f.cond(), end, false);
                }
                loopBody(f.body(), end, cont);
                place(cont);
                if (!f.step().isEmpty()) {
                    sourceLine(f.step().getFirst().pos());
                    for (Expr e : f.step()) {
                        effect(e);
                    }
                }
                out.ins("jmp", top, "loop");
                place(end);
            }
            case Stmt.Break b -> {
                sourceLine(s.pos());
                out.ins("jmp", jumps.peek().breakLabel(), "break");
            }
            case Stmt.Continue c -> {
                sourceLine(s.pos());
                String target = jumps.stream().map(Jumps::continueLabel).filter(x -> x != null).findFirst().orElseThrow();
                out.ins("jmp", target, "continue");
            }
            case Stmt.Switch sw -> switchStatement(sw);
            case Stmt.Case c -> throw new IllegalStateException("case label outside a switch body");
            case Stmt.Range r -> {
                // a hint: no code (the naive code generator does not use it)
            }
        }
    }

    private void loopBody(Stmt body, String breakLabel, String continueLabel) throws CompileError {
        jumps.push(new Jumps(breakLabel, continueLabel));
        statement(body);
        jumps.pop();
    }

    /** Compares the value with each case label in turn, then runs the body from the matching label. */
    private void switchStatement(Stmt.Switch sw) throws CompileError {
        sourceLine(sw.pos());
        beginExpr();
        String end = label();
        Map<Stmt.Case, String> caseLabels = new LinkedHashMap<>();
        String defaultLabel = end;
        for (Stmt s : sw.body()) {
            if (s instanceof Stmt.Case c) {
                String l = label();
                caseLabels.put(c, l);
                if (c.value() == null) {
                    defaultLabel = l;
                }
            }
        }
        Range r = range(sw.value());
        if (r.fitsUnsigned16() || r.fitsSigned16()) {
            genA(sw.value());
            for (Map.Entry<Stmt.Case, String> e : caseLabels.entrySet()) {
                Long v = e.getKey().value();
                if (v == null || v < r.min() || v > r.max()) {
                    continue; // default, or a value the switch value can never have
                }
                out.ins("cmp", imm(v), "case " + v + "?");
                jumpIf(Flag.EQ, e.getValue());
            }
        } else {
            String v = gen32(sw.value());
            for (Map.Entry<Stmt.Case, String> e : caseLabels.entrySet()) {
                Long c = e.getKey().value();
                if (c == null) {
                    continue;
                }
                String next = label();
                out.ins("lda", v, "case " + c + "?");
                out.ins("cmp", imm(c & 0xFFFF), null);
                jumpIf(Flag.NE, next);
                out.ins("lda", v + "+2", null);
                out.ins("cmp", imm((c >> 16) & 0xFFFF), null);
                jumpIf(Flag.EQ, e.getValue());
                place(next);
            }
        }
        out.ins("jmp", defaultLabel, defaultLabel.equals(end) ? "no case matches" : "default");
        jumps.push(new Jumps(end, null));
        for (Stmt s : sw.body()) {
            if (s instanceof Stmt.Case c) {
                place(caseLabels.get(c));
            } else {
                statement(s);
            }
        }
        jumps.pop();
        place(end);
    }

    /** The source line as a comment, the debug line directive and the processor state. */
    private void sourceLine(SourcePos pos) {
        out.blank();
        String file = pos.file();
        sourceFiles.putIfAbsent(file, Path.of(file));
        out.sourceHeader("; " + Path.of(file).getFileName() + ":" + pos.line() + "   " + lineText(file, pos.line()));
        out.raw("        .dbg    line, \"" + debugName.apply(file) + "\", " + pos.line());
        out.note("M=16 X=16");
    }

    private String lineText(String file, int line) {
        List<String> lines = sourceLines.computeIfAbsent(file, f -> {
            try {
                return Files.readAllLines(Path.of(f));
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
        });
        return line >= 1 && line <= lines.size() ? lines.get(line - 1).strip() : "";
    }

    // ---------------------------------------------------------------- places (objects in memory)

    /** Where an object is. */
    private sealed interface Place {
        /** A fixed address: {@code symbol + offset}; long addressing if in ROM. */
        record Fixed(String symbol, long offset, boolean rom) implements Place {}

        /** {@code symbol + offset + index}, the 16-bit index in a temporary (loaded into X). */
        record Indexed(String symbol, long offset, boolean rom, String index) implements Place {}

        /** {@code pointer + offset}, the 24-bit pointer in a 4-byte slot (copied to the direct page). */
        record Pointed(String pointer, long offset) implements Place {}
    }

    private static Place plus(Place p, long d) {
        return switch (p) {
            case Place.Fixed f -> new Place.Fixed(f.symbol(), f.offset() + d, f.rom());
            case Place.Indexed i -> new Place.Indexed(i.symbol(), i.offset() + d, i.rom(), i.index());
            case Place.Pointed q -> new Place.Pointed(q.pointer(), q.offset() + d);
        };
    }

    /** Computes where an lvalue is; may emit code (an index, a pointer) into temporaries. */
    private Place placeOf(Expr lv) throws CompileError {
        return switch (lv) {
            case Expr.Var v -> new Place.Fixed(v.variable().label(), 0, v.variable().inRom());
            case Expr.Member m -> plus(placeOf(m.base()), m.field().offset());
            case Expr.Deref d -> new Place.Pointed(gen32(d.pointer()), 0);
            case Expr.Index i -> {
                int size = i.type().size();
                Long k = constant(i.index());
                if (i.base().type() instanceof Type.Array) {
                    Place base = placeOf(i.base());
                    if (k != null) {
                        yield plus(base, k * size);
                    }
                    yield indexed(base, i.index(), size);
                }
                String ptr = gen32(i.base());
                if (k != null) {
                    yield new Place.Pointed(ptr, k * size);
                }
                yield indexed(new Place.Pointed(ptr, 0), i.index(), size);
            }
            default -> throw new IllegalStateException("not an lvalue: " + lv);
        };
    }

    /** {@code base + index * size}, with the index computed at run time. */
    private Place indexed(Place base, Expr index, int size) throws CompileError {
        genA(index);
        scale(size);
        String t = temp();
        switch (base) {
            case Place.Fixed f -> {
                out.ins("sta", t, "byte offset of the element");
                return new Place.Indexed(f.symbol(), f.offset(), f.rom(), t);
            }
            case Place.Indexed x -> {
                out.ins("clc", null, null);
                out.ins("adc", x.index(), null);
                out.ins("sta", t, "byte offset of the element");
                return new Place.Indexed(x.symbol(), x.offset(), x.rom(), t);
            }
            case Place.Pointed q -> {
                out.ins("clc", null, null);
                out.ins("adc", q.pointer(), "pointer + byte offset (objects never cross a bank)");
                out.ins("sta", t, null);
                out.ins("lda", q.pointer() + "+2", null);
                out.ins("sta", t + "+2", null);
                return new Place.Pointed(t, q.offset());
            }
        }
    }

    /** A *= size (A holds an index, the result a byte offset). */
    private void scale(int size) {
        if (size == 1) {
            return;
        }
        if (Integer.bitCount(size) == 1) {
            for (int s = size; s > 1; s >>= 1) {
                out.ins("asl", "a", s == 2 ? "* " + size + " (element size)" : null);
            }
            return;
        }
        out.ins("ldx", imm(size), null);
        out.ins("stx", "z:R0", null);
        out.ins("jsl", mul16(), "* " + size + " (element size)");
    }

    /** Sets up X or Y for an access; returns the operand for the word at {@code extra}. */
    private String operand(Place p, long extra) {
        return switch (p) {
            case Place.Fixed f -> (f.rom() ? "f:" : "") + f.symbol() + offset(f.offset() + extra);
            case Place.Indexed i -> {
                out.ins("ldx", i.index(), null);
                yield (i.rom() ? "f:" : "") + i.symbol() + offset(i.offset() + extra) + ",x";
            }
            case Place.Pointed q -> {
                out.ins("ldy", imm(extra), null); // the offset is already in the pointer (preparePointer)
                yield VIA_P;
            }
        };
    }

    /**
     * Copies the pointer of a Pointed place to the direct page, with its offset added to the
     * low word (A is destroyed). Adding the offset there, not in Y, keeps the bank: [dp],y would
     * carry a negative offset ($FFFC) into the bank byte.
     */
    private void preparePointer(Place p) {
        if (p instanceof Place.Pointed q) {
            out.ins("lda", q.pointer(), null);
            if (q.offset() != 0) {
                out.ins("clc", null, null);
                out.ins("adc", imm(q.offset()), "+ " + q.offset() + " (in the address, the bank stays)");
            }
            out.ins("sta", P, null);
            out.ins("lda", q.pointer() + "+2", null);
            out.ins("sta", P + "+2", "pointer for [dp],y");
        }
    }

    private static String offset(long o) {
        return o == 0 ? "" : o > 0 ? "+" + o : "-" + (-o);
    }

    /** A = a scalar from memory (8-bit types extended, 32-bit types: the low word). */
    private void loadA(Place p, Type t, String what) {
        preparePointer(p);
        out.ins("lda", operand(p, 0), "A = " + what);
        if (t.size() == 1) {
            extend8((Type.Int) t, "A = " + what);
        }
    }

    /** Stores A (8 or 16 bits) into memory. */
    private void storeA(Place p, int size, String what) {
        if (p instanceof Place.Pointed) {
            String v = temp();
            out.ins("sta", v, null);
            preparePointer(p);
            out.ins("lda", v, null);
        }
        String op = operand(p, 0);
        if (size == 1) {
            out.ins("sep", "#$20", "M=8");
            out.raw("        .a8");
            out.ins("sta", op, what + " = A (low byte)");
            out.ins("rep", "#$20", "M=16");
            out.raw("        .a16");
        } else {
            out.ins("sta", op, what + " = A");
        }
    }

    /** A 4-byte value from memory, as a slot (a RAM label, or a temporary it was copied to). */
    private String load32(Place p) {
        if (p instanceof Place.Fixed f && !f.rom()) {
            return f.symbol() + offset(f.offset());
        }
        String t = temp();
        preparePointer(p);
        out.ins("lda", operand(p, 0), null);
        out.ins("sta", t, null);
        out.ins("lda", operand(p, 2), null);
        out.ins("sta", t + "+2", null);
        return t;
    }

    private void store32(Place p, String from, String comment) {
        if (p instanceof Place.Fixed f && !f.rom()) {
            copy32(from, f.symbol() + offset(f.offset()), comment);
            return;
        }
        preparePointer(p);
        out.ins("lda", from, null);
        out.ins("sta", operand(p, 0), null);
        out.ins("lda", from + "+2", null);
        out.ins("sta", operand(p, 2), comment);
    }

    /** A pointer to a place, as a 4-byte slot (address in the low word, bank in the third byte). */
    private String addressOf(Place p) {
        String t = temp();
        switch (p) {
            case Place.Fixed f -> {
                String target = f.symbol() + offset(f.offset());
                out.ins("lda", "#.loword(" + target + ")", "&" + target);
                out.ins("sta", t, null);
                out.ins("lda", f.rom() ? "#^(" + target + ")" : "#$007E", f.rom() ? "bank of the ROM data" : "bank $7E: RAM");
                out.ins("sta", t + "+2", null);
            }
            case Place.Indexed i -> {
                String target = i.symbol() + offset(i.offset());
                out.ins("lda", i.index(), null);
                out.ins("clc", null, null);
                out.ins("adc", "#.loword(" + target + ")", "&" + target + "[index]");
                out.ins("sta", t, null);
                out.ins("lda", i.rom() ? "#^(" + target + ")" : "#$007E", null);
                out.ins("sta", t + "+2", null);
            }
            case Place.Pointed q -> {
                out.ins("lda", q.pointer(), null);
                out.ins("clc", null, null);
                out.ins("adc", imm(q.offset()), null);
                out.ins("sta", t, null);
                out.ins("lda", q.pointer() + "+2", null);
                out.ins("sta", t + "+2", null);
            }
        }
        return t;
    }

    /** Copies {@code size} bytes between two places (struct assignment, local array initializers). */
    private void copyBlock(Place to, Place from, int size, String comment) {
        String src = addressOf(from);
        String dst = addressOf(to);
        copy32(src, P, null);
        copy32(dst, Q, null);
        String loop = label();
        out.ins("ldy", "#$0000", comment + ": " + size + " bytes");
        out.ins("sep", "#$20", "M=8");
        out.raw("        .a8");
        place(loop);
        out.ins("lda", VIA_P, null);
        out.ins("sta", VIA_Q, null);
        out.ins("iny", null, null);
        out.ins("cpy", imm(size), null);
        out.ins("bne", loop, null);
        out.ins("rep", "#$20", "M=16");
        out.raw("        .a16");
    }

    // ---------------------------------------------------------------- expressions

    /** Evaluates an expression for its side effects only. */
    private void effect(Expr e) throws CompileError {
        beginExpr();
        if (e.type().size() == 4 || e instanceof Expr.Assign a && !Type.isScalar(a.type())) {
            gen32orCopy(e);
        } else {
            genA(e);
        }
    }

    private void gen32orCopy(Expr e) throws CompileError {
        if (e instanceof Expr.Assign a) {
            assign(a);
        } else {
            gen32(e);
        }
    }

    /** A = the low 16 bits of {@code e} (for an 8-bit type: the value, extended to 16 bits). */
    private void genA(Expr e) throws CompileError {
        switch (e) {
            case Expr.Const k -> out.ins("lda", imm(k.value()), "A = " + k.value());
            case Expr.Var v -> loadA(placeOf(v), v.type(), v.variable().name());
            case Expr.Index i -> loadA(placeOf(i), i.type(), ExprPrinter.print(i));
            case Expr.Member m -> loadA(placeOf(m), m.type(), ExprPrinter.print(m));
            case Expr.Deref d -> loadA(placeOf(d), d.type(), ExprPrinter.print(d));
            case Expr.Convert c -> {
                genA(c.operand());
                if (c.type().bits() == 8) {
                    extend8(c.type(), "A = (" + c.type() + ")A");
                }
            }
            case Expr.Unary u -> {
                genA(u.operand());
                out.ins("eor", "#$FFFF", u.op() == Expr.UnOp.NOT ? "A = " + ExprPrinter.print(u) : "negate: invert ...");
                if (u.op() == Expr.UnOp.NEG) {
                    out.ins("inc", "a", "... and add 1: A = " + ExprPrinter.print(u));
                }
            }
            case Expr.Binary b -> binaryA(b);
            case Expr.LogicalNot n -> boolValue(n);
            case Expr.Logical l -> boolValue(l);
            case Expr.Conditional c -> {
                String otherwise = label();
                String end = label();
                branch(c.cond(), otherwise, false);
                genA(c.then());
                out.ins("jmp", end, null);
                place(otherwise);
                genA(c.otherwise());
                place(end);
            }
            case Expr.Assign a -> {
                Type t = a.target().type();
                if (t.size() == 4 || !Type.isScalar(t)) {
                    String v = assign(a);
                    if (v != null) {
                        out.ins("lda", v, null);
                    }
                } else {
                    Place p = placeOf(a.target());
                    genA(a.value());
                    storeA(p, t.size(), ExprPrinter.print(a.target()));
                }
            }
            case Expr.PostIncDec p -> {
                if (p.type().size() == 4) {
                    out.ins("lda", gen32(p), "A = old value (low word)");
                } else {
                    Place where = placeOf(p.target());
                    String old = temp();
                    loadA(where, p.type(), ExprPrinter.print(p.target()));
                    out.ins("sta", old, "keep the old value");
                    out.ins(p.increment() ? "clc" : "sec", null, null);
                    out.ins(p.increment() ? "adc" : "sbc", imm(p.step()), p.increment() ? "+ " + p.step() : "- " + p.step());
                    storeA(where, p.type().size(), ExprPrinter.print(p.target()));
                    out.ins("lda", old, "A = the old value");
                }
            }
            case Expr.Call c -> call(c);
            case Expr.AddressOf a -> out.ins("lda", addressOf(placeOf(a.lvalue())), "A = address (low word)");
            case Expr.Decay d -> out.ins("lda", addressOf(placeOf(d.array())), "A = address (low word)");
            case Expr.PointerAdd p -> out.ins("lda", gen32(p), null);
            case Expr.PointerDiff p -> out.ins("lda", gen32(p), null);
            case Expr.PointerCast c -> out.ins("lda", gen32(c), null);
            case Expr.PointerToInt c -> out.ins("lda", gen32(c), "A = the address (low word)");
        }
    }

    /** Assignment of a 32-bit value, a pointer or a struct; returns the slot holding the value (null for a struct). */
    private String assign(Expr.Assign a) throws CompileError {
        Type t = a.target().type();
        String what = ExprPrinter.print(a.target());
        if (!Type.isScalar(t)) {
            Place to = placeOf(a.target());
            Place from = placeOf(a.value());
            copyBlock(to, from, t.size(), what + " = " + ExprPrinter.print(a.value()));
            return null;
        }
        Place to = placeOf(a.target());
        String v = gen32(a.value());
        store32(to, v, what + " = " + ExprPrinter.print(a.value()));
        return v;
    }

    private void binaryA(Expr.Binary b) throws CompileError {
        String result = "A = " + ExprPrinter.print(b);
        switch (b.op()) {
            case ADD, SUB, AND, OR, XOR -> {
                String operand = operand16(b.right());
                if (operand == null) {
                    genA(b.right());
                    operand = temp();
                    out.ins("sta", operand, "save the right operand");
                }
                genA(b.left());
                switch (b.op()) {
                    case ADD -> {
                        out.ins("clc", null, null);
                        out.ins("adc", operand, result);
                    }
                    case SUB -> {
                        out.ins("sec", null, null);
                        out.ins("sbc", operand, result);
                    }
                    case AND -> out.ins("and", operand, result);
                    case OR -> out.ins("ora", operand, result);
                    default -> out.ins("eor", operand, result);
                }
            }
            case MUL -> {
                String right = operand16(b.right());
                if (right == null) {
                    genA(b.right());
                    right = temp();
                    out.ins("sta", right, "save the right operand");
                }
                genA(b.left());
                out.ins("ldx", right, null);
                out.ins("stx", "z:R0", null);
                out.ins("jsl", mul16(), result + " (low 16 bits)");
            }
            case SHL -> shift16(b, "asl", result);
            case SHR, DIV, MOD -> {
                if (unsigned16(b)) {
                    if (b.op() == Expr.BinOp.SHR) {
                        shift16(b, "lsr", result + " (both fit in 16 bits)");
                    } else {
                        String right = operand16(b.right());
                        if (right == null) {
                            genA(b.right());
                            right = temp();
                            out.ins("sta", right, "save the divisor");
                        }
                        genA(b.left());
                        out.ins("ldx", right, null);
                        out.ins("stx", "z:R0", null);
                        out.ins("jsl", "dampf_udiv16", "A = quotient, R2 = remainder (both fit in 16 bits)");
                        if (b.op() == Expr.BinOp.MOD) {
                            out.ins("lda", "z:R2", result);
                        }
                    }
                } else {
                    out.ins("lda", gen32(b), result + " (low word)");
                }
            }
            default -> boolValue(b); // comparisons
        }
    }

    /** {@code left << n} or {@code left >> n} on 16 bits. */
    private void shift16(Expr.Binary b, String op, String comment) throws CompileError {
        if (b.right() instanceof Expr.Const k) {
            if (k.value() >= 16) {
                out.ins("lda", "#$0000", comment + ": all bits shifted out");
                return;
            }
            genA(b.left());
            for (int i = 0; i < k.value(); i++) {
                out.ins(op, "a", i == k.value() - 1 ? comment : null);
            }
            return;
        }
        genA(b.right());
        String count = temp();
        out.ins("sta", count, "shift count");
        genA(b.left());
        String done = label();
        String loop = label();
        out.ins("ldx", count, null);
        jumpIf(Flag.EQ, done);
        place(loop);
        out.ins(op, "a", null);
        out.ins("dex", null, null);
        jumpIf(Flag.NE, loop);
        place(done);
        out.note(comment);
    }

    /** The full 32-bit value of {@code e}, in a 4-byte slot in RAM. Returns the slot's label. */
    private String gen32(Expr e) throws CompileError {
        if (e.type() instanceof Type.Int t && t.size() < 4) {
            genA(e);
            return extendTo32(t);
        }
        switch (e) {
            case Expr.Var v -> {
                return load32(placeOf(v));
            }
            case Expr.Index i -> {
                return load32(placeOf(i));
            }
            case Expr.Member m -> {
                return load32(placeOf(m));
            }
            case Expr.Deref d -> {
                return load32(placeOf(d));
            }
            case Expr.Const k -> {
                String t = temp();
                out.ins("lda", imm(k.value() & 0xFFFF), k.value() + ", low word ...");
                out.ins("sta", t, null);
                out.ins("lda", imm((k.value() >> 16) & 0xFFFF), "... high word");
                out.ins("sta", t + "+2", null);
                return t;
            }
            case Expr.Convert c -> {
                if (c.operand().type().size() == 4) {
                    return gen32(c.operand()); // int <-> unsigned int: the same bits
                }
                genA(c.operand());
                return extendTo32((Type.Int) c.operand().type());
            }
            case Expr.Unary u -> {
                String v = gen32(u.operand());
                String t = temp();
                if (u.op() == Expr.UnOp.NOT) {
                    out.ins("lda", v, null);
                    out.ins("eor", "#$FFFF", null);
                    out.ins("sta", t, null);
                    out.ins("lda", v + "+2", null);
                    out.ins("eor", "#$FFFF", null);
                    out.ins("sta", t + "+2", ExprPrinter.print(u));
                } else {
                    out.ins("lda", "#$0000", null);
                    out.ins("sec", null, null);
                    out.ins("sbc", v, null);
                    out.ins("sta", t, null);
                    out.ins("lda", "#$0000", null);
                    out.ins("sbc", v + "+2", null);
                    out.ins("sta", t + "+2", ExprPrinter.print(u));
                }
                return t;
            }
            case Expr.Binary b -> {
                return binary32(b);
            }
            case Expr.Conditional c -> {
                String t = temp();
                String otherwise = label();
                String end = label();
                branch(c.cond(), otherwise, false);
                copy32(gen32(c.then()), t, null);
                out.ins("jmp", end, null);
                place(otherwise);
                copy32(gen32(c.otherwise()), t, null);
                place(end);
                return t;
            }
            case Expr.Assign a -> {
                return assign(a);
            }
            case Expr.PostIncDec p -> {
                Place where = placeOf(p.target());
                String old = temp();
                copy32(load32(where), old, "keep the old value of " + ExprPrinter.print(p.target()));
                String t = temp();
                out.ins("lda", old, null);
                out.ins(p.increment() ? "clc" : "sec", null, null);
                out.ins(p.increment() ? "adc" : "sbc", imm(p.step()), null);
                out.ins("sta", t, null);
                out.ins("lda", old + "+2", null);
                out.ins(p.increment() ? "adc" : "sbc", "#$0000", null);
                out.ins("sta", t + "+2", null);
                store32(where, t, ExprPrinter.print(p));
                return old;
            }
            case Expr.Call c -> {
                call(c);
                String t = temp();
                out.ins("sta", t, "result, low word ...");
                out.ins("stx", t + "+2", "... and high word");
                return t;
            }
            case Expr.LogicalNot n -> {
                boolValue(n);
                return extendTo32(Type.U16); // 0 or 1
            }
            case Expr.Logical l -> {
                boolValue(l);
                return extendTo32(Type.U16); // 0 or 1
            }
            case Expr.AddressOf a -> {
                return addressOf(placeOf(a.lvalue()));
            }
            case Expr.Decay d -> {
                return addressOf(placeOf(d.array()));
            }
            case Expr.PointerCast c -> {
                return gen32(c.operand()); // the same bits
            }
            case Expr.PointerToInt c -> {
                return gen32(c.operand()); // the same bits: address, bank, a zero byte
            }
            case Expr.PointerAdd p -> {
                String ptr = gen32(p.pointer());
                genA(p.offset());
                scale(p.scale());
                String t = temp();
                if (p.subtract()) {
                    out.ins("sta", t, null);
                    out.ins("lda", ptr, null);
                    out.ins("sec", null, null);
                    out.ins("sbc", t, ExprPrinter.print(p) + " (the bank stays)");
                } else {
                    out.ins("clc", null, null);
                    out.ins("adc", ptr, ExprPrinter.print(p) + " (the bank stays)");
                }
                out.ins("sta", t, null);
                out.ins("lda", ptr + "+2", null);
                out.ins("sta", t + "+2", null);
                return t;
            }
            case Expr.PointerDiff p -> {
                String l = gen32(p.left());
                String r = gen32(p.right());
                out.ins("lda", l, null);
                out.ins("sec", null, null);
                out.ins("sbc", r, "byte distance (16 bits: both point into one object)");
                String d = extendTo32(Type.I16);
                if (p.scale() == 1) {
                    return d;
                }
                // the distance is an exact multiple of the element size
                copy32(d, "z:R0", null);
                out.ins("lda", imm(p.scale()), null);
                out.ins("sta", "z:R4", null);
                out.ins("stz", "z:R6", null);
                out.ins("jsl", "dampf_sdiv32", "/ " + p.scale() + " (element size)");
                String t = temp();
                copy32("z:R8", t, ExprPrinter.print(p));
                return t;
            }
            default -> throw new IllegalStateException("no 32-bit value for " + e);
        }
    }

    private String binary32(Expr.Binary b) throws CompileError {
        String comment = ExprPrinter.print(b);
        if (b.op().isComparison()) {
            boolValue(b);
            return extendTo32(Type.U16); // 0 or 1
        }
        if ((b.op() == Expr.BinOp.DIV || b.op() == Expr.BinOp.MOD || b.op() == Expr.BinOp.SHR) && unsigned16(b)) {
            binaryA(b);
            return extendTo32(Type.U16);
        }
        String l = gen32(b.left());
        switch (b.op()) {
            case ADD, SUB, AND, OR, XOR -> {
                String r = gen32(b.right());
                String t = temp();
                String[] ops = switch (b.op()) {
                    case ADD -> new String[] {"clc", "adc", "adc"};
                    case SUB -> new String[] {"sec", "sbc", "sbc"};
                    case AND -> new String[] {null, "and", "and"};
                    case OR -> new String[] {null, "ora", "ora"};
                    default -> new String[] {null, "eor", "eor"};
                };
                if (ops[0] != null) {
                    out.ins(ops[0], null, null);
                }
                out.ins("lda", l, null);
                out.ins(ops[1], r, null);
                out.ins("sta", t, null);
                out.ins("lda", l + "+2", null);
                out.ins(ops[2], r + "+2", null);
                out.ins("sta", t + "+2", comment);
                return t;
            }
            case MUL, DIV, MOD -> {
                String r = gen32(b.right());
                copy32(l, "z:R0", null);
                copy32(r, "z:R4", null);
                String t = temp();
                String result;
                if (b.op() == Expr.BinOp.MUL) {
                    out.ins("jsl", contexts.containsKey(current) ? "dampf_mul32_soft" : "dampf_mul32", "R8 = R0 * R4");
                    result = "z:R8";
                } else {
                    boolean signed = b.type().signed();
                    out.ins("jsl", signed ? "dampf_sdiv32" : "dampf_udiv32", "R8 = R0 / R4, R12 = R0 % R4" + (signed ? " (signed)" : ""));
                    result = b.op() == Expr.BinOp.DIV ? "z:R8" : "z:R12";
                }
                copy32(result, t, comment);
                return t;
            }
            case SHL, SHR -> {
                String t = temp();
                copy32(l, t, null);
                genA(b.right());
                String done = label();
                String loop = label();
                out.ins("tax", null, "shift count");
                jumpIf(Flag.EQ, done);
                place(loop);
                if (b.op() == Expr.BinOp.SHL) {
                    out.ins("asl", t, null);
                    out.ins("rol", t + "+2", null);
                } else if (b.type().signed()) {
                    out.ins("lda", t + "+2", "arithmetic shift: keep the sign bit");
                    out.ins("cmp", "#$8000", null);
                    out.ins("ror", "a", null);
                    out.ins("sta", t + "+2", null);
                    out.ins("ror", t, null);
                } else {
                    out.ins("lsr", t + "+2", null);
                    out.ins("ror", t, null);
                }
                out.ins("dex", null, null);
                jumpIf(Flag.NE, loop);
                place(done);
                out.note(comment);
                return t;
            }
            default -> throw new IllegalStateException(b.op().toString());
        }
    }

    /** Stores A, extended to 32 bits as {@code from} says, into a new slot. */
    private String extendTo32(Type.Int from) {
        String t = temp();
        out.ins("sta", t, "low word");
        if (from.signed() && from.bits() < 32) {
            String positive = label();
            out.ins("ldx", "#$0000", "high word: sign extension");
            out.ins("cmp", "#$8000", null);
            jumpIf(Flag.CC, positive);
            out.ins("dex", null, null);
            place(positive);
            out.ins("stx", t + "+2", null);
        } else if (from.bits() < 32) {
            out.ins("stz", t + "+2", "high word: 0");
        } else {
            // A holds only the low word of a 32-bit value: callers never get here
            throw new IllegalStateException("extendTo32 of a 32-bit value");
        }
        return t;
    }

    // ---------------------------------------------------------------- conditions

    /** Flag tests after a compare, with the opposite test for branching around a jump. */
    private enum Flag {
        EQ("beq", "bne"), NE("bne", "beq"), CS("bcs", "bcc"), CC("bcc", "bcs"), MI("bmi", "bpl"), PL("bpl", "bmi");

        final String branch;
        final String inverse;

        Flag(String branch, String inverse) {
            this.branch = branch;
            this.inverse = inverse;
        }

        Flag not() {
            return switch (this) {
                case EQ -> NE;
                case NE -> EQ;
                case CS -> CC;
                case CC -> CS;
                case MI -> PL;
                case PL -> MI;
            };
        }
    }

    /**
     * Jumps to {@code target} if the flag test holds. Branches reach only 128 bytes, so this
     * branches around a JMP (branch distances are optimization G).
     */
    private void jumpIf(Flag f, String target) {
        out.ins(f.inverse, ":+", null);
        out.ins("jmp", target, null);
        out.raw(":");
    }

    /** Jumps to {@code target} if the truth of {@code e} is {@code when}; falls through otherwise. */
    private void branch(Expr e, String target, boolean when) throws CompileError {
        switch (e) {
            case Expr.Const k -> {
                if ((k.value() != 0) == when) {
                    out.ins("jmp", target, "always");
                }
            }
            case Expr.LogicalNot n -> branch(n.operand(), target, !when);
            case Expr.Logical l -> {
                if (l.and() != when) {
                    // && jumping when false, || jumping when true: either operand decides alone
                    branch(l.left(), target, when);
                    branch(l.right(), target, when);
                } else {
                    String skip = label();
                    branch(l.left(), skip, !when);
                    branch(l.right(), target, when);
                    place(skip);
                }
            }
            case Expr.Binary b when b.op().isComparison() -> {
                Flag f = compare(b);
                jumpIf(when ? f : f.not(), target);
            }
            default -> {
                if (e.type().size() == 4) {
                    String v = gen32(e);
                    out.ins("lda", v, null);
                    out.ins("ora", v + "+2", "zero?");
                } else {
                    genA(e);
                    out.ins("cmp", "#$0000", "zero?");
                }
                jumpIf(when ? Flag.NE : Flag.EQ, target);
            }
        }
    }

    /** Sets the flags for a comparison; returns the flag test that means "true". */
    private Flag compare(Expr.Binary b) throws CompileError {
        Expr l = b.left();
        Expr r = b.right();
        Expr.BinOp op = b.op();
        if (op == Expr.BinOp.GT || op == Expr.BinOp.LE) { // a > b is b < a, a <= b is b >= a
            Expr x = l;
            l = r;
            r = x;
            op = op == Expr.BinOp.GT ? Expr.BinOp.LT : Expr.BinOp.GE;
        }
        String what = ExprPrinter.print(b);
        Range rl = range(l);
        Range rr = range(r);
        boolean u16 = rl.fitsUnsigned16() && rr.fitsUnsigned16();
        boolean s16 = !u16 && rl.fitsSigned16() && rr.fitsSigned16();
        if (u16 || s16) {
            String operand = operand16(r);
            if (operand == null) {
                genA(r);
                operand = temp();
                out.ins("sta", operand, null);
            }
            genA(l);
            if (op == Expr.BinOp.EQ || op == Expr.BinOp.NE || u16) {
                out.ins("cmp", operand, what + (u16 ? " (unsigned, 16 bits)" : ""));
                return switch (op) {
                    case EQ -> Flag.EQ;
                    case NE -> Flag.NE;
                    case LT -> Flag.CC;
                    default -> Flag.CS;
                };
            }
            out.ins("sec", null, null);
            out.ins("sbc", operand, what + " (signed, 16 bits)");
            out.ins("bvc", ":+", "N xor V is the sign of the true difference");
            out.ins("eor", "#$8000", null);
            out.raw(":");
            return op == Expr.BinOp.LT ? Flag.MI : Flag.PL;
        }
        String lv = gen32(l);
        String rv = gen32(r);
        if (op == Expr.BinOp.EQ || op == Expr.BinOp.NE) {
            String t = temp();
            out.ins("lda", lv, null);
            out.ins("eor", rv, null);
            out.ins("sta", t, null);
            out.ins("lda", lv + "+2", null);
            out.ins("eor", rv + "+2", null);
            out.ins("ora", t, what + " (32 bits)");
            return op == Expr.BinOp.EQ ? Flag.EQ : Flag.NE;
        }
        out.ins("lda", lv, null);
        out.ins("cmp", rv, null);
        out.ins("lda", lv + "+2", null);
        out.ins("sbc", rv + "+2", what + " (32 bits)");
        if (l.type() instanceof Type.Int it && it.signed()) {
            out.ins("bvc", ":+", "N xor V is the sign of the true difference");
            out.ins("eor", "#$8000", null);
            out.raw(":");
            return op == Expr.BinOp.LT ? Flag.MI : Flag.PL;
        }
        return op == Expr.BinOp.LT ? Flag.CC : Flag.CS; // unsigned, also pointers
    }

    /** A = 1 if {@code e} is true, else 0. */
    private void boolValue(Expr e) throws CompileError {
        String no = label();
        String end = label();
        branch(e, no, false);
        out.ins("lda", "#$0001", "A = " + ExprPrinter.print(e) + " (true)");
        out.ins("bra", end, null);
        place(no);
        out.ins("lda", "#$0000", "(false)");
        place(end);
    }

    // ---------------------------------------------------------------- value ranges

    /** The range a value can have, as far as its types show. */
    private record Range(long min, long max) {
        boolean fitsUnsigned16() {
            return min >= 0 && max <= 0xFFFF;
        }

        boolean fitsSigned16() {
            return min >= -0x8000 && max <= 0x7FFF;
        }
    }

    private static Range range(Expr e) {
        return switch (e) {
            case Expr.Const k -> new Range(k.value(), k.value());
            case Expr.Convert c -> {
                Range r = range(c.operand());
                yield r.min() >= c.type().min() && r.max() <= c.type().max() ? r : typeRange(c.type());
            }
            case Expr.Binary b when b.op().isComparison() -> new Range(0, 1);
            case Expr.LogicalNot n -> new Range(0, 1);
            case Expr.Logical l -> new Range(0, 1);
            case Expr.Binary b when b.op() == Expr.BinOp.AND -> {
                // x & y with y >= 0 lies in 0..y
                Range l = range(b.left());
                Range r = range(b.right());
                if (r.min() >= 0 || l.min() >= 0) {
                    yield new Range(0, Math.min(r.min() >= 0 ? r.max() : Long.MAX_VALUE, l.min() >= 0 ? l.max() : Long.MAX_VALUE));
                }
                yield typeRange(b.type());
            }
            case Expr.Binary b when b.op() == Expr.BinOp.SHR && range(b.left()).min() >= 0 -> new Range(0, range(b.left()).max());
            default -> e.type() instanceof Type.Int t ? typeRange(t) : new Range(0, 0xFFFFFFFFL); // pointers: all 32 bits
        };
    }

    private static Range typeRange(Type.Int t) {
        return new Range(t.min(), t.max());
    }

    /** Both operands of / % >> lie in 0..65535: the unsigned 16-bit operation gives C's result. */
    private static boolean unsigned16(Expr.Binary b) {
        return range(b.left()).fitsUnsigned16() && range(b.right()).fitsUnsigned16();
    }

    /** The value of a constant index, or null. */
    private static Long constant(Expr e) {
        return e instanceof Expr.Const k ? k.value() : null;
    }

    // ---------------------------------------------------------------- calls and small helpers

    private void call(Expr.Call c) throws CompileError {
        Function f = c.function();
        if (!f.isDefined()) {
            throw new CompileError(c.pos(), "function '" + f.name() + "' is not defined");
        }
        List<Variable> params = f.params();
        boolean nested = c.args().stream().anyMatch(NaiveCodeGen::containsCall);
        if (nested) {
            // a call inside an argument could overwrite parameters already stored: collect all first
            List<String> saved = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                Expr a = c.args().get(i);
                String t = temp();
                if (a.type().size() == 4) {
                    copy32(gen32(a), t, "argument " + (i + 1) + " of " + f.name() + "()");
                } else {
                    genA(a);
                    out.ins("sta", t, "argument " + (i + 1) + " of " + f.name() + "()");
                }
                saved.add(t);
            }
            for (int i = 0; i < params.size(); i++) {
                Variable p = params.get(i);
                if (p.type().size() == 4) {
                    copy32(saved.get(i), p.label(), p.name() + " = argument " + (i + 1));
                } else {
                    out.ins("lda", saved.get(i), null);
                    storeA(new Place.Fixed(p.label(), 0, false), p.type().size(), p.name());
                }
            }
        } else {
            for (int i = 0; i < params.size(); i++) {
                Variable p = params.get(i);
                Expr a = c.args().get(i);
                if (p.type().size() == 4) {
                    copy32(gen32(a), p.label(), p.name() + " = " + ExprPrinter.print(a));
                } else {
                    genA(a);
                    storeA(new Place.Fixed(p.label(), 0, false), p.type().size(), p.name());
                }
            }
        }
        out.ins("jsl", f.label(), ExprPrinter.print(c) + (f.returnType() instanceof Type.Void ? "" : ", result in A"
                + (f.returnType().size() == 4 ? " (low) and X (high)" : "")));
        if (replaced.contains(f.name()) && f.returnType() instanceof Type.Int t && t.size() == 1) {
            extend8(t, "the low byte of A is an assembly function's result");
        }
    }

    private static boolean containsCall(Expr e) {
        return e instanceof Expr.Call || Children.of(e).stream().anyMatch(NaiveCodeGen::containsCall);
    }

    /**
     * An operand a 16-bit instruction (also ldx) can use directly, or null: a constant, or a
     * 16- or 32-bit variable in RAM (its low word), also through conversions that keep the low
     * 16 bits.
     */
    private static String operand16(Expr e) {
        return switch (e) {
            case Expr.Const k -> imm(k.value());
            case Expr.Var v -> v.type().size() >= 2 && Type.isScalar(v.type()) && !v.variable().inRom() ? v.variable().label() : null;
            case Expr.Convert c when c.operand().type().size() >= 2 && c.type().size() >= 2 -> operand16(c.operand());
            default -> null;
        };
    }

    private void copy32(String from, String to, String comment) {
        if (from.equals(to)) {
            return;
        }
        out.ins("lda", from, null);
        out.ins("sta", to, null);
        out.ins("lda", from + "+2", null);
        out.ins("sta", to + "+2", comment);
    }

    /** Keeps the low byte of A and extends it to 16 bits as {@code t} says. */
    private void extend8(Type.Int t, String comment) {
        if (t.signed()) {
            out.ins("and", "#$00FF", "keep the low byte ...");
            out.ins("eor", "#$0080", "... and sign-extend it:");
            out.ins("sec", null, null);
            out.ins("sbc", "#$0080", comment);
        } else {
            out.ins("and", "#$00FF", comment);
        }
    }

    // ---------------------------------------------------------------- labels and temporaries

    private void beginExpr() {
        tempsUsed = 0;
    }

    private String temp() {
        String t = tempLabel(current, tempsUsed++);
        tempCount.merge(current, tempsUsed, Math::max);
        return t;
    }

    private static String tempLabel(Function f, int i) {
        return f.label() + "__t" + i;
    }

    private String label() {
        return "@L" + (labels++);
    }

    private void place(String label) {
        out.label(label);
    }

    /** The segment of a function's code: packed into a ROM bank by the ROM builder. */
    public static String codeSegment(String label) {
        return "C" + label;
    }

    /** The segment of a constant object in ROM. */
    public static String romSegment(String label) {
        return "R" + label;
    }

    private static String imm(long v) {
        return "#$" + hex(v & 0xFFFF, 4);
    }

    private static String hex(long v, int digits) {
        return String.format("%0" + digits + "X", v);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
