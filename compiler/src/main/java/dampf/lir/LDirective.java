package dampf.lir;

import java.util.List;

/**
 * Everything that is neither an instruction nor a code label: assembler directives, symbol
 * definitions, data, comments and blank lines. {@code kind} says what it is, {@code args} are its
 * arguments as ca65 writes them (split at top-level commas), {@code label} a label on the same
 * line (a data label before {@code .res}, the name of an equate), {@code comment} the text after
 * {@code ;}. {@code text} is the line as written, which the printer reproduces.
 */
public record LDirective(Kind kind, String label, List<String> args, String comment, String text) implements LItem {
    public enum Kind {
        /** An empty line. */
        BLANK,
        /** A comment that starts the line ({@code ; ...}). */
        COMMENT,
        /** A comment indented to the comment column. */
        NOTE,
        /** {@code name = expression}. */
        EQUATE,
        SEGMENT, PROC, ENDPROC, DBG,
        P816, SMART, A8, A16, I8, I16,
        IMPORT, IMPORTZP, EXPORT, EXPORTZP, GLOBAL,
        BYTE, WORD, RES;

        /** The directive for a ca65 name ("segment", "a16"), or null. */
        static Kind of(String name) {
            for (Kind k : values()) {
                if (k.ordinal() > EQUATE.ordinal() && k.name().equalsIgnoreCase(name)) {
                    return k;
                }
            }
            return null;
        }
    }

    public static final LDirective BLANK_LINE = new LDirective(Kind.BLANK, null, List.of(), null, "");

    /** A comment indented to the comment column, as the code generators write it next to code. */
    public static LDirective note(String text) {
        return new LDirective(Kind.NOTE, null, List.of(), text, " ".repeat(LPrinter.COMMENT_COLUMN) + "; " + text);
    }

    /** Whether it only explains (blank lines and comments) and means nothing to the assembler. */
    public boolean trivia() {
        return kind == Kind.BLANK || kind == Kind.COMMENT || kind == Kind.NOTE;
    }

    /** For {@code .dbg}: what it describes ("line", "file", "func", "sym"). */
    public String dbgKind() {
        return kind == Kind.DBG && !args.isEmpty() ? args.getFirst() : null;
    }
}
