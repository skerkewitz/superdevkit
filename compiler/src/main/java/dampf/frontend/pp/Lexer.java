package dampf.frontend.pp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits a source file into preprocessing tokens (C99 5.1.1.2 phases 1 to 3, 6.4): a backslash at
 * the end of a line joins it with the next, a comment becomes white space, and every token keeps the
 * line and column where it starts in the file as written. A line ends in a {@link Token.Kind#NEWLINE}
 * token, unless the newline is inside a comment.
 *
 * <p>Not supported (dampf's preprocessor is a subset, ADR-6FD4GDN5): trigraphs and digraphs, and
 * universal character names in identifiers. A character that starts no token (such as {@code @},
 * or a quote that is never closed on its line) is a token of its own, as gcc keeps it.
 */
final class Lexer {
    /** The punctuators (C99 6.4.6) without the digraphs, longest first where one starts another. */
    static final List<String> PUNCTUATORS = List.of(
            "...", "<<=", ">>=",
            "->", "++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "*=", "/=", "%=", "+=", "-=",
            "&=", "^=", "|=", "##",
            "[", "]", "(", ")", "{", "}", ".", "&", "*", "+", "-", "~", "!", "/", "%", "<", ">", "^", "|",
            "?", ":", ";", "=", ",", "#");
    static final Set<String> PUNCTUATOR_SET = Set.copyOf(PUNCTUATORS);

    private final char[] s;
    private final int[] line;
    private final int[] col;
    private final String file;
    private int i;

    private Lexer(String text, String file) {
        this.file = file;
        // phase 2: a backslash right before a newline joins the two lines; every character that
        // stays keeps its place in the file as written
        int n = text.length();
        char[] chars = new char[n];
        int[] lines = new int[n + 1];
        int[] cols = new int[n + 1];
        int k = 0;
        int ln = 1;
        int cl = 1;
        for (int p = 0; p < n; p++) {
            char c = text.charAt(p);
            if (c == '\\' && p + 1 < n && (text.charAt(p + 1) == '\n'
                    || text.charAt(p + 1) == '\r' && p + 2 < n && text.charAt(p + 2) == '\n')) {
                p += text.charAt(p + 1) == '\r' ? 2 : 1;
                ln++;
                cl = 1;
                continue;
            }
            if (c == '\r' && p + 1 < n && text.charAt(p + 1) == '\n') {
                continue;   // CR LF is one newline
            }
            chars[k] = c;
            lines[k] = ln;
            cols[k] = cl;
            k++;
            if (c == '\n') {
                ln++;
                cl = 1;
            } else {
                cl++;
            }
        }
        lines[k] = ln;
        cols[k] = cl;
        this.s = java.util.Arrays.copyOf(chars, k);
        this.line = lines;
        this.col = cols;
    }

    /** The tokens of a file; the last line ends in a NEWLINE token even without a newline. */
    static List<Token> lex(String text, String file) {
        return new Lexer(text, file).run();
    }

    /** The tokens of one line of text (a macro body given on the command line, a pasted token). */
    static List<Token> lexLine(String text, String file, int line, int col) {
        List<Token> out = new ArrayList<>();
        for (Token t : new Lexer(text, file).run()) {
            if (t.kind() != Token.Kind.NEWLINE) {
                out.add(new Token(t.kind(), t.text(), t.space(), file, line, col, null, false));
            }
        }
        return out;
    }

    private char at(int p) {
        return p < s.length ? s[p] : '\0';
    }

    private List<Token> run() {
        List<Token> out = new ArrayList<>();
        boolean space = false;
        i = 0;
        while (i < s.length) {
            char c = s[i];
            if (c == '\n') {
                out.add(Token.of(Token.Kind.NEWLINE, "\n", space, file, line[i], col[i]));
                space = false;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\f' || c == '\u000b' || c == '\r') {
                space = true;
                i++;
                continue;
            }
            if (c == '/' && at(i + 1) == '*') {
                int start = i;
                int end = -1;
                for (int p = i + 2; p + 1 < s.length; p++) {
                    if (s[p] == '*' && s[p + 1] == '/') {
                        end = p + 2;
                        break;
                    }
                }
                if (end < 0) {
                    throw new PpException("unterminated comment", file, line[start], col[start]);
                }
                i = end;
                space = true;
                continue;
            }
            if (c == '/' && at(i + 1) == '/') {
                while (i < s.length && s[i] != '\n') {
                    i++;
                }
                space = true;
                continue;
            }
            int start = i;
            Token.Kind kind = scan();
            out.add(Token.of(kind, new String(s, start, i - start), space, file, line[start], col[start]));
            space = false;
        }
        if (out.isEmpty() || out.getLast().kind() != Token.Kind.NEWLINE) {
            out.add(Token.of(Token.Kind.NEWLINE, "\n", false, file, line[s.length], col[s.length]));
        }
        return out;
    }

    private static boolean identStart(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c == '$';
    }

    private static boolean identPart(char c) {
        return identStart(c) || c >= '0' && c <= '9';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Scans the token at i, leaves i after it and returns its kind. */
    private Token.Kind scan() {
        char c = s[i];
        // a prefixed string or character constant: L"x", u8"x", u'x', U"x"
        if (c == 'L' || c == 'U' || c == 'u') {
            int p = i + 1;
            if (c == 'u' && at(p) == '8') {
                p++;
            }
            if (at(p) == '"' || at(p) == '\'' && !(c == 'u' && p == i + 2)) {
                int save = i;
                i = p;
                Token.Kind k = quoted();
                if (k != Token.Kind.OTHER) {
                    return k;
                }
                i = save;
            }
        }
        if (identStart(c)) {
            while (i < s.length && identPart(s[i])) {
                i++;
            }
            return Token.Kind.IDENT;
        }
        if (digit(c) || c == '.' && digit(at(i + 1))) {
            // pp-number (6.4.8): digits, letters, _, ., and e+ e- E+ E- p+ p- P+ P-
            i++;
            while (i < s.length) {
                char d = s[i];
                if ((d == '+' || d == '-') && "eEpP".indexOf(s[i - 1]) >= 0) {
                    i++;
                } else if (identPart(d) || d == '.') {
                    i++;
                } else {
                    break;
                }
            }
            return Token.Kind.NUMBER;
        }
        if (c == '"' || c == '\'') {
            return quoted();
        }
        for (String p : PUNCTUATORS) {
            if (s.length - i >= p.length() && new String(s, i, p.length()).equals(p)) {
                i += p.length();
                return Token.Kind.PUNCT;
            }
        }
        i++;
        return Token.Kind.OTHER;
    }

    /** A string or character constant from the quote at i; a quote never closed on its line is OTHER. */
    private Token.Kind quoted() {
        char q = s[i];
        int p = i + 1;
        while (p < s.length && s[p] != q && s[p] != '\n') {
            p += s[p] == '\\' && p + 1 < s.length && s[p + 1] != '\n' ? 2 : 1;
        }
        if (p >= s.length || s[p] != q) {
            i++;
            return Token.Kind.OTHER;
        }
        i = p + 1;
        return q == '"' ? Token.Kind.STRING : Token.Kind.CHAR;
    }
}
