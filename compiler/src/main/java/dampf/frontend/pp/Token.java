package dampf.frontend.pp;

import java.util.Set;

/**
 * A preprocessing token (C99 6.4): its kind and spelling, whether white space came before it, where
 * it starts in its file (line and column from 1, counted in the file as written, before backslash
 * newlines are removed) and, once it comes out of a macro, the names of the macros it may no longer
 * expand ({@code hide}, Prosser's hide set). {@code expanded}: the token came out of a macro; it
 * then stands at the line of the invocation it came from.
 */
record Token(Kind kind, String text, boolean space, String file, int line, int col, Set<String> hide, boolean expanded) {
    enum Kind {
        IDENT, NUMBER, CHAR, STRING, PUNCT, OTHER,
        /** The end of a logical line (not inside a comment). */
        NEWLINE,
        /** An empty argument where ## needs an operand (C99 6.10.3.3), never written out. */
        PLACEMARKER
    }

    Token {
        hide = hide == null ? Set.of() : hide;
    }

    static Token of(Kind kind, String text, boolean space, String file, int line, int col) {
        return new Token(kind, text, space, file, line, col, Set.of(), false);
    }

    boolean is(String punct) {
        return kind == Kind.PUNCT && text.equals(punct);
    }

    boolean isIdent(String name) {
        return kind == Kind.IDENT && text.equals(name);
    }

    Token withSpace(boolean s) {
        return new Token(kind, text, s, file, line, col, hide, expanded);
    }

    Token withHide(Set<String> h) {
        return new Token(kind, text, space, file, line, col, h, expanded);
    }

    /** The token as it comes out of the invocation at {@code at}: at its line, marked as expanded. */
    Token at(Token at) {
        return new Token(kind, text, space, at.file, at.line, at.col, hide, true);
    }

    @Override
    public String toString() {
        return text;
    }
}
