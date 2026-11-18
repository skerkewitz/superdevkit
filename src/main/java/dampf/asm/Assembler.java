package dampf.asm;

import dampf.lir.LDirective;
import dampf.lir.LInstr;
import dampf.lir.LItem;
import dampf.lir.LLabel;
import dampf.lir.LParser;
import dampf.target.Cycles65816;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles ca65 source into an {@link ObjectFile}: the subset of ca65 that dampf writes and its
 * runtime uses, read with {@link LParser}, plus a label and an instruction on one line and
 * {@code .if}/{@code .else}/{@code .endif} on a defined symbol. Anything else is an error.
 *
 * <p>Like ca65 it works in one pass. An operand's size is chosen when the line is read: by its
 * prefix ({@code z:} direct page, {@code a:} absolute, {@code f:} long), else by what is known of
 * its symbols at that line (a symbol imported with {@code .importzp}, a label in the segment
 * ZEROPAGE, or an equate with such a value is in the direct page; a number by its size; anything
 * else absolute). Immediates have the size {@code .a8}/{@code .a16} and {@code .i8}/{@code .i16}
 * say ({@code .smart off}). Every value that needs a symbol is filled in by the linker.
 *
 * <p>Scopes as in ca65: {@code .proc} opens one (its labels are {@code proc::name}, found from
 * inside before the outer ones), a cheap local label ({@code @x}) belongs to the normal label
 * before it, and {@code :+}/{@code :-} go to the next or previous unnamed label.
 */
public final class Assembler {
    private static final Pattern LABEL_AND_CODE = Pattern.compile("^(@?[A-Za-z_][A-Za-z0-9_]*):\\s+([a-zA-Z].*)$");
    private static final Set<String> INDEX_IMMEDIATE = Set.of("ldx", "ldy", "cpx", "cpy");
    private static final Set<String> BYTE_IMMEDIATE = Set.of("rep", "sep", "brk", "cop", "wdm");

    private final ObjectFile obj;
    private final Map<String, Long> defines;
    private ObjectFile.Section section;
    private final Deque<String> scopes = new ArrayDeque<>();
    private String cheapScope = "";
    private int unnamed;
    private boolean a16 = true;
    private boolean i16 = true;
    /** Names known (so far) to be in the direct page: importzp, ZEROPAGE labels, equates of such. */
    private final Set<String> zeropage = new HashSet<>();
    /** Names defined so far (full names). */
    private final Set<String> defined = new HashSet<>();
    private final List<String> globals = new ArrayList<>();
    private String dbgFile;
    private int dbgLine;
    private int lineNumber;

    private Assembler(String name, Map<String, Long> defines) {
        this.obj = new ObjectFile(name);
        this.defines = defines;
    }

    /** Assembles {@code source}; {@code defines} are the symbols of {@code -D} (for {@code .if}). */
    public static ObjectFile assemble(String name, String source, Map<String, Long> defines) {
        Assembler a = new Assembler(name, defines);
        a.run(source);
        return a.obj;
    }

