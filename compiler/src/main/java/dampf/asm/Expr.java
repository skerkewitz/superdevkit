package dampf.asm;

import java.util.function.Function;

/**
 * An expression of ca65 source as dampf's assembler reads it: numbers ({@code $1F}, {@code %101},
 * {@code 42}, {@code 'a'}), symbols (with {@code ::} scopes, cheap local {@code @x}, unnamed
 * {@code :+} and {@code :-}), {@code + - * / & | ^ << >>}, parentheses, the unary {@code - < > ^ ~}
 * and the functions {@code .loword}, {@code .hiword}, {@code .bankbyte}, {@code .lobyte},
 * {@code .hibyte}. Its value is computed when all its symbols have one.
 */
public sealed interface Expr {
    record Num(long value) implements Expr {}

    /** A symbol as written; {@code scope} is where it was used (for lookup), {@code cheap} its cheap local scope. */
    record Sym(String name) implements Expr {}

    record Unary(String op, Expr e) implements Expr {}

    record Binary(String op, Expr a, Expr b) implements Expr {}

    /** The value, with {@code symbols} giving each symbol's value (null if unknown); null if not known. */
    default Long eval(Function<String, Long> symbols) {
        return switch (this) {
            case Num n -> n.value();
            case Sym s -> symbols.apply(s.name());
            case Unary u -> {
                Long v = u.e().eval(symbols);
                yield v == null ? null : switch (u.op()) {
                    case "-" -> -v;
                    case "~" -> ~v;
                    case "<", ".lobyte" -> v & 0xFF;
                    case ">", ".hibyte" -> (v >> 8) & 0xFF;
                    case "^", ".bankbyte" -> (v >> 16) & 0xFF;
                    case ".loword" -> v & 0xFFFF;
                    case ".hiword" -> (v >> 16) & 0xFFFF;
                    default -> throw new IllegalStateException(u.op());
                };
            }
            case Binary b -> {
                Long x = b.a().eval(symbols);
                Long y = b.b().eval(symbols);
                yield x == null || y == null ? null : switch (b.op()) {
                    case "+" -> x + y;
                    case "-" -> x - y;
                    case "*" -> x * y;
                    case "/" -> x / y;
                    case "&" -> x & y;
                    case "|" -> x | y;
                    case "^" -> x ^ y;
                    case "<<" -> x << y;
                    case ">>" -> x >> y;
                    default -> throw new IllegalStateException(b.op());
                };
            }
        };
    }

    /** Applies f to every symbol name (to give it its full, scoped name). */
    default Expr mapSymbols(Function<String, Expr> f) {
        return switch (this) {
            case Num n -> n;
            case Sym s -> f.apply(s.name());
            case Unary u -> new Unary(u.op(), u.e().mapSymbols(f));
            case Binary b -> new Binary(b.op(), b.a().mapSymbols(f), b.b().mapSymbols(f));
        };
    }

    /** Whether the expression ends in a function that cuts it to a byte or word (no range check needed). */
    default boolean truncates() {
        return this instanceof Unary u && switch (u.op()) {
            case "<", ">", "^", ".lobyte", ".hibyte", ".bankbyte", ".loword", ".hiword" -> true;
            default -> false;
        };
    }

