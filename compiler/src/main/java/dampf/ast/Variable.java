package dampf.ast;

import dampf.frontend.SourcePos;

/**
 * A variable with static storage. In dampf every variable has a fixed address: globals, and
 * also parameters and locals, because the call graph has no recursion (static frames).
 *
 * @param name  the C name (temporaries: a description)
 * @param label    the unique assembly label
 * @param readOnly declared const (for an array: its elements)
 */
public record Variable(String name, String label, Type type, Kind kind, SourcePos pos, boolean readOnly) {
    public Variable(String name, String label, Type type, Kind kind, SourcePos pos) {
        this(name, label, type, kind, pos, false);
    }

    /** A global or static local that is const: it goes to ROM. */
    public boolean inRom() {
        return readOnly && (kind == Kind.GLOBAL || kind == Kind.STATIC_LOCAL);
    }

    /** STATIC_LOCAL: a {@code static} variable inside a function, stored like a global. */
    public enum Kind { GLOBAL, STATIC_LOCAL, PARAM, LOCAL, TEMP }
}
