package dampf.frontend.pp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Macro expansion (C99 6.10.3) after Prosser's algorithm: every token carries the set of macros it
 * came out of (its hide set) and does not expand as one of them again. An argument is expanded
 * completely before it replaces its parameter, except as an operand of {@code #} or {@code ##}; the
 * result is scanned again together with the rest of the text, so that it can take the arguments of
 * a function-like macro from there. A token that comes out of a macro stands at the line of the
 * invocation.
 */
final class Expander {
    /** Names that dampf's preprocessor rejects instead of passing them on (ADR-6FD4GDN5). */
    static final Set<String> UNSUPPORTED = Set.of("__VA_OPT__", "__COUNTER__", "__DATE__", "__TIME__", "__TIMESTAMP__",
            "__has_include", "__has_include_next", "_Pragma", "__INCLUDE_LEVEL__", "__BASE_FILE__");

    private final Map<String, Macro> macros;

    Expander(Map<String, Macro> macros) {
        this.macros = macros;
    }

    /** The tokens with every macro in them expanded (the text of a line or of several, or an argument). */
    List<Token> expand(List<Token> in) {
        Deque<Token> ts = new ArrayDeque<>(in);
        List<Token> out = new ArrayList<>(in.size());
        while (!ts.isEmpty()) {
            Token t = ts.pollFirst();
            if (t.kind() == Token.Kind.PLACEMARKER) {
                // the white space of an empty argument at the end of a replacement goes to the
                // token after it, as in gcc and clang; at the end of the text (of an argument
                // being expanded) it is dropped, as they do
                Token n = ts.peekFirst();
                if (n != null && n.kind() != Token.Kind.NEWLINE) {
                    ts.pollFirst();
                    ts.addFirst(n.withSpace(true));
                }
                continue;
            }
            if (t.kind() != Token.Kind.IDENT) {
                out.add(t);
                continue;
            }
            String name = t.text();
            Macro m = macros.get(name);
            if (m == null || t.hide().contains(name)) {
                out.add(builtin(t));
                continue;
            }
            if (!m.functionLike()) {
                pushFront(ts, subst(m, null, with(t.hide(), name), t));
                continue;
            }
            Token open = next(ts);
            if (open == null || !open.is("(")) {
                out.add(t);   // a function-like macro's name without arguments is just a name
                continue;
            }
            List<List<Token>> args = new ArrayList<>();
            Token close = arguments(ts, m, t, args);
            Set<String> hs = new HashSet<>(t.hide());
            hs.retainAll(close.hide());
            hs.add(name);
            pushFront(ts, subst(m, args, Set.copyOf(hs), t));
        }
        return out;
    }

    /** __LINE__ and __FILE__, the names the subset rejects, and every other name as it is. */
    private Token builtin(Token t) {
        if (macros.containsKey(t.text())) {
            return t;
        }
        return switch (t.text()) {
            case "__LINE__" -> new Token(Token.Kind.NUMBER, Integer.toString(t.line()), t.space(), t.file(), t.line(), t.col(),
                    null, true);
            case "__FILE__" -> new Token(Token.Kind.STRING, quote(t.file()), t.space(), t.file(), t.line(), t.col(), null, true);
            default -> {
                if (UNSUPPORTED.contains(t.text())) {
                    throw new PpException(t.text() + " is not supported by dampf's preprocessor", t);
                }
                yield t;
            }
        };
    }

    /** The next token that is not the end of a line (or white space left by an empty argument), or null. */
    private static Token next(Deque<Token> ts) {
        for (Token t : ts) {
            if (t.kind() != Token.Kind.NEWLINE && t.kind() != Token.Kind.PLACEMARKER) {
                return t;
            }
        }
        return null;
    }

    private static void pushFront(Deque<Token> ts, List<Token> list) {
        for (int k = list.size() - 1; k >= 0; k--) {
            ts.addFirst(list.get(k));
        }
    }

    private static Set<String> with(Set<String> hide, String name) {
        Set<String> s = new HashSet<>(hide);
        s.add(name);
        return Set.copyOf(s);
    }

    /**
     * Takes the arguments of an invocation out of ts, from its "(" to the matching ")", which it
     * returns. An end of line inside them is white space. The arguments of a variadic macro after
     * its named parameters are one, commas included.
     */
    private static Token arguments(Deque<Token> ts, Macro m, Token name, List<List<Token>> args) {
        while (ts.peekFirst().kind() == Token.Kind.NEWLINE || ts.peekFirst().kind() == Token.Kind.PLACEMARKER) {
            ts.pollFirst();
        }
        ts.pollFirst();   // (
        int depth = 0;
        List<Token> arg = new ArrayList<>();
        boolean space = false;
        int named = m.params().size() - (m.variadic() ? 1 : 0);
        while (true) {
            Token t = ts.pollFirst();
            if (t == null) {
                throw new PpException("unterminated argument list invoking macro \"" + m.name() + "\"", name);
            }
            if (t.kind() == Token.Kind.NEWLINE || t.kind() == Token.Kind.PLACEMARKER) {
                space = true;
                continue;
            }
            if (space) {
                t = t.withSpace(true);
                space = false;
            }
            if (depth == 0 && t.is(")")) {
                args.add(arg);
                check(m, args, name);
                return t;
            }
            if (depth == 0 && t.is(",") && !(m.variadic() && args.size() >= named)) {
                args.add(arg);
                arg = new ArrayList<>();
                continue;
            }
            if (t.is("(")) {
                depth++;
            } else if (t.is(")")) {
                depth--;
            }
            arg.add(arg.isEmpty() ? t.withSpace(false) : t);
        }
    }

    private static void check(Macro m, List<List<Token>> args, Token at) {
        int n = m.params().size();
        if (n == 0 && args.size() == 1 && args.getFirst().isEmpty()) {
            args.clear();
            return;
        }
        if (m.variadic() && args.size() == n - 1) {
            args.add(new ArrayList<>());   // no variadic arguments (gcc allows it, C99 asks for one)
        }
        if (args.size() != n) {
            throw new PpException("macro \"" + m.name() + "\" passed " + args.size() + " arguments, but takes "
                    + (args.size() > n ? "just " : "") + n, at);
        }
    }

    /**
     * The replacement list of m with its parameters replaced (6.10.3.1 to 6.10.3.3), every token
     * with hs added to its hide set and standing at the invocation.
     */
    private List<Token> subst(Macro m, List<List<Token>> args, Set<String> hs, Token inv) {
        List<Token> body = m.body();
        List<Token> out = new ArrayList<>();
        List<List<Token>> expanded = args == null ? null : new ArrayList<>(java.util.Collections.nCopies(args.size(), null));
        for (int k = 0; k < body.size(); k++) {
            Token t = body.get(k);
            if (m.functionLike() && t.is("#") && k + 1 < body.size() && m.param(body.get(k + 1)) >= 0) {
                out.add(stringify(args.get(m.param(body.get(k + 1))), t));
                k++;
                continue;
            }
            if (t.is("##") && k + 1 < body.size()) {
                // the operand after ##: an argument unexpanded, a stringified one, or the token itself
                Token b = body.get(k + 1);
                List<Token> rhs;
                if (m.functionLike() && b.is("#") && k + 2 < body.size() && m.param(body.get(k + 2)) >= 0) {
                    rhs = List.of(stringify(args.get(m.param(body.get(k + 2))), b));
                    k += 2;
                } else if (m.param(b) >= 0) {
                    rhs = args.get(m.param(b));
                    k++;
                } else {
                    rhs = List.of(b);
                    k++;
                }
                Token lhs = out.isEmpty() ? placemarker(t) : out.removeLast();
                Token first = rhs.isEmpty() ? placemarker(t) : rhs.getFirst();
                out.add(paste(lhs, first));
                for (int r = 1; r < rhs.size(); r++) {
                    out.add(rhs.get(r));
                }
                continue;
            }
            int p = m.param(t);
            if (p >= 0) {
                boolean pasted = k + 1 < body.size() && body.get(k + 1).is("##");
                List<Token> a;
                if (pasted) {
                    a = args.get(p).isEmpty() ? List.of(placemarker(t)) : args.get(p);
                } else {
                    if (expanded.get(p) == null) {
                        expanded.set(p, expand(args.get(p)));
                    }
                    a = expanded.get(p);
                }
                for (int r = 0; r < a.size(); r++) {
                    out.add(r == 0 ? a.get(r).withSpace(t.space()) : a.get(r));
                }
                if (a.isEmpty() && t.space()) {
                    // an empty argument leaves its white space ("a x b" with x empty is "a  b")
                    out.add(placemarker(t).withSpace(true));
                }
                continue;
            }
            out.add(t);
        }
        List<Token> result = new ArrayList<>(out.size());
        boolean space = inv.space();
        boolean first = true;
        for (Token t : out) {
            if (t.kind() == Token.Kind.PLACEMARKER) {
                space |= t.space();
                continue;
            }
            Set<String> h = new HashSet<>(t.hide());
            h.addAll(hs);
            Token r = t.withHide(Set.copyOf(h)).at(inv);
            if (first) {
                r = r.withSpace(inv.space());
                first = false;
            } else if (space) {
                r = r.withSpace(true);
            }
            space = false;
            result.add(r);
        }
        if (space) {
            // white space left at the end goes to the token after the replacement
            result.add(placemarker(inv).withSpace(true));
        }
        return result;
    }

    private static Token placemarker(Token at) {
        return new Token(Token.Kind.PLACEMARKER, "", false, at.file(), at.line(), at.col(), null, false);
    }

    /** a ## b (6.10.3.3): the two spellings as one token; anything else is an error. */
    private static Token paste(Token a, Token b) {
        if (a.kind() == Token.Kind.PLACEMARKER) {
            return b.withSpace(a.space() || b.space());
        }
        if (b.kind() == Token.Kind.PLACEMARKER) {
            return a;
        }
        String text = a.text() + b.text();
        List<Token> ts = Lexer.lexLine(text, a.file(), a.line(), a.col());
        if (ts.size() != 1) {
            throw new PpException("pasting \"" + a.text() + "\" and \"" + b.text() + "\" does not give a valid preprocessing token", a);
        }
        Token t = ts.getFirst();
        return new Token(t.kind(), t.text(), a.space(), a.file(), a.line(), a.col(), null, a.expanded());
    }

    /** #x (6.10.3.2): the argument's spelling as a string, white space between tokens as one space. */
    private static Token stringify(List<Token> arg, Token at) {
        StringBuilder s = new StringBuilder("\"");
        for (Iterator<Token> it = arg.iterator(); it.hasNext(); ) {
            Token t = it.next();
            if (t.space() && s.length() > 1) {
                s.append(' ');
            }
            if (t.kind() == Token.Kind.STRING || t.kind() == Token.Kind.CHAR) {
                s.append(t.text().replace("\\", "\\\\").replace("\"", "\\\""));
            } else {
                s.append(t.text());
            }
        }
        s.append('"');
        return new Token(Token.Kind.STRING, s.toString(), at.space(), at.file(), at.line(), at.col(), null, false);
    }

    /** A file name as a string literal, as the line markers and __FILE__ write it. */
    static String quote(String file) {
        return "\"" + file.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