    static Expr parse(String text) {
        Parser p = new Parser(text);
        Expr e = p.or();
        p.skip();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("cannot read the expression '" + text + "' at " + p.pos);
        }
        return e;
    }

    final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private boolean eat(String t) {
            skip();
            if (s.startsWith(t, pos)) {
                pos += t.length();
                return true;
            }
            return false;
        }

        Expr or() {
            Expr e = xor();
            while (true) {
                skip();
                if (s.startsWith("||", pos)) {
                    throw new IllegalArgumentException("|| is not supported: " + s);
                }
                if (eat("|")) {
                    e = new Binary("|", e, xor());
                } else {
                    return e;
                }
            }
        }

        Expr xor() {
            Expr e = and();
            while (eat("^")) {
                e = new Binary("^", e, and());
            }
            return e;
        }

        Expr and() {
            Expr e = shift();
            while (true) {
                skip();
                if (s.startsWith("&&", pos)) {
                    throw new IllegalArgumentException("&& is not supported: " + s);
                }
                if (eat("&")) {
                    e = new Binary("&", e, shift());
                } else {
                    return e;
                }
            }
        }

        Expr shift() {
            Expr e = sum();
            while (true) {
                if (eat("<<")) {
                    e = new Binary("<<", e, sum());
                } else if (eat(">>")) {
                    e = new Binary(">>", e, sum());
                } else {
                    return e;
                }
            }
        }

        Expr sum() {
            Expr e = product();
            while (true) {
                skip();
                if (pos < s.length() && s.charAt(pos) == ':' ) {
                    return e;
                }
                if (eat("+")) {
                    e = new Binary("+", e, product());
                } else if (eat("-")) {
                    e = new Binary("-", e, product());
                } else {
                    return e;
                }
            }
        }

        Expr product() {
            Expr e = unary();
            while (true) {
                if (eat("*")) {
                    e = new Binary("*", e, unary());
                } else if (eat("/")) {
                    e = new Binary("/", e, unary());
                } else {
                    return e;
                }
            }
        }

        Expr unary() {
            skip();
            for (String op : new String[] {"-", "~", "<", ">", "^"}) {
                if (s.startsWith(op, pos) && !s.startsWith("<<", pos) && !s.startsWith(">>", pos)) {
                    pos += op.length();
                    return new Unary(op, unary());
                }
            }
            if (s.startsWith("+", pos)) {
                pos++;
                return unary();
            }
            return primary();
        }

        Expr primary() {
            skip();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("an expression ends early: " + s);
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Expr e = or();
                if (!eat(")")) {
                    throw new IllegalArgumentException("missing ')': " + s);
                }
                return e;
            }
            if (c == '.') {
                int start = pos++;
                while (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                    pos++;
                }
                String f = s.substring(start, pos).toLowerCase();
                if (!eat("(")) {
                    throw new IllegalArgumentException("expected '(' after " + f + ": " + s);
                }
                Expr e = or();
                if (!eat(")")) {
                    throw new IllegalArgumentException("missing ')': " + s);
                }
                return switch (f) {
                    case ".loword", ".hiword", ".bankbyte", ".lobyte", ".hibyte" -> new Unary(f, e);
                    default -> throw new IllegalArgumentException("the function " + f + " is not supported: " + s);
                };
            }
            if (c == '$') {
                int start = ++pos;
                while (pos < s.length() && Character.digit(s.charAt(pos), 16) >= 0) {
                    pos++;
                }
                return new Num(Long.parseLong(s.substring(start, pos), 16));
            }
            if (c == '%') {
                int start = ++pos;
                while (pos < s.length() && (s.charAt(pos) == '0' || s.charAt(pos) == '1')) {
                    pos++;
                }
                return new Num(Long.parseLong(s.substring(start, pos), 2));
            }
            if (Character.isDigit(c)) {
                int start = pos;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
                return new Num(Long.parseLong(s.substring(start, pos)));
            }
            if (c == '\'' && pos + 2 < s.length() && s.charAt(pos + 2) == '\'') {
                pos += 3;
                return new Num(s.charAt(pos - 2));
            }
            if (c == ':') {
                int start = pos++;
                while (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (s.startsWith(":", pos)) {   // ::global
                    pos++;
                    return new Sym("::" + name());
                }
                return new Sym(s.substring(start, pos));
            }
            if (Character.isLetter(c) || c == '_' || c == '@') {
                return new Sym(name());
            }
            throw new IllegalArgumentException("cannot read '" + s.substring(pos) + "' in " + s);
        }

        private String name() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '@') {
                    pos++;
                } else if (c == ':' && s.startsWith("::", pos) && pos + 2 < s.length()
                        && (Character.isLetter(s.charAt(pos + 2)) || s.charAt(pos + 2) == '_')) {
                    pos += 2;   // scope::name
                } else {
                    break;
                }
            }
            return s.substring(start, pos);
        }
    }
}
