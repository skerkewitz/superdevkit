package dampf.backend;

import dampf.frontend.CompileError;
import dampf.frontend.SourcePos;
import dampf.mir.Block;
import dampf.mir.Instr;
import dampf.mir.MFunction;
import dampf.mir.MModule;
import dampf.mir.MType;
import dampf.mir.Printer;
import dampf.mir.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates 65816 code from MIR, one instruction at a time. Like {@link NaiveCodeGen} it aims to
 * be obviously correct, not fast: it is the back end the MIR passes are measured with, and it
 * gets better as they do.
 *
 * <p><b>Machine state</b> as in the naive generator: A/X/Y 16 bit, DBR $7E, JSL/RTL, a result in
 * A (and X for the high word of an i32 or a pointer).
 *
 * <p><b>Values.</b> Every MIR value (parameter, instruction result) has a fixed place in RAM: 2
 * bytes for i1, i8 and i16, 4 bytes for i32 and pointers. An i1 or i8 is kept zero-extended to
 * 16 bits, so a 16-bit load gives its value. Every instruction loads its operands from there
 * and stores its result there; nothing stays in a register from one instruction to the next.
 *
 * <p><b>Frames</b> form a compiled stack. dampf has no recursion, so the call graph is acyclic,
 * and a function's frame can start where the frames of all its callers end: frames that may be
 * live at the same time never overlap, and the others share memory. All frames lie in one block,
 * {@code dampf_frame}. A caller stores the arguments straight into the callee's parameters.
 *
 * <p><b>φs</b> get a second place, "incoming": every predecessor stores its operand there right
 * before its jump, and the block copies it into the φ's own place on entry. That is correct for
 * any set of φs (also when they swap values), with no analysis.
 */
public final class MirCodeGen {
    private static final String P = "z:dampf_ptr";
    private static final String Q = "z:dampf_ptr+4";
    private static final String VIA_P = "[dampf_ptr],y";
    private static final String VIA_Q = "[dampf_ptr+4],y";
    private static final String FRAME = "dampf_frame";
    /** RAM between $2000 and $FFFF (bank $7E) minus room for the globals: the frames must fit. */
    private static final int MAX_FRAME = 0xC000;

    private final MModule module;
    private final String entry;
    private final java.util.function.Function<String, String> debugName;
    private final Set<String> rom = new HashSet<>();

    /** Position of every value, frame slot and φ "incoming" place in dampf_frame. */
    private final Map<Object, Integer> place = new IdentityHashMap<>();
    private final Map<Instr.Phi, Integer> incoming = new IdentityHashMap<>();
    private final Map<MFunction, Integer> base = new HashMap<>();
    private final Map<MFunction, Integer> frameSize = new HashMap<>();
    private final Map<String, List<MFunction>> callers = new HashMap<>();
    private int frameTotal;

    private final List<String> banked = new ArrayList<>();
    private int entryBlock = -1;
    private final Map<String, List<String>> sourceLines = new HashMap<>();
    private final Map<String, Path> sourceFiles = new LinkedHashMap<>();

    private AsmWriter out;
    private int labels;
    private SourcePos lastPos;

    /**
     * @param entry     the label of the function the runtime calls
     * @param debugName maps a source file path to the name written into the debug information
     */
    public MirCodeGen(MModule module, String entry, java.util.function.Function<String, String> debugName) {
        this.module = module;
        this.entry = entry;
        this.debugName = debugName;
        for (MModule.Global g : module.globals()) {
            if (g.readOnly()) {
                rom.add(g.symbol());
            }
        }
    }

    /** At most this many segments per assembly module: ca65 allows about 256 per object file. */
    private static final int SEGMENTS_PER_MODULE = 200;

