package dampf.lir;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads LIR from its text, the ca65 source dampf writes ({@link LPrinter}): {@link #line} one
 * line, {@link #parse} a whole file into a unit with functions and blocks. It knows the
 * directives dampf writes; any other line is an error. The accumulator width of an instruction
 * follows {@code REP}/{@code SEP #$20} along the edges of its function's blocks, as the code
 * runs and as the printer's cycle column assumes (in the order of the text where no way reaches a
 * block); ca65 sizes the immediates by {@code .a8}/{@code .a16}.
 */
public final class LParser {
    private static final Pattern SOURCE_LINE = Pattern.compile("^(; [^\\s:]+:\\d+(?:   .*?)?)(?:\\s+\\[ (\\d+)(?:-(\\d+))? mc( \\+ calls)? \\])?$");
    private static final Pattern EQUATE = Pattern.compile("^([A-Za-z_@][A-Za-z0-9_@]*)\\s*=\\s*(.*)$");
    private static final Pattern LABEL = Pattern.compile("^([A-Za-z_@][A-Za-z0-9_@]*)?:(.*)$");
    private static final Pattern CYCLES = Pattern.compile("^(.*?)\\s+(\\d+(?:-\\d+)?(?:/\\d+)?|\\?)$");

    private LParser() {}

    /** A whole file. */
    public static LUnit parse(String text) {
        LUnit unit = new LUnit();
        if (text.isEmpty()) {
            return unit;
        }
        boolean m16 = true;
        int n = 0;
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        for (String l : body.split("\n", -1)) {
            n++;
            LItem item;
            try {
                item = line(l, m16);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + n + ": " + e.getMessage(), e);
            }
            if (item instanceof LInstr in && "#$20".equals(in.operand())) {
                if (in.mnemonic().equals("rep")) {
                    m16 = true;
                } else if (in.mnemonic().equals("sep")) {
                    m16 = false;
                }
            }
            unit.add(item);
        }
        for (LFunction f : unit.functions()) {
            followEdges(f);
        }
        return unit;
    }

    /**
     * The accumulator width of each instruction along the edges, from the function's start with
     * 16 bits, where the text order says otherwise: a block that a branch enters with another
     * width than the code above it in the text ends with (dampf's code places the switches by the
     * edges, {@link Widths}). Blocks no way reaches, and functions whose ways in disagree, keep the
     * order of the text.
     */
    private static void followEdges(LFunction f) {
        if (f.blocks().isEmpty()) {
            return;
        }
        java.util.Map<LBlock, Integer> entry = Widths.entryWidths(f);
        if (entry == null) {
            return;
        }
        boolean changed = false;
        for (LBlock b : f.blocks()) {
            Integer start = entry.get(b);
            if (start == null) {
                continue;
            }
            boolean m16 = start == 16;
            List<LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr in)) {
                    continue;
                }
                if (in.m16() != m16) {
                    items.set(k, new LInstr(in.mnemonic(), in.op(), in.comment(), m16, in.x16(), in.relaxable()));
                    changed = true;
                }
                if ("#$20".equals(in.operand()) && (in.mnemonic().equals("rep") || in.mnemonic().equals("sep"))) {
                    m16 = in.mnemonic().equals("rep");
                }
            }
        }
        if (changed) {
            f.rebuild(f.items());
        }
    }

    /** One line; an instruction runs with a 16-bit accumulator. */
    public static LItem line(String text) {
        return line(text, true);
    }

    /** One line; {@code m16}: the accumulator width an instruction on it runs in. */
    public static LItem line(String text, boolean m16) {
        if (text.isEmpty()) {
            return LDirective.BLANK_LINE;
        }
        if (text.startsWith(";")) {
            Matcher s = SOURCE_LINE.matcher(text);
            if (s.matches()) {
                LSourceLine sl = new LSourceLine(s.group(1));
                if (s.group(2) != null) {
                    int best = Integer.parseInt(s.group(2));
                    sl.setCycles(new dampf.target.MasterCycles.Range(best, s.group(3) == null ? best : Integer.parseInt(s.group(3))),
                            s.group(4) != null);
                }
                return sl;
            }
            return new LDirective(LDirective.Kind.COMMENT, null, List.of(), text.substring(1).strip(), text);
        }
        String body = text.strip();
        if (body.startsWith(";")) {
            return new LDirective(LDirective.Kind.NOTE, null, List.of(), body.substring(1).strip(), text);
        }
        if (Character.isWhitespace(text.charAt(0))) {
            return body.startsWith(".") ? directive(null, body, text) : instr(body, m16);
        }
        Matcher e = EQUATE.matcher(text);
        if (e.matches()) {
            String[] vc = splitComment(e.group(2));
            return new LDirective(LDirective.Kind.EQUATE, e.group(1), List.of(vc[0].strip()), vc[1], text);
        }
        Matcher l = LABEL.matcher(text);
        if (l.matches()) {
            String name = l.group(1) == null ? "" : l.group(1);
            String rest = l.group(2).strip();
            if (rest.isEmpty() || rest.startsWith(";")) {
                return new LLabel(name, text);
            }
            if (rest.startsWith(".")) {
                return directive(name, rest, text);
            }
            throw new IllegalArgumentException("code after a label on the same line: " + text);
        }
        if (text.startsWith(".")) {
            return directive(null, body, text);
        }
        throw new IllegalArgumentException("not understood: " + text);
    }

    private static LDirective directive(String label, String body, String text) {
        String[] bc = splitComment(body);
        String d = bc[0].strip();
        int sp = 0;
        while (sp < d.length() && !Character.isWhitespace(d.charAt(sp))) {
            sp++;
        }
        String name = d.substring(1, sp);
        LDirective.Kind kind = LDirective.Kind.of(name);
        if (kind == null) {
            throw new IllegalArgumentException("unknown directive ." + name);
        }
        return new LDirective(kind, label, args(d.substring(sp).strip()), bc[1], text);
    }

    private static LInstr instr(String body, boolean m16) {
        String[] bc = splitComment(body);
        String code = bc[0].strip();
        String comment = null;
        if (bc[1] != null) {
            Matcher c = CYCLES.matcher(" " + bc[1]);
            String rest = c.matches() ? c.group(1) : " " + bc[1];
            rest = rest.strip();
            comment = rest.isEmpty() ? null : rest;
        }
        int sp = code.indexOf(' ');
        String mnemonic = sp < 0 ? code : code.substring(0, sp);
        String operand = sp < 0 ? null : code.substring(sp).strip();
        if (!mnemonic.chars().allMatch(Character::isLetter)) {
            throw new IllegalArgumentException("not an instruction: " + body);
        }
        return new LInstr(mnemonic, operand, comment, m16);
    }

    /** Text before the first ';' outside quotes, and the comment after it (null if none). */
    private static String[] splitComment(String s) {
        boolean quoted = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ';' && !quoted) {
                return new String[] {s.substring(0, k), s.substring(k + 1).strip()};
            }
        }
        return new String[] {s, null};
    }

    /** Arguments split at commas outside quotes and parentheses. */
    static List<String> args(String s) {
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) {
            return out;
        }
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '(' || c == '[')) {
                depth++;
            } else if (!quoted && (c == ')' || c == ']')) {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                out.add(s.substring(start, k).strip());
                start = k + 1;
            }
        }
        out.add(s.substring(start).strip());
        return out;
    }
}
