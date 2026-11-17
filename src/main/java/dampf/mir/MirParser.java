package dampf.mir;

import dampf.frontend.SourcePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads MIR text, as {@link Printer} writes it, back into a module. Pass tests use it: MIR text
 * in, run the pass, compare with the expected text. Values may be used before the line that
 * defines them (φ operands from a loop's back edge); they are resolved at the end of each
 * function. Source positions are read from the "; file:line" comments.
 */
public final class MirParser {
    private final String[] lines;
    private int lineNo;
    private final MModule module = new MModule();

    // per function
    private MFunction fn;
    private final Map<String, Value.Param> params = new HashMap<>();
    private final Map<String, Value.Slot> slots = new HashMap<>();
    private final Map<String, Block> blocks = new HashMap<>();
    private final Map<String, Instr> defined = new HashMap<>();

    private record Fixup(Instr instr, int operand, String name) {}

    private final List<Fixup> fixups = new ArrayList<>();

    private MirParser(String text) {
        this.lines = text.split("\n", -1);
    }

    public static MModule parse(String text) {
        MirParser p = new MirParser(text);
        p.module();
        return p.module;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("MIR line " + (lineNo + 1) + ": " + message + ": " + lines[lineNo].strip());
    }

    private void module() {
        for (lineNo = 0; lineNo < lines.length; lineNo++) {
            String line = code(lines[lineNo]);
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("global ")) {
                global(new Tokens(line.substring(7)));
            } else if (line.startsWith("func ")) {
                function();
            } else {
                throw error("expected 'global' or 'func'");
            }
        }
    }

    /** The line without its comment. */
    private static String code(String line) {
        int c = line.indexOf(';');
        return (c < 0 ? line : line.substring(0, c)).strip();
    }

    private static SourcePos position(String line) {
        int c = line.indexOf(';');
        if (c < 0) {
            return null;
        }
        String comment = line.substring(c + 1).strip();
        int colon = comment.lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        try {
            return new SourcePos(comment.substring(0, colon), Integer.parseInt(comment.substring(colon + 1)), 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ globals

    private void global(Tokens t) {
        String symbol = t.symbol('@');
        t.expect(":");
        int size = t.integer();
        boolean readOnly = t.accept("const");
        List<MModule.Datum> init = new ArrayList<>();
        if (t.accept("=")) {
            t.expect("[");
            while (!t.accept("]")) {
                int offset = t.integer();
                t.expect(":");
                MType type = type(t.next());
                if (type == MType.PTR) {
                    String target = t.next();
                    if (!target.startsWith("@")) {
                        throw error("expected an address");
                    }
                    String[] parts = splitOffset(target.substring(1));
                    init.add(new MModule.Datum(offset, 4, Long.parseLong(parts[1]), parts[0]));
                } else {
                    init.add(new MModule.Datum(offset, type.size, type.wrap(t.longValue()), null));
                }
                t.accept(",");
            }
        }
        module.globals().add(new MModule.Global(symbol, size, init, readOnly));
    }

    /** "sym+4" or "sym-2" or "sym" into the symbol and the offset. */
    private static String[] splitOffset(String s) {
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '+' || c == '-') && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) {
                return new String[] {s.substring(0, i), Long.toString(Long.parseLong(s.substring(i + (c == '+' ? 1 : 0))))};
            }
        }
        return new String[] {s, "0"};
    }

    // ------------------------------------------------------------------ functions

    private void function() {
        Tokens t = new Tokens(code(lines[lineNo]).substring(5));
        String name = t.symbol('@');
        params.clear();
        slots.clear();
        blocks.clear();
        defined.clear();
        fixups.clear();
        t.expect("(");
        List<Value.Param> ps = new ArrayList<>();
        while (!t.accept(")")) {
            MType type = type(t.next());
            String pname = t.next();
            if (!pname.startsWith("%")) {
                throw error("expected a parameter name");
            }
            Value.Param p = new Value.Param(pname.substring(1), type);
            params.put(p.name(), p);
            ps.add(p);
            t.accept(",");
        }
        t.expect("->");
        MType result = type(t.next());
        t.expect("{");
        fn = new MFunction(name, result, null);
        fn.params().addAll(ps);
        module.functions().add(fn);

        // the lines of the body; first all block labels, so jumps can go forward
        int start = lineNo + 1;
        int end = start;
        while (end < lines.length && !code(lines[end]).equals("}")) {
            end++;
        }
        if (end == lines.length) {
            throw error("function without '}'");
        }
        for (int i = start; i < end; i++) {
            String c = code(lines[i]);
            if (c.endsWith(":") && !c.contains(" ")) {
                String label = c.substring(0, c.length() - 1);
                blocks.put(label, fn.addBlock(label));
            }
        }
        Block current = null;
        for (lineNo = start; lineNo < end; lineNo++) {
            String raw = lines[lineNo];
            String c = code(raw);
            if (c.isEmpty()) {
                continue;
            }
            if (c.startsWith("slot ")) {
                Tokens s = new Tokens(c.substring(5));
                String sname = s.symbol('$');
                s.expect(":");
                Value.Slot slot = new Value.Slot(sname, s.integer());
                fn.slots().add(slot);
                slots.put(sname, slot);
                continue;
            }
            if (c.endsWith(":") && !c.contains(" ")) {
                current = blocks.get(c.substring(0, c.length() - 1));
                continue;
            }
            if (current == null) {
                throw error("an instruction before the first block");
            }
            instruction(current, new Tokens(c), position(raw));
        }
        for (Fixup f : fixups) {
            Instr d = defined.get(f.name());
            if (d == null) {
                throw new IllegalArgumentException("MIR function " + name + ": %" + f.name() + " is used but never defined");
            }
            f.instr().setOp(f.operand(), d);
        }
        lineNo = end;
    }

    private void instruction(Block b, Tokens t, SourcePos pos) {
        String result = null;
        if (t.peek().startsWith("%") && t.peekAt(1).equals("=")) {
            result = t.next().substring(1);
            t.next();
        }
        String op = t.next();
        Instr instr;
        List<Pending> pending = new ArrayList<>();
        switch (op) {
            case "add", "sub", "mul", "udiv", "sdiv", "urem", "srem", "and", "or", "xor", "shl", "lshr", "ashr" -> {
                MType type = type(t.next());
                Value x = value(t.next(), type, pending, 0);
                t.expect(",");
                Value y = value(t.next(), type, pending, 1);
                instr = new Instr.Bin(Instr.BinOp.valueOf(op.toUpperCase()), x, y, pos);
            }
            case "cmp" -> {
                Instr.Pred pred = Instr.Pred.valueOf(t.next().toUpperCase());
                MType type = type(t.next());
                Value x = value(t.next(), type, pending, 0);
                t.expect(",");
                Value y = value(t.next(), type, pending, 1);
                instr = new Instr.Cmp(pred, x, y, pos);
            }
            case "zext", "sext", "trunc", "ptrtoint", "inttoptr" -> {
                MType from = type(t.next());
                Value x = value(t.next(), from, pending, 0);
                t.expect("to");
                instr = new Instr.Cast(Instr.CastKind.valueOf(op.toUpperCase()), x, type(t.next()), pos);
            }
            case "ptradd" -> {
                Value p = value(t.next(), MType.PTR, pending, 0);
                t.expect(",");
                MType ot = type(t.next());
                Value o = value(t.next(), ot, pending, 1);
                instr = new Instr.PtrAdd(p, o, pos);
            }
            case "load" -> {
                boolean vol = t.accept("volatile");
                MType type = type(t.next());
                instr = new Instr.Load(type, value(t.next(), MType.PTR, pending, 0), vol, pos);
            }
            case "store" -> {
                boolean vol = t.accept("volatile");
                MType type = type(t.next());
                Value a = value(t.next(), MType.PTR, pending, 0);
                t.expect(",");
                Value v = value(t.next(), type, pending, 1);
                instr = new Instr.Store(a, v, vol, pos);
            }
            case "copy" -> {
                int size = t.integer();
                Value to = value(t.next(), MType.PTR, pending, 0);
                t.expect(",");
                Value from = value(t.next(), MType.PTR, pending, 1);
                instr = new Instr.Copy(to, from, size, pos);
            }
            case "call" -> {
                MType type = type(t.next());
                String callee = t.symbol('@');
                t.expect("(");
                List<Value> args = new ArrayList<>();
                while (!t.accept(")")) {
                    MType at = type(t.next());
                    args.add(value(t.next(), at, pending, args.size()));
                    t.accept(",");
                }
                instr = new Instr.Call(callee, type, args, pos);
            }
            case "phi" -> {
                MType type = type(t.next());
                Instr.Phi phi = new Instr.Phi(type, pos);
                int k = 0;
                while (t.accept("[")) {
                    Block from = block(t.next());
                    t.expect(",");
                    phi.add(from, value(t.next(), type, pending, k++));
                    t.expect("]");
                    t.accept(",");
                }
                instr = phi;
            }
            case "jump" -> instr = new Instr.Jump(block(t.next()), pos);
            case "unreachable" -> instr = new Instr.Unreachable(pos);
            case "br" -> {
                Value c = value(t.next(), MType.I1, pending, 0);
                t.expect(",");
                Block yes = block(t.next());
                t.expect(",");
                instr = new Instr.Branch(c, yes, block(t.next()), pos);
            }
            case "ret" -> {
                if (!t.hasNext()) {
                    instr = new Instr.Ret(null, pos);
                } else {
                    MType type = type(t.next());
                    instr = new Instr.Ret(value(t.next(), type, pending, 0), pos);
                }
            }
            case "switch" -> {
                MType type = type(t.next());
                Value v = value(t.next(), type, pending, 0);
                t.expect(",");
                Instr.Switch s = new Instr.Switch(v, block(t.next()), pos);
                t.expect("[");
                while (!t.accept("]")) {
                    s.cases.add(t.longValue());
                    t.expect(":");
                    s.targets.add(block(t.next()));
                    t.accept(",");
                }
                instr = s;
            }
            default -> throw error("unknown instruction '" + op + "'");
        }
        if (t.hasNext()) {
            throw error("unexpected '" + t.peek() + "'");
        }
        b.add(instr);
        for (Pending p : pending) {
            fixups.add(new Fixup(instr, p.operand(), p.name()));
        }
        if (result != null) {
            if (instr.type() == MType.VOID) {
                throw error("this instruction has no result");
            }
            if (defined.put(result, instr) != null) {
                throw error("%" + result + " is defined twice");
            }
        } else if (instr.type() != MType.VOID) {
            throw error("the result is not named");
        }
    }

    private record Pending(int operand, String name) {}

    private Value value(String token, MType type, List<Pending> pending, int operand) {
        if (token.startsWith("%")) {
            String n = token.substring(1);
            Value.Param p = params.get(n);
            if (p != null) {
                return p;
            }
            Instr d = defined.get(n);
            if (d != null) {
                return d;
            }
            pending.add(new Pending(operand, n));
            return new Value.Undef(type); // replaced once the definition has been read
        }
        if (token.startsWith("@")) {
            return new Value.GlobalAddr(token.substring(1));
        }
        if (token.startsWith("$")) {
            Value.Slot s = slots.get(token.substring(1));
            if (s == null) {
                throw error("unknown slot " + token);
            }
            return new Value.SlotAddr(s);
        }
        return switch (token) {
            case "true" -> new Value.Const(MType.I1, 1);
            case "false" -> new Value.Const(MType.I1, 0);
            case "undef" -> new Value.Undef(type);
            default -> {
                try {
                    yield new Value.Const(type, Long.parseLong(token));
                } catch (NumberFormatException e) {
                    throw error("not a value: '" + token + "'");
                }
            }
        };
    }

    private Block block(String name) {
        Block b = blocks.get(name);
        if (b == null) {
            throw error("unknown block " + name);
        }
        return b;
    }

    private MType type(String token) {
        try {
            return MType.valueOf(token.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw error("not a type: '" + token + "'");
        }
    }

    /** Words and the punctuation , [ ] ( ) : = of one line. */
    private final class Tokens {
        private final List<String> list = new ArrayList<>();
        private int pos;

        Tokens(String text) {
            StringBuilder word = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '>') {
                    flush(word);
                    list.add("->");
                    i++;
                } else if (",[]():=".indexOf(c) >= 0) {
                    flush(word);
                    list.add(String.valueOf(c));
                } else if (Character.isWhitespace(c)) {
                    flush(word);
                } else {
                    word.append(c);
                }
            }
            flush(word);
        }

        private void flush(StringBuilder w) {
            if (!w.isEmpty()) {
                list.add(w.toString());
                w.setLength(0);
            }
        }

        boolean hasNext() {
            return pos < list.size();
        }

        String peek() {
            return pos < list.size() ? list.get(pos) : "";
        }

        String peekAt(int k) {
            return pos + k < list.size() ? list.get(pos + k) : "";
        }

        String next() {
            if (pos >= list.size()) {
                throw error("the line ends too early");
            }
            return list.get(pos++);
        }

        boolean accept(String s) {
            if (peek().equals(s)) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(String s) {
            if (!accept(s)) {
                throw error("expected '" + s + "', found '" + peek() + "'");
            }
        }

        int integer() {
            String s = next();
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw error("expected a number, found '" + s + "'");
            }
        }

        long longValue() {
            String s = next();
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw error("expected a number, found '" + s + "'");
            }
        }

        String symbol(char sigil) {
            String s = next();
            if (s.isEmpty() || s.charAt(0) != sigil) {
                throw error("expected a name starting with '" + sigil + "'");
            }
            return s.substring(1);
        }
    }
}