    public List<String> generate() throws CompileError {
        MFunction entryFunction = module.function(entry);
        if (entryFunction == null || !entryFunction.params().isEmpty()) {
            throw new IllegalStateException("the entry function " + entry + " must exist and have no parameters");
        }
        for (MModule.Global g : module.globals()) {
            if (g.size() > 0x8000) {
                throw new CompileError(entryFunction.pos(), "'" + g.symbol() + "' is " + g.size()
                        + " bytes; objects can be at most 32 KB (docs/memory-map.md)");
            }
        }
        layout();
        for (MFunction f : module.functions()) {
            AsmWriter code = new AsmWriter();
            function(f, code);
            if (code.unknownInstructions() > 0) {
                throw new IllegalStateException("the cycle table does not know an instruction in " + f.name());
            }
            if (f == entryFunction) {
                entryBlock = banked.size();
            }
            banked.add(code.toString());
        }
        AsmWriter ram = new AsmWriter();
        globals(ram);
        ram.blank();
        ram.raw("        .segment \"BSS\"");
        ram.raw(FRAME + ":" + " ".repeat(Math.max(1, 28 - FRAME.length() - 1)) + ".res    " + Math.max(1, frameTotal)
                + "    ; all frames: every function's values (compiled stack)");

        List<String> modules = new ArrayList<>();
        int chunks = Math.max(1, (banked.size() + SEGMENTS_PER_MODULE - 1) / SEGMENTS_PER_MODULE);
        for (int m = 0; m < chunks; m++) {
            AsmWriter w = new AsmWriter();
            prelude(w, m, chunks);
            if (m == 0) {
                w.raw(ram.toString());
            }
            for (int i = m * SEGMENTS_PER_MODULE; i < Math.min(banked.size(), (m + 1) * SEGMENTS_PER_MODULE); i++) {
                if (i == entryBlock) {
                    w.blank();
                    w.raw("        .export dampf_entry");
                    w.raw("dampf_entry = " + entry + "             ; the runtime calls this after start-up");
                }
                w.raw(banked.get(i));
            }
            modules.add(w.toString());
        }
        return modules;
    }

