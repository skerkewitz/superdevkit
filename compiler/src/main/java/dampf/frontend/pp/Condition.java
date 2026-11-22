package dampf.frontend.pp;

import java.util.List;

/**
 * The value of the expression of an {@code #if} or {@code #elif} (C99 6.10.1), after {@code defined}
 * and the macros are replaced and every name left is 0. The arithmetic is that of {@code intmax_t}
 * and {@code uintmax_t} (64 bits): an operation with an unsigned operand is unsigned, as in C.
 */
final class Condition {
    /** A value and whether it is unsigned. */
    private record Value(long v, boolean unsigned) {
        boolean isTrue() {
            return v != 0;
        }
    }

    private final List<Token> ts;
    private final Token at;
    private int p;

    private Condition(List<Token> ts, Token at) {
        this.ts = ts;
        this.at = at;
    }

    /** Whether the expression is true; {@code at} (the directive) is where an error is reported. */
    static boolean evaluate(List<Token> ts, Token at) {
        if (ts.isEmpty()) {
            throw new PpException("#" + at.text() + " with no expression", at);
        }
        Condition c = new Condition(ts, at);
        Value v = c.conditional(true);
        if (c.p < ts.size()) {
            throw c.error("missing binary operator before token \"" + ts.get(c.p).text() + "\"");
        }
        return v.isTrue();
    }

    private PpException error(String msg) {
        return new PpException(msg, at);
    }

    private Token peek() {
        return p < ts.size() ? ts.get(p) : null;
    }

    private boolean accept(String punct) {
        Token t = peek();
        if (t != null && t.is(punct)) {
            p++;
            return true;
        }
        return false;
    }

    private void expect(String punct) {
        if (!accept(punct)) {
            throw error("expected '" + punct + "' in #if expression");
        }
    }

    // live: whether the value is used; a division by zero in a branch that is not taken is no error
    private Value conditional(boolean live) {
        Value c = binary(0, live);
        if (accept("?")) {
            Value a = conditional(live && c.isTrue());
            expect(":");
            Value b = conditional(live && !c.isTrue());
            boolean u = a.unsigned || b.unsigned;
            return new Value(c.isTrue() ? a.v : b.v, u);
        }
        return c;
    }

    private static final List<List<String>> LEVELS = List.of(
            List.of("||"), List.of("&&"), List.of("|"), List.of("^"), List.of("&"), List.of("==", "!="),
            List.of("<", ">", "<=", ">="), List.of("<<", ">>"), List.of("+", "-"), List.of("*", "/", "%"));

    private Value binary(int level, boolean live) {
        if (level == LEVELS.size()) {
            return unary(live);
        }
        Value a = binary(level + 1, live);
        while (true) {
            Token t = peek();
            if (t == null || t.kind() != Token.Kind.PUNCT || !LEVELS.get(level).contains(t.text())) {
                return a;
            }
            p++;
            String op = t.text();
            if (op.equals("||")) {
                Value b = binary(level + 1, live && !a.isTrue());
                a = new Value(a.isTrue() || b.isTrue() ? 1 : 0, false);
                continue;
            }
            if (op.equals("&&")) {
                Value b = binary(level + 1, live && a.isTrue());
                a = new Value(a.isTrue() && b.isTrue() ? 1 : 0, false);
                continue;
            }
            Value b = binary(level + 1, live);
            a = apply(op, a, b, live);
        }
    }

