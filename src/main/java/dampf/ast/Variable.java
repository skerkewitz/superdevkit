package dampf.ast;

import dampf.frontend.SourcePos;

/**
 * A variable with static storage. In dampf every variable has a fixed address: globals, and
 * also parameters and locals, because the call graph has no recursion (static frames).
 *
 * @param name  the C name (temporaries: a description)
 * @param label the unique assembly label
 */
public record Variable(String name, String label, Type type, Kind kind, SourcePos pos) {
    public enum Kind { GLOBAL, PARAM, LOCAL, TEMP }
}