    private void prelude(AsmWriter w, int module, int modules) {
        w.raw("; Generated by dampf from MIR" + (modules > 1 ? " (module " + (module + 1) + " of " + modules + ")" : "") + ".");
        w.raw("; A/X/Y are 16 bit throughout, DBR = $7E; every value has a fixed place in dampf_frame.");
        w.blank();
        w.raw("        .p816");
        w.raw("        .smart  off");
        w.raw("        .a16");
        w.raw("        .i16");
        w.raw("        .importzp dampf_regs, dampf_ptr");
        w.raw("        .import dampf_mul16, dampf_mul32, dampf_udiv16, dampf_udiv32, dampf_sdiv32");
        if (modules > 1) {
            List<String> all = new ArrayList<>();
            all.add(FRAME);
            this.module.functions().forEach(f -> all.add(f.name()));
            this.module.globals().forEach(g -> all.add(g.symbol()));
            for (int i = 0; i < all.size(); i += 8) {
                w.raw("        .global " + String.join(", ", all.subList(i, Math.min(all.size(), i + 8))));
            }
        }
        for (Map.Entry<String, Path> e : sourceFiles.entrySet()) {
            w.raw(fileDirective(e.getKey(), e.getValue()));
        }
        w.blank();
        for (int r = 0; r <= 14; r += 2) {
            w.raw("R" + r + (r < 10 ? "  " : " ") + "= dampf_regs+" + r);
        }
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

    // ---------------------------------------------------------------- frames

    private static int bytes(MType t) {
        return t.size <= 2 ? 2 : 4;
    }

    private static int words(MType t) {
        return t.size <= 2 ? 1 : 2;
    }

    private void layout() {
        for (MFunction f : module.functions()) {
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Call c) {
                        List<MFunction> l = callers.computeIfAbsent(c.callee, k -> new ArrayList<>());
                        if (!l.contains(f)) {
                            l.add(f);
                        }
                    }
                }
            }
        }
        for (MFunction f : module.functions()) {
            int size = 0;
            Map<Object, Integer> local = new IdentityHashMap<>();
            for (Value.Param p : f.params()) {
                local.put(p, size);
                size += bytes(p.type());
            }
            for (Value.Slot s : f.slots()) {
                local.put(s, size);
                size += Math.max(1, s.size());
            }
            Map<Instr.Phi, Integer> in = new IdentityHashMap<>();
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i.type() != MType.VOID) {
                        local.put(i, size);
                        size += bytes(i.type());
                    }
                    if (i instanceof Instr.Phi p) {
                        in.put(p, size);
                        size += bytes(p.type());
                    }
                }
            }
            frameSize.put(f, size);
            int b = base(f, new LinkedHashSet<>());
            local.forEach((k, v) -> place.put(k, b + v));
            in.forEach((k, v) -> incoming.put(k, b + v));
            frameTotal = Math.max(frameTotal, b + size);
        }
        if (frameTotal > MAX_FRAME) {
            throw new IllegalStateException("the frames need " + frameTotal + " bytes, more than " + MAX_FRAME);
        }
    }

    /** Where f's frame starts: after the frames of all its callers. */
    private int base(MFunction f, Set<MFunction> path) {
        Integer known = base.get(f);
        if (known != null) {
            return known;
        }
        if (!path.add(f)) {
            throw new IllegalStateException("recursion through " + f.name());
        }
        int b = 0;
        for (MFunction c : callers.getOrDefault(f.name(), List.of())) {
            frameSize.computeIfAbsent(c, this::sizeOf);
            b = Math.max(b, base(c, path) + frameSize.get(c));
        }
        path.remove(f);
        base.put(f, b);
        return b;
    }

    private int sizeOf(MFunction f) {
        int size = 0;
        for (Value.Param p : f.params()) {
            size += bytes(p.type());
        }
        for (Value.Slot s : f.slots()) {
            size += Math.max(1, s.size());
        }
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (i.type() != MType.VOID) {
                    size += bytes(i.type());
                }
                if (i instanceof Instr.Phi p) {
                    size += bytes(p.type());
                }
            }
        }
        return size;
    }

    private static String at(int offset) {
        return FRAME + "+" + offset;
    }

    // ---------------------------------------------------------------- data

    private void globals(AsmWriter ram) {
        for (MModule.Global g : module.globals()) {
            boolean zero = g.init().stream().allMatch(d -> d.symbol() == null && d.value() == 0);
            AsmWriter w = g.readOnly() ? new AsmWriter() : ram;
            String segment = g.readOnly() ? NaiveCodeGen.romSegment(g.symbol()) : zero ? "BSS" : "DATA";
            w.blank();
            w.raw("        .segment \"" + segment + "\"");
            if (segment.equals("BSS")) {
                w.raw(pad(g.symbol() + ":", 28) + ".res    " + Math.max(1, g.size()));
                continue;
            }
            w.raw(g.symbol() + ":");
            data(w, g);
            if (g.readOnly()) {
                banked.add(w.toString());
            }
        }
    }

    private void data(AsmWriter w, MModule.Global g) {
        TreeMap<Integer, MModule.Datum> addresses = new TreeMap<>();
        byte[] bytes = new byte[g.size()];
        for (MModule.Datum d : g.init()) {
            if (d.symbol() != null) {
                addresses.put(d.offset(), d);
            } else {
                for (int b = 0; b < d.size(); b++) {
                    bytes[d.offset() + b] = (byte) (d.value() >> (8 * b));
                }
            }
        }
        StringBuilder line = new StringBuilder();
        int count = 0;
        for (int o = 0; o < g.size(); o++) {
            MModule.Datum a = addresses.get(o);
            if (a != null) {
                flush(w, line);
                count = 0;
                String target = a.symbol() + (a.value() == 0 ? "" : (a.value() > 0 ? "+" : "") + a.value());
                if (rom.contains(a.symbol())) {
                    w.raw("        .word   .loword(" + target + ")");
                    w.raw("        .byte   ^(" + target + "), 0");
                } else {
                    w.raw("        .word   " + target);
                    w.raw("        .byte   $7E, 0                  ; bank $7E: RAM");
                }
                o += 3;
                continue;
            }
            line.append(count == 0 ? "" : ",").append('$').append(hex(bytes[o] & 0xFF, 2));
            if (++count == 16) {
                flush(w, line);
                count = 0;
            }
        }
        flush(w, line);
    }

    private static void flush(AsmWriter w, StringBuilder line) {
        if (!line.isEmpty()) {
            w.raw("        .byte   " + line);
            line.setLength(0);
        }
    }

    // ---------------------------------------------------------------- functions

    private void function(MFunction f, AsmWriter code) {
        out = code;
        lastPos = null;
        f.number();
        out.blank();
        out.raw("; ---- " + f.name() + "    (" + f.pos() + ")");
        out.raw("        .segment \"" + NaiveCodeGen.codeSegment(f.name()) + "\"");
        out.raw(".proc " + f.name());
        for (Value.Param p : f.params()) {
            out.note("parameter " + p.type() + " " + p + " at " + at(place.get(p)));
        }
        List<Block> blocks = f.blocks();
        for (int k = 0; k < blocks.size(); k++) {
            Block b = blocks.get(k);
            Block next = k + 1 < blocks.size() ? blocks.get(k + 1) : null;
            out.label(b.name());
            for (Instr i : b.instrs()) {
                if (i instanceof Instr.Phi p) {
                    out.note(Printer.instr(p));
                    for (int w = 0; w < words(p.type()); w++) {
                        ins("lda", at(incoming.get(p) + 2 * w));
                        ins("sta", at(place.get(p) + 2 * w));
                    }
                    continue;
                }
                if (i.pos() != null && (lastPos == null || !lastPos.file().equals(i.pos().file()) || lastPos.line() != i.pos().line())) {
                    sourceLine(i.pos());
                    lastPos = i.pos();
                }
                out.note(Printer.instr(i));
                if (i.isTerminator()) {
                    phiCopies(b, i);
                }
                instr(i, next);
            }
        }
        out.raw(".endproc");
    }

    private void sourceLine(SourcePos pos) {
        out.blank();
        String file = pos.file();
        sourceFiles.putIfAbsent(file, Path.of(file));
        out.sourceHeader("; " + Path.of(file).getFileName() + ":" + pos.line() + "   " + lineText(file, pos.line()));
        out.raw("        .dbg    line, \"" + debugName.apply(file) + "\", " + pos.line());
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

    /** Before a block's jump: every φ of every successor gets this block's operand. */
    private void phiCopies(Block b, Instr terminator) {
        for (Block s : new LinkedHashSet<>(terminator.successors())) {
            for (Instr i : s.instrs()) {
                if (!(i instanceof Instr.Phi p)) {
                    break;
                }
                Value v = p.op(p.from.indexOf(b));
                for (int w = 0; w < words(p.type()); w++) {
                    ins("lda", operand(v, w));
                    ins("sta", at(incoming.get(p) + 2 * w));
                }
            }
        }
    }

    // ---------------------------------------------------------------- operands

    /** Word w (0 low, 1 high) of a value, as an operand of lda, adc, cmp, ... */
    private String operand(Value v, int w) {
        return switch (v) {
            case Value.Const k -> imm(k.bits() >> (16 * w));
            case Value.Undef u -> imm(0);
            case Value.GlobalAddr g -> rom.contains(g.symbol())
                    ? (w == 0 ? "#.loword(" + g.symbol() + ")" : "#^" + g.symbol())
                    : (w == 0 ? "#.loword(" + g.symbol() + ")" : imm(0x7E));
            case Value.SlotAddr s -> w == 0 ? "#.loword(" + at(place.get(s.slot())) + ")" : imm(0x7E);
            case Value.Param p -> w >= words(p.type()) ? imm(0) : at(place.get(p) + 2 * w);
            case Instr i -> w >= words(i.type()) ? imm(0) : at(place.get(i) + 2 * w);
        };
    }

    private String dest(Instr i, int w) {
        return at(place.get(i) + 2 * w);
    }

    /** The bits above an i1 or i8 are zero in its place. */
    private void mask(MType t) {
        if (t == MType.I8) {
            ins("and", imm(0xFF));
        } else if (t == MType.I1) {
            ins("and", imm(1));
        }
    }

    /** A memory operand for byte {@code k} of the object at a fixed address, or null if the address is not fixed. */
    private String fixed(Value address, int k) {
        return switch (address) {
            case Value.GlobalAddr g -> (rom.contains(g.symbol()) ? "f:" : "") + g.symbol() + (k == 0 ? "" : "+" + k);
            case Value.SlotAddr s -> at(place.get(s.slot()) + k);
            default -> null;
        };
    }

    /** Copies a pointer into the direct page for [dp],y. */
    private void pointer(Value address, String dp) {
        ins("lda", operand(address, 0));
        ins("sta", dp);
        ins("lda", operand(address, 1));
        ins("sta", dp + "+2");
    }

    // ---------------------------------------------------------------- instructions

    private void instr(Instr i, Block next) {
        switch (i) {
            case Instr.Bin b -> bin(b);
            case Instr.Cmp c -> cmp(c);
            case Instr.Cast c -> cast(c);
            case Instr.PtrAdd p -> {
                ins("clc", null);
                ins("lda", operand(p.op(0), 0));
                ins("adc", operand(p.op(1), 0));
                ins("sta", dest(p, 0));
                ins("lda", operand(p.op(0), 1));
                if (p.op(1).type() == MType.I32) {
                    ins("adc", operand(p.op(1), 1));
                }   // an i16 offset stays within the bank (Instr.PtrAdd)
                ins("sta", dest(p, 1));
            }
            case Instr.Load l -> load(l);
            case Instr.Store s -> store(s);
            case Instr.Copy c -> copy(c);
            case Instr.Call c -> call(c);
            case Instr.Phi p -> throw new IllegalStateException("φ after the start of a block");
            case Instr.Jump j -> {
                if (j.target != next) {
                    ins("jmp", j.target.name());
                }
            }
            case Instr.Branch br -> {
                String skip = label();
                ins("lda", operand(br.op(0), 0));
                ins("beq", skip);
                ins("jmp", br.ifTrue.name());
                out.label(skip);
                if (br.ifFalse != next) {
                    ins("jmp", br.ifFalse.name());
                }
            }
            case Instr.Switch s -> {
                MType t = s.op(0).type();
                for (int k = 0; k < s.cases.size(); k++) {
                    long c = t.wrap(s.cases.get(k));
                    String nextCase = label();
                    for (int w = 0; w < words(t); w++) {
                        ins("lda", operand(s.op(0), w));
                        ins("cmp", imm(c >> (16 * w)));
                        ins("bne", nextCase);
                    }
                    ins("jmp", s.targets.get(k).name());
                    out.label(nextCase);
                }
                if (s.otherwise != next) {
                    ins("jmp", s.otherwise.name());
                }
            }
            case Instr.Ret r -> {
                if (!r.operands().isEmpty()) {
                    Value v = r.op(0);
                    if (words(v.type()) == 2) {
                        ins("lda", operand(v, 1));
                        ins("tax", null);
                    }
                    ins("lda", operand(v, 0));
                }
                ins("rtl", null);
            }
        }
    }

    private void bin(Instr.Bin b) {
        MType t = b.type();
        int n = words(t);
        Value x = b.op(0);
        Value y = b.op(1);
        switch (b.op) {
            case ADD, SUB -> {
                ins(b.op == Instr.BinOp.ADD ? "clc" : "sec", null);
                for (int w = 0; w < n; w++) {
                    ins("lda", operand(x, w));
                    ins(b.op == Instr.BinOp.ADD ? "adc" : "sbc", operand(y, w));
                    mask(t);
                    ins("sta", dest(b, w));
                }
            }
            case AND, OR, XOR -> {
                String m = b.op == Instr.BinOp.AND ? "and" : b.op == Instr.BinOp.OR ? "ora" : "eor";
                for (int w = 0; w < n; w++) {
                    ins("lda", operand(x, w));
                    ins(m, operand(y, w));
                    ins("sta", dest(b, w));
                }
            }
            case MUL -> {
                if (n == 1) {
                    ins("lda", operand(y, 0));
                    ins("sta", "z:R0");
                    ins("lda", operand(x, 0));
                    ins("jsl", "dampf_mul16");
                    mask(t);
                    ins("sta", dest(b, 0));
                } else {
                    to32(x, "z:R0", "z:R2");
                    to32(y, "z:R4", "z:R6");
                    ins("jsl", "dampf_mul32");
                    from32(b, "z:R8", "z:R10");
                }
            }
            case UDIV, UREM -> {
                boolean rem = b.op == Instr.BinOp.UREM;
                if (n == 1) {
                    ins("lda", operand(y, 0));
                    ins("sta", "z:R0");
                    ins("lda", operand(x, 0));
                    ins("jsl", "dampf_udiv16");
                    if (rem) {
                        ins("lda", "z:R2");
                    }
                    ins("sta", dest(b, 0));
                } else {
                    to32(x, "z:R0", "z:R2");
                    to32(y, "z:R4", "z:R6");
                    ins("jsl", "dampf_udiv32");
                    from32(b, rem ? "z:R12" : "z:R8", rem ? "z:R14" : "z:R10");
                }
            }
            case SDIV, SREM -> {
                boolean rem = b.op == Instr.BinOp.SREM;
                if (n == 1) {
                    signExtend(x, t, "z:R0", "z:R2");
                    signExtend(y, t, "z:R4", "z:R6");
                } else {
                    to32(x, "z:R0", "z:R2");
                    to32(y, "z:R4", "z:R6");
                }
                ins("jsl", "dampf_sdiv32");
                if (n == 1) {
                    ins("lda", rem ? "z:R12" : "z:R8");
                    mask(t);
                    ins("sta", dest(b, 0));
                } else {
                    from32(b, rem ? "z:R12" : "z:R8", rem ? "z:R14" : "z:R10");
                }
            }
            case SHL, LSHR, ASHR -> shift(b);
        }
    }

    private void to32(Value v, String lo, String hi) {
        ins("lda", operand(v, 0));
        ins("sta", lo);
        ins("lda", operand(v, 1));
        ins("sta", hi);
    }

    private void from32(Instr d, String lo, String hi) {
        ins("lda", lo);
        ins("sta", dest(d, 0));
        ins("lda", hi);
        ins("sta", dest(d, 1));
    }

    /** A = the value of an i1/i8/i16 as a signed 16-bit number. */
    private void loadSigned16(Value v, MType t) {
        ins("lda", operand(v, 0));
        int m = t == MType.I8 ? 0x80 : t == MType.I1 ? 1 : 0;
        if (m != 0) {
            ins("eor", imm(m));     // (x ^ m) - m: sign-extends the top bit m
            ins("sec", null);
            ins("sbc", imm(m));
        }
    }

    /** Stores A (a signed 16-bit number) to lo, and its sign (0 or $FFFF) to hi. */
    private void storeWithSign(String lo, String hi) {
        String positive = label();
        ins("sta", lo);
        ins("ldx", imm(0));
        ins("cmp", imm(0x8000));
        ins("bcc", positive);
        ins("dex", null);
        out.label(positive);
        ins("stx", hi);
    }

    private void signExtend(Value v, MType t, String lo, String hi) {
        loadSigned16(v, t);
        storeWithSign(lo, hi);
    }

    /** Shifts by a constant up to this are written out; longer ones and variable ones loop. */
    private static final int UNROLL_SHIFT = 8;

    private void shift(Instr.Bin b) {
        MType t = b.type();
        int bits = t.bits();
        Value x = b.op(0);
        Value y = b.op(1);
        if (y instanceof Value.Const k && k.bits() <= UNROLL_SHIFT && k.bits() < bits) {
            unrolledShift(b, (int) k.bits());
            return;
        }
        // X = the shift count, at most the width (shifting further changes nothing: all 0, or all sign)
        if (y instanceof Value.Const k) {
            ins("ldx", imm(Math.min(k.bits(), bits)));
        } else {
            String have = label();
            String big = label();
            if (words(y.type()) == 2) {
                ins("lda", operand(y, 1));
                ins("bne", big);
            }
            ins("lda", operand(y, 0));
            ins("cmp", imm(bits));
            ins("bcc", have);
            out.label(big);
            ins("lda", imm(bits));
            out.label(have);
            ins("tax", null);
        }
        String loop = label();
        String done = label();
        if (words(t) == 1) {
            if (b.op == Instr.BinOp.ASHR) {
                loadSigned16(x, t);
            } else {
                ins("lda", operand(x, 0));
            }
            ins("cpx", imm(0));
            ins("beq", done);
            out.label(loop);
            switch (b.op) {
                case SHL -> ins("asl", "a");
                case LSHR -> ins("lsr", "a");
                default -> {
                    ins("cmp", imm(0x8000));    // C = the sign
                    ins("ror", "a");
                }
            }
            ins("dex", null);
            ins("bne", loop);
            out.label(done);
            mask(t);
            ins("sta", dest(b, 0));
        } else {
            String lo = dest(b, 0);
            String hi = dest(b, 1);
            to32(x, lo, hi);
            ins("cpx", imm(0));
            ins("beq", done);
            out.label(loop);
            switch (b.op) {
                case SHL -> {
                    ins("asl", lo);
                    ins("rol", hi);
                }
                case LSHR -> {
                    ins("lsr", hi);
                    ins("ror", lo);
                }
                default -> {
                    ins("lda", hi);
                    ins("cmp", imm(0x8000));
                    ins("ror", hi);
                    ins("ror", lo);
                }
            }
            ins("dex", null);
            ins("bne", loop);
            out.label(done);
        }
    }

    private void unrolledShift(Instr.Bin b, int count) {
        MType t = b.type();
        Value x = b.op(0);
        if (words(t) == 1) {
            if (b.op == Instr.BinOp.ASHR) {
                loadSigned16(x, t);
            } else {
                ins("lda", operand(x, 0));
            }
            for (int k = 0; k < count; k++) {
                switch (b.op) {
                    case SHL -> ins("asl", "a");
                    case LSHR -> ins("lsr", "a");
                    default -> {
                        ins("cmp", imm(0x8000));    // C = the sign
                        ins("ror", "a");
                    }
                }
            }
            mask(t);
            ins("sta", dest(b, 0));
            return;
        }
        String lo = dest(b, 0);
        String hi = dest(b, 1);
        to32(x, lo, hi);
        for (int k = 0; k < count; k++) {
            switch (b.op) {
                case SHL -> {
                    ins("asl", lo);
                    ins("rol", hi);
                }
                case LSHR -> {
                    ins("lsr", hi);
                    ins("ror", lo);
                }
                default -> {
                    ins("lda", hi);
                    ins("cmp", imm(0x8000));
                    ins("ror", hi);
                    ins("ror", lo);
                }
            }
        }
    }

    private void cmp(Instr.Cmp c) {
        MType t = c.op(0).type();
        int n = words(t);
        Value a = c.op(0);
        Value b = c.op(1);
        switch (c.pred) {
            case EQ, NE -> {
                String differ = label();
                String done = label();
                for (int w = 0; w < n; w++) {
                    ins("lda", operand(a, w));
                    ins("cmp", operand(b, w));
                    ins("bne", differ);
                }
                ins("lda", imm(c.pred == Instr.Pred.EQ ? 1 : 0));
                ins("bra", done);
                out.label(differ);
                ins("lda", imm(c.pred == Instr.Pred.EQ ? 0 : 1));
                out.label(done);
                ins("sta", dest(c, 0));
            }
            default -> {
                // x < y, or its negation; signed by flipping the sign bits, so an unsigned compare works
                boolean swap = switch (c.pred) {
                    case UGT, ULE, SGT, SLE -> true;
                    default -> false;
                };
                boolean wantLess = switch (c.pred) {
                    case ULT, UGT, SLT, SGT -> true;
                    default -> false;
                };
                boolean signed = switch (c.pred) {
                    case SLT, SLE, SGT, SGE -> true;
                    default -> false;
                };
                Value x = swap ? b : a;
                Value y = swap ? a : b;
                int top = n - 1;
                int bias = !signed ? 0 : t == MType.I8 ? 0x80 : t == MType.I1 ? 1 : 0x8000;
                String yTop = operand(y, top);
                if (bias != 0) {
                    if (y instanceof Value.Const k) {
                        yTop = imm((k.bits() >> (16 * top)) ^ bias);
                    } else {
                        ins("lda", operand(y, top));
                        ins("eor", imm(bias));
                        ins("sta", "z:R0");
                        yTop = "z:R0";
                    }
                }
                if (n == 2) {
                    ins("lda", operand(x, 0));
                    ins("cmp", operand(y, 0));
                }
                ins("lda", operand(x, top));
                if (bias != 0) {
                    ins("eor", imm(bias));
                }
                ins(n == 2 ? "sbc" : "cmp", yTop);   // C = x >= y
                ins("lda", imm(0));
                ins("rol", "a");
                if (wantLess) {
                    ins("eor", imm(1));
                }
                ins("sta", dest(c, 0));
            }
        }
    }

    private void cast(Instr.Cast c) {
        MType from = c.op(0).type();
        MType to = c.type();
        if (c.kind == Instr.CastKind.SEXT) {
            loadSigned16(c.op(0), from);
            if (words(to) == 1) {
                mask(to);
                ins("sta", dest(c, 0));
            } else {
                storeWithSign(dest(c, 0), dest(c, 1));
            }
            return;
        }
        // zext, trunc, ptrtoint, inttoptr: the low bits, zero-extended
        ins("lda", operand(c.op(0), 0));
        mask(to);
        ins("sta", dest(c, 0));
        if (words(to) == 2) {
            if (words(from) == 2) {
                ins("lda", operand(c.op(0), 1));
                ins("sta", dest(c, 1));
            } else {
                ins("stz", dest(c, 1));
            }
        }
    }

    private void load(Instr.Load l) {
        MType t = l.type();
        Value address = l.op(0);
        boolean direct = fixed(address, 0) != null;
        if (!direct) {
            pointer(address, P);
        }
        if (t.size == 1) {
            if (!direct) {
                ins("ldy", imm(0));
            }
            ins("sep", "#$20");
            ins("lda", direct ? fixed(address, 0) : VIA_P);
            ins("rep", "#$20");
            ins("and", imm(0xFF));   // B, the high byte of A, still has an old value
            mask(t);
            ins("sta", dest(l, 0));
            return;
        }
        for (int w = 0; w < words(t); w++) {
            if (!direct) {
                ins("ldy", imm(2 * w));
            }
            ins("lda", direct ? fixed(address, 2 * w) : VIA_P);
            ins("sta", dest(l, w));
        }
    }

    private void store(Instr.Store s) {
        Value address = s.op(0);
        Value v = s.op(1);
        MType t = v.type();
        boolean direct = fixed(address, 0) != null;
        if (!direct) {
            pointer(address, P);
        }
        if (t.size == 1) {
            ins("lda", operand(v, 0));
            if (!direct) {
                ins("ldy", imm(0));
            }
            ins("sep", "#$20");
            ins("sta", direct ? fixed(address, 0) : VIA_P);
            ins("rep", "#$20");
            return;
        }
        for (int w = 0; w < words(t); w++) {
            ins("lda", operand(v, w));
            if (!direct) {
                ins("ldy", imm(2 * w));
            }
            ins("sta", direct ? fixed(address, 2 * w) : VIA_P);
        }
    }

    private void copy(Instr.Copy c) {
        pointer(c.op(1), P);
        pointer(c.op(0), Q);
        int size = c.size;
        if (size % 2 == 1) {
            ins("ldy", imm(size - 1));
            ins("sep", "#$20");
            ins("lda", VIA_P);
            ins("sta", VIA_Q);
            ins("rep", "#$20");
        }
        if (size >= 2) {
            String loop = label();
            ins("ldy", imm((size & ~1) - 2));
            out.label(loop);
            ins("lda", VIA_P);
            ins("sta", VIA_Q);
            ins("dey", null);
            ins("dey", null);
            ins("bpl", loop);
        }
    }

    private void call(Instr.Call c) {
        MFunction callee = module.function(c.callee);
        if (callee == null) {
            throw new IllegalStateException("call of " + c.callee + ", which has no MIR");
        }
        for (int k = 0; k < c.operands().size(); k++) {
            Value.Param p = callee.params().get(k);
            for (int w = 0; w < words(p.type()); w++) {
                ins("lda", operand(c.op(k), w));
                ins("sta", at(place.get(p) + 2 * w));
            }
        }
        ins("jsl", c.callee);
        if (c.type() != MType.VOID) {
            ins("sta", dest(c, 0));
            if (words(c.type()) == 2) {
                ins("stx", dest(c, 1));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void ins(String mnemonic, String operand) {
        out.ins(mnemonic, operand, null);
    }

    private String label() {
        return "@L" + (labels++);
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