    private void run(String source) {
        Deque<boolean[]> conditions = new ArrayDeque<>();   // per .if: [taking now, taken already]
        for (String raw : source.split("\n", -1)) {
            lineNumber++;
            String text = raw.replace("\t", "        ").stripTrailing();
            String code = stripComment(text).strip();
            try {
                if (code.startsWith(".if ") || code.startsWith(".if\t")) {
                    boolean outer = conditions.stream().allMatch(c -> c[0]);
                    boolean v = outer && condition(code.substring(3).strip());
                    conditions.push(new boolean[] {v, v});
                    continue;
                }
                if (code.equals(".else")) {
                    boolean[] c = conditions.peek();
                    boolean outer = conditions.stream().skip(1).allMatch(x -> x[0]);
                    c[0] = outer && !c[1];
                    continue;
                }
                if (code.equals(".endif")) {
                    conditions.pop();
                    continue;
                }
                if (!conditions.stream().allMatch(c -> c[0])) {
                    continue;
                }
                Matcher m = LABEL_AND_CODE.matcher(code);
                if (m.matches()) {
                    item(LParser.line(m.group(1) + ":"));
                    item(LParser.line("        " + m.group(2)));
                } else {
                    item(LParser.line(text));
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(obj.name + ":" + lineNumber + ": " + e.getMessage() + " (" + raw.strip() + ")", e);
            }
        }
        if (!scopes.isEmpty()) {
            throw new IllegalArgumentException(obj.name + ": .proc " + scopes.peek() + " without .endproc");
        }
        finish();
    }

    private boolean condition(String expr) {
        Long v = Expr.parse(expr).eval(defines::get);
        if (v == null) {
            throw new IllegalArgumentException(".if needs a value known from -D: " + expr);
        }
        return v != 0;
    }

    private static String stripComment(String s) {
        boolean quoted = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ';' && !quoted) {
                return s.substring(0, k);
            }
        }
        return s;
    }

    // ---------------------------------------------------------------- lines

    private void item(LItem item) {
        switch (item) {
            case LLabel l -> label(l.name());
            case LInstr in -> instr(in);
            case LDirective d -> directive(d);
            default -> { }   // C line headers are comments
        }
    }

    private String scopePrefix() {
        return scopes.isEmpty() ? "" : String.join("::", scopes.reversed()) + "::";
    }

    private void label(String name) {
        ObjectFile.Section s = current();
        String full;
        if (name.isEmpty()) {
            full = ":" + unnamed++;
        } else if (name.startsWith("@")) {
            full = cheapScope + "::" + name;
        } else {
            full = scopePrefix() + name;
            cheapScope = full;
        }
        define(new ObjectFile.Symbol(full, s.name, s.size(), null, s.name.equals("ZEROPAGE")));
    }

    private void define(ObjectFile.Symbol sym) {
        if (obj.symbols.containsKey(sym.name())) {
            throw new IllegalArgumentException("'" + sym.name() + "' is defined twice");
        }
        obj.symbols.put(sym.name(), sym);
        defined.add(sym.name());
        if (sym.zeropage()) {
            zeropage.add(sym.name());
        }
    }

    private ObjectFile.Section current() {
        if (section == null) {
            section = obj.section("CODE");
        }
        return section;
    }

    private void directive(LDirective d) {
        if (d.label() != null && d.kind() != LDirective.Kind.EQUATE) {
            label(d.label());
        }
        List<String> args = d.args();
        switch (d.kind()) {
            case BLANK, COMMENT, NOTE, P816 -> { }
            case SMART -> {
                if (!args.equals(List.of("off"))) {
                    throw new IllegalArgumentException("only .smart off is supported");
                }
            }
            case A8 -> a16 = false;
            case A16 -> a16 = true;
            case I8 -> i16 = false;
            case I16 -> i16 = true;
            case SEGMENT -> section = obj.section(unquote(args.getFirst()));
            case PROC -> {
                String name = args.getFirst();
                label(name);
                scopes.push(name);
                cheapScope = scopePrefix();
            }
            case ENDPROC -> {
                if (scopes.isEmpty()) {
                    throw new IllegalArgumentException(".endproc without .proc");
                }
                scopes.pop();
                cheapScope = scopePrefix();
            }
            case EQUATE -> {
                Expr e = resolveLater(Expr.parse(args.getFirst()));
                String full = scopePrefix() + d.label();
                define(new ObjectFile.Symbol(full, null, 0, e, addressSize(Expr.parse(args.getFirst())) == 1));
            }
            case IMPORT, IMPORTZP -> {
                for (String n : args) {
                    obj.imports.add(n);
                    if (d.kind() == LDirective.Kind.IMPORTZP) {
                        zeropage.add(n);
                    }
                }
            }
            case EXPORT, EXPORTZP -> obj.exports.addAll(args);
            case GLOBAL -> globals.addAll(args);
            case BYTE -> {
                for (String a : args) {
                    if (a.startsWith("\"")) {
                        for (char c : unquote(a).toCharArray()) {
                            current().emit(c);
                        }
                    } else {
                        value(Expr.parse(a), 1, "byte");
                    }
                }
            }
            case WORD -> {
                for (String a : args) {
                    value(Expr.parse(a), 2, "word");
                }
            }
            case RES -> {
                Long n = Expr.parse(args.getFirst()).eval(x -> null);
                Long fill = args.size() > 1 ? Expr.parse(args.get(1)).eval(x -> null) : null;
                if (n == null) {
                    throw new IllegalArgumentException(".res needs a number");
                }
                ObjectFile.Section s = current();
                if (fill == null && writesNothing(s.name)) {
                    s.reserved += n.intValue();
                } else {
                    for (int k = 0; k < n; k++) {
                        s.emit(fill == null ? 0 : fill.intValue());
                    }
                }
            }
            case DBG -> dbg(args);
            default -> throw new IllegalArgumentException("the directive " + d.text().strip() + " is not supported");
        }
    }

    /** Segments whose contents are not in the ROM (their .res reserve only). */
    static boolean writesNothing(String segment) {
        return segment.equals("BSS") || segment.equals("ZEROPAGE");
    }

    private void dbg(List<String> args) {
        switch (args.getFirst()) {
            case "file" -> obj.dbgFiles.put(unquote(args.get(1)), new long[] {Long.parseLong(args.get(2)), Long.parseLong(args.get(3))});
            case "line" -> {
                dbgFile = unquote(args.get(1));
                dbgLine = Integer.parseInt(args.get(2));
            }
            case "func" -> obj.dbgFuncs.add(new ObjectFile.DbgFunc(unquote(args.get(1)), unquote(args.get(4))));
            default -> { }   // sym: ld65 writes it, Mesen does not show static locals from it
        }
    }

    private static String unquote(String s) {
        if (s.length() < 2 || !s.startsWith("\"") || !s.endsWith("\"")) {
            throw new IllegalArgumentException("expected a string: " + s);
        }
        return s.substring(1, s.length() - 1);
    }

    // ---------------------------------------------------------------- instructions

    private void instr(LInstr in) {
        ObjectFile.Section s = current();
        int start = s.size();
        encode(in);
        if (dbgFile != null) {
            obj.lines.add(new ObjectFile.Line(s.name, start, s.size() - start, dbgFile, dbgLine));
        }
    }

    private void encode(LInstr in) {
        String m = in.mnemonic();
        String op = in.operand();
        if (m.equals("mvn") || m.equals("mvp")) {
            // ca65: mvn src, dst; the bytes are opcode, dst bank, src bank
            String[] banks = op.split(",");
            emitOpcode(m, "");
            value(Expr.parse(stripHash(banks[1].strip())), 1, "bank");
            value(Expr.parse(stripHash(banks[0].strip())), 1, "bank");
            return;
        }
        if (op == null) {
            emitOpcode(m, implied(m));
            return;
        }
        if (op.equals("a")) {
            emitOpcode(m, "a");
            return;
        }
        if (op.startsWith("#")) {
            int size = BYTE_IMMEDIATE.contains(m) ? 1 : INDEX_IMMEDIATE.contains(m) ? (i16 ? 2 : 1) : (a16 ? 2 : 1);
            emitOpcode(m, "#imm");
            value(Expr.parse(op.substring(1)), size, "immediate");
            return;
        }
        if (in.isConditionalBranch() || m.equals("bra")) {
            emitOpcode(m, "");
            relative(Expr.parse(op), 1);
            return;
        }
        if (m.equals("brl") || m.equals("per")) {
            emitOpcode(m, "");
            relative(Expr.parse(op), 2);
            return;
        }
        if (m.equals("pea")) {
            emitOpcode(m, "");
            value(Expr.parse(op), 2, "pea");
            return;
        }
        // memory operands
        String prefix = null;
        String body = op;
        if (op.length() > 2 && op.charAt(1) == ':' && "zaf".indexOf(op.charAt(0)) >= 0) {
            prefix = op.substring(0, 1);
            body = op.substring(2);
        }
        String index = "";
        if (body.endsWith(",x") || body.endsWith(",y") || body.endsWith(",s")) {
            index = body.substring(body.length() - 2);
            body = body.substring(0, body.length() - 2).strip();
        }
        if (body.startsWith("[") && body.endsWith("]")) {
            Expr e = Expr.parse(body.substring(1, body.length() - 1));
            String mode = m.equals("jml") || m.equals("jmp") ? "[abs]" : index.equals(",y") ? "[dp],y" : "[dp]";
            emitOpcode(m, mode);
            value(e, mode.equals("[abs]") ? 2 : 1, mode);
            return;
        }
        if (body.startsWith("(") && body.endsWith(")") && balanced(body)) {
            String inner = body.substring(1, body.length() - 1).strip();
            String mode;
            boolean jump = m.equals("jmp") || m.equals("jsr");
            if (inner.endsWith(",x")) {
                inner = inner.substring(0, inner.length() - 2);
                mode = jump ? "(abs,x)" : "(dp,x)";
            } else if (inner.endsWith(",s")) {
                inner = inner.substring(0, inner.length() - 2);
                mode = "(sr,s),y";
            } else {
                mode = jump ? "(abs)" : index.equals(",y") ? "(dp),y" : "(dp)";
            }
            emitOpcode(m, mode);
            value(Expr.parse(inner), mode.contains("abs") ? 2 : 1, mode);
            return;
        }
        Expr e = Expr.parse(body);
        if (index.equals(",s")) {
            emitOpcode(m, "sr,s");
            value(e, 1, "sr,s");
            return;
        }
        int size = prefix == null ? addressSize(e) : switch (prefix) {
            case "z" -> 1;
            case "a" -> 2;
            default -> 3;
        };
        if (m.equals("jsl") || m.equals("jml")) {
            size = 3;
        } else if (m.equals("jmp") || m.equals("jsr")) {
            size = Math.max(2, Math.min(size, 2));
        }
        String[] names = index.isEmpty() ? new String[] {"dp", "abs", "long"}
                : index.equals(",x") ? new String[] {"dp,x", "abs,x", "long,x"} : new String[] {"dp,y", "abs,y", null};
        for (int k = size - 1; k < 3; k++) {
            if (names[k] != null && Cycles65816.opcode(m, names[k]) >= 0) {
                if (prefix != null && k != size - 1) {
                    throw new IllegalArgumentException(m + " has no " + names[size - 1] + " form");
                }
                emitOpcode(m, names[k]);
                value(e, k + 1, names[k]);
                return;
            }
        }
        throw new IllegalArgumentException("no form of " + m + " for " + op);
    }

    private static boolean balanced(String s) {
        int depth = 0;
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == '(') {
                depth++;
            } else if (s.charAt(k) == ')') {
                depth--;
                if (depth == 0 && k < s.length() - 1) {
                    return false;   // (a)+(b)
                }
            }
        }
        return true;
    }

    private static String stripHash(String s) {
        return s.startsWith("#") ? s.substring(1) : s;
    }

    private static String implied(String m) {
        return switch (m) {
            case "asl", "lsr", "rol", "ror", "inc", "dec" -> "a";
            default -> "";
        };
    }

    private void emitOpcode(String m, String mode) {
        int op = Cycles65816.opcode(m, mode);
        if (op < 0) {
            throw new IllegalArgumentException("no instruction " + m + (mode.isEmpty() ? "" : " " + mode));
        }
        current().emit(op);
    }

    /** {@code size} bytes of e's value: now if it is a number, else at link time. */
    private void value(Expr e, int size, String what) {
        ObjectFile.Section s = current();
        Long v = e.eval(x -> null);
        if (v != null) {
            if (!e.truncates() && (v >= 1L << (8 * size) || v < -(1L << (8 * size - 1)))) {
                throw new IllegalArgumentException("the value " + v + " does not fit into " + size + " byte(s) (" + what + ")");
            }
            for (int k = 0; k < size; k++) {
                s.emit((int) (v >> (8 * k)));
            }
            return;
        }
        s.fixups.add(new ObjectFile.Fixup(s.size(), size, ObjectFile.Fixup.Kind.VALUE, resolveLater(e), obj.name + ":" + lineNumber));
        for (int k = 0; k < size; k++) {
            s.emit(0);
        }
    }

    private void relative(Expr e, int size) {
        ObjectFile.Section s = current();
        s.fixups.add(new ObjectFile.Fixup(s.size(), size, ObjectFile.Fixup.Kind.RELATIVE, resolveLater(e), obj.name + ":" + lineNumber));
        for (int k = 0; k < size; k++) {
            s.emit(0);
        }
    }

    // ---------------------------------------------------------------- symbols

    /**
     * The address size of an operand without prefix, from what is known at this line: 1 (direct
     * page), 2 (absolute) or 3 (long).
     */
    private int addressSize(Expr e) {
        return switch (e) {
            case Expr.Num n -> n.value() >= 0 && n.value() < 0x100 ? 1 : n.value() >= 0 && n.value() < 0x10000 ? 2 : 3;
            case Expr.Sym s -> isZeropage(s.name()) ? 1 : 2;
            case Expr.Unary u -> switch (u.op()) {
                case "<", ">", "^", ".lobyte", ".hibyte", ".bankbyte" -> 1;
                case ".loword", ".hiword" -> 2;
                default -> addressSize(u.e());
            };
            case Expr.Binary b -> Math.max(addressSize(b.a()), addressSize(b.b()));
        };
    }

    private boolean isZeropage(String name) {
        if (name.startsWith("@") || name.startsWith(":")) {
            return false;
        }
        String prefix = scopePrefix();
        while (true) {
            if (defined.contains(prefix + name)) {
                return zeropage.contains(prefix + name);
            }
            if (prefix.isEmpty()) {
                return zeropage.contains(name);
            }
            int k = prefix.lastIndexOf("::", prefix.length() - 3);
            prefix = k < 0 ? "" : prefix.substring(0, k + 2);
        }
    }

    /**
     * Gives cheap local and unnamed labels their full names now (they depend on where they are
     * used); other names are marked with the scope they are used in and found when the file is
     * done ({@link #finish}), since a label of the scope may be defined further down.
     */
    private Expr resolveLater(Expr e) {
        String scope = scopePrefix();
        return e.mapSymbols(name -> {
            if (name.startsWith("@")) {
                return new Expr.Sym(cheapScope + "::" + name);
            }
            if (name.startsWith(":") && !name.startsWith("::")) {
                int n = name.length() - 1;
                int target = name.charAt(1) == '+' ? unnamed + n - 1 : unnamed - n;
                return new Expr.Sym(":" + target);
            }
            if (name.startsWith("::")) {
                return new Expr.Sym(name.substring(2));
            }
            return new Expr.Sym("?" + scope + "|" + name);
        });
    }

    private String lookup(String marked) {
        int bar = marked.indexOf('|');
        String prefix = marked.substring(1, bar);
        String name = marked.substring(bar + 1);
        while (true) {
            if (obj.symbols.containsKey(prefix + name)) {
                return prefix + name;
            }
            if (prefix.isEmpty()) {
                return name;   // global: defined here or imported
            }
            int k = prefix.lastIndexOf("::", prefix.length() - 3);
            prefix = k < 0 ? "" : prefix.substring(0, k + 2);
        }
    }

    private Expr finishExpr(Expr e) {
        return e.mapSymbols(n -> new Expr.Sym(n.startsWith("?") ? lookup(n) : n));
    }

    private void finish() {
        for (ObjectFile.Section s : obj.sections.values()) {
            List<ObjectFile.Fixup> done = new ArrayList<>();
            for (ObjectFile.Fixup f : s.fixups) {
                done.add(new ObjectFile.Fixup(f.offset(), f.size(), f.kind(), finishExpr(f.expr()), f.where()));
            }
            s.fixups.clear();
            s.fixups.addAll(done);
        }
        Map<String, ObjectFile.Symbol> syms = new HashMap<>(obj.symbols);
        for (ObjectFile.Symbol sym : syms.values()) {
            if (sym.value() != null) {
                obj.symbols.put(sym.name(), new ObjectFile.Symbol(sym.name(), null, 0, finishExpr(sym.value()), sym.zeropage()));
            }
        }
        for (String g : globals) {
            if (obj.symbols.containsKey(g)) {
                obj.exports.add(g);
            } else {
                obj.imports.add(g);
            }
        }
        for (String e : obj.exports) {
            if (!obj.symbols.containsKey(e)) {
                throw new IllegalArgumentException(obj.name + ": '" + e + "' is exported but not defined");
            }
        }
    }
}
