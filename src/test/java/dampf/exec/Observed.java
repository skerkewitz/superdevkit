package dampf.exec;

import dampf.ast.Type;
import dampf.ast.Variable;
import java.util.ArrayList;
import java.util.List;

/**
 * One scalar of a global that the execution tests compare between PC and SNES: a variable, an
 * array element or a struct field, with its C expression ({@code path}) and its place in memory.
 * Pointers are left out (their values are addresses, which differ between PC and SNES), and so
 * are constant objects in ROM.
 */
record Observed(String path, Type.Int type, String label, int offset) {
    static List<Observed> of(List<Variable> globals) {
        List<Observed> out = new ArrayList<>();
        for (Variable v : globals) {
            if (v.kind() == Variable.Kind.GLOBAL && !v.readOnly()) {
                flatten(v.name(), v.type(), v.label(), 0, out);
            }
        }
        return out;
    }

    private static void flatten(String path, Type t, String label, int offset, List<Observed> out) {
        switch (t) {
            case Type.Int i -> out.add(new Observed(path, i, label, offset));
            case Type.Array a -> {
                for (int k = 0; k < a.length(); k++) {
                    flatten(path + "[" + k + "]", a.element(), label, offset + k * a.element().size(), out);
                }
            }
            case Type.Struct s -> {
                for (Type.Field f : s.def().fields()) {
                    flatten(path + "." + f.name(), f.type(), label, offset + f.offset(), out);
                }
            }
            default -> { } // pointers
        }
    }
}
