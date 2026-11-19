package superdevkit.sfx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for {@code sfx.json}: objects, arrays, strings, numbers, true, false and
 * null. Every value knows the line it starts on, so the converter's errors can name the place.
 * The kit asks for nothing but Java, so no library.
 */
final class Json {
    /** A value and the line it starts on (1-based). */
    record Value(Object v, int line) {
        /** The members of an object, in the file's order. */
        @SuppressWarnings("unchecked")
        Map<String, Value> object() {
            return (Map<String, Value>) v;
        }

        @SuppressWarnings("unchecked")
        List<Value> array() {
            return (List<Value>) v;
        }

        boolean isObject() {
            return v instanceof Map;
        }

        boolean isArray() {
            return v instanceof List;
        }
    }

    /** A file that is not JSON: the message names the line. */
    static final class SyntaxError extends Exception {
        private static final long serialVersionUID = 1L;

        SyntaxError(int line, String message) {
            super("line " + line + ": " + message);
        }
    }

    private final String s;
    private int i;
    private int line = 1;

    private Json(String s) {
        this.s = s;
    }

    static Value parse(String text) throws SyntaxError {
        Json p = new Json(text);
        p.space();
        Value v = p.value();
        p.space();
        if (p.i < p.s.length()) {
            throw new SyntaxError(p.line, "text after the end of the JSON value");
        }
        return v;
    }

    private void space() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n') {
                line++;
            } else if (c != ' ' && c != '\t' && c != '\r') {
                return;
            }
            i++;
        }
    }

    private Value value() throws SyntaxError {
        if (i >= s.length()) {
            throw new SyntaxError(line, "the file ends where a value should be");
        }
        int at = line;
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Value> m = new LinkedHashMap<>();
            space();
            if (peek('}')) {
                return new Value(m, at);
            }
            while (true) {
                space();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new SyntaxError(line, "a member name in quotes expected");
                }
                int keyLine = line;
                String key = string();
                if (m.containsKey(key)) {
                    throw new SyntaxError(keyLine, "\"" + key + "\" twice in one object");
                }
                space();
                expect(':');
                space();
                m.put(key, value());
                space();
                if (peek('}')) {
                    return new Value(m, at);
                }
                expect(',');
            }
        }
        if (c == '[') {
            i++;
            List<Value> a = new ArrayList<>();
            space();
            if (peek(']')) {
                return new Value(a, at);
            }
            while (true) {
                space();
                a.add(value());
                space();
                if (peek(']')) {
                    return new Value(a, at);
                }
                expect(',');
            }
        }
        if (c == '"') {
            return new Value(string(), at);
        }
        if (s.startsWith("true", i)) {
            i += 4;
            return new Value(Boolean.TRUE, at);
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return new Value(Boolean.FALSE, at);
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return new Value(null, at);
        }
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw new SyntaxError(line, "unexpected '" + c + "'");
        }
        try {
            return new Value(new BigDecimal(s.substring(start, i)), at);
        } catch (NumberFormatException e) {
            throw new SyntaxError(line, "not a number: " + s.substring(start, i));
        }
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) throws SyntaxError {
        if (!peek(c)) {
            throw new SyntaxError(line, "'" + c + "' expected");
        }
    }

    private String string() throws SyntaxError {
        i++; // the opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length() || s.charAt(i) == '\n') {
                throw new SyntaxError(line, "a string is not closed");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw new SyntaxError(line, "a string is not closed");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw new SyntaxError(line, "\\u needs four hex digits");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException x) {
                        throw new SyntaxError(line, "\\u needs four hex digits");
                    }
                    i += 4;
                }
                default -> throw new SyntaxError(line, "unknown escape \\" + e);
            }
        }
    }
}