    private Value apply(String op, Value a, Value b, boolean live) {
        boolean u = a.unsigned || b.unsigned;
        long x = a.v;
        long y = b.v;
        return switch (op) {
            case "|" -> new Value(x | y, u);
            case "^" -> new Value(x ^ y, u);
            case "&" -> new Value(x & y, u);
            case "==" -> new Value(x == y ? 1 : 0, false);
            case "!=" -> new Value(x != y ? 1 : 0, false);
            case "<" -> new Value((u ? Long.compareUnsigned(x, y) : Long.compare(x, y)) < 0 ? 1 : 0, false);
            case ">" -> new Value((u ? Long.compareUnsigned(x, y) : Long.compare(x, y)) > 0 ? 1 : 0, false);
            case "<=" -> new Value((u ? Long.compareUnsigned(x, y) : Long.compare(x, y)) <= 0 ? 1 : 0, false);
            case ">=" -> new Value((u ? Long.compareUnsigned(x, y) : Long.compare(x, y)) >= 0 ? 1 : 0, false);
            // the type of a shift is that of its left operand
            case "<<" -> new Value(y < 0 || y >= 64 ? 0 : x << y, a.unsigned);
            case ">>" -> new Value(y < 0 || y >= 64 ? (a.unsigned || x >= 0 ? 0 : -1) : a.unsigned ? x >>> y : x >> y, a.unsigned);
            case "+" -> new Value(x + y, u);
            case "-" -> new Value(x - y, u);
            case "*" -> new Value(x * y, u);
            default -> {
                if (y == 0) {
                    if (live) {
                        throw error("division by zero in #if");
                    }
                    yield new Value(0, u);
                }
                if (op.equals("/")) {
                    yield new Value(u ? Long.divideUnsigned(x, y) : x / y, u);
                }
                yield new Value(u ? Long.remainderUnsigned(x, y) : x % y, u);
            }
        };
    }

    private Value unary(boolean live) {
        Token t = peek();
        if (t == null) {
            throw error("#if expression ends early");
        }
        if (t.kind() == Token.Kind.PUNCT) {
            switch (t.text()) {
                case "+" -> {
                    p++;
                    return unary(live);
                }
                case "-" -> {
                    p++;
                    Value v = unary(live);
                    return new Value(-v.v, v.unsigned);
                }
                case "~" -> {
                    p++;
                    Value v = unary(live);
                    return new Value(~v.v, v.unsigned);
                }
                case "!" -> {
                    p++;
                    return new Value(unary(live).isTrue() ? 0 : 1, false);
                }
                case "(" -> {
                    p++;
                    Value v = conditional(live);
                    expect(")");
                    return v;
                }
                default -> throw error("token \"" + t.text() + "\" is not valid in #if expressions");
            }
        }
        p++;
        return switch (t.kind()) {
            case NUMBER -> number(t.text());
            case CHAR -> character(t.text());
            case IDENT -> new Value(0, false);   // a name that is no macro (6.10.1p4)
            default -> throw error("token \"" + t.text() + "\" is not valid in #if expressions");
        };
    }

    /** An integer constant with its suffixes; one that does not fit intmax_t is unsigned, as in gcc. */
    private Value number(String text) {
        String s = text;
        boolean u = false;
        while (!s.isEmpty() && "uUlL".indexOf(s.charAt(s.length() - 1)) >= 0) {
            u |= Character.toLowerCase(s.charAt(s.length() - 1)) == 'u';
            s = s.substring(0, s.length() - 1);
        }
        int radix = 10;
        if (s.startsWith("0x") || s.startsWith("0X")) {
            radix = 16;
            s = s.substring(2);
        } else if (s.length() > 1 && s.startsWith("0")) {
            radix = 8;
            s = s.substring(1);
        }
        try {
            long v = Long.parseUnsignedLong(s, radix);
            return new Value(v, u || v < 0);
        } catch (NumberFormatException e) {
            throw error("invalid integer constant \"" + text + "\" in #if");
        }
    }

    /** A character constant: its value as an int, plain char signed as on x86-64. */
    private Value character(String text) {
        if (!text.startsWith("'")) {
            throw error("wide character constants are not supported in #if: " + text);
        }
        String s = text.substring(1, text.length() - 1);
        long v;
        if (s.startsWith("\\")) {
            char c = s.charAt(1);
            v = switch (c) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'a' -> 7;
                case 'b' -> 8;
                case 'f' -> 12;
                case 'v' -> 11;
                case 'x' -> Long.parseLong(s.substring(2), 16);
                default -> c >= '0' && c <= '7' ? Long.parseLong(s.substring(1), 8) : c;
            };
        } else if (s.length() == 1) {
            v = s.charAt(0);
        } else {
            throw error("multi-character constants are not supported in #if: " + text);
        }
        return new Value((byte) v, false);
    }
}
