package dampf.exec;

import dampf.ast.Init;
import dampf.ast.Program;
import dampf.ast.Type;
import dampf.ast.Variable;
import java.util.ArrayList;
import java.util.List;

/**
 * One scalar of a global that the execution tests compare between PC and SNES: a variable, an
 * array element or a struct field, with its C expression ({@code path}) and its place in memory.
 * Pointers are left out (their values are addresses, which differ between PC and SNES), and so
 * are constant objects in ROM. {@code initial} is its value before the program runs: what a
 * runner reports for a global the ROM leaves out because no reachable code touches it
 * (dampf.mir.Reachable), so the PC's value must still be it.
 */
record Observed(String path, Type.Int type, String label, int offset, long initial) {
    static List<Observed> of(Program program) {
        List<Observed> out = new ArrayList<>();
        for (Variable v : program.globals()) {
            if (v.kind() == Variable.Kind.GLOBAL && !v.readOnly()) {
                flatten(v.name(), v.type(), v.label(), 0, program.initialValues().get(v), out);
            }
        }
        return out;
    }

    private static void flatten(String path, Type t, String label, int offset, Init init, List<Observed> out) {
        switch (t) {
            case Type.Int i -> {
                long value = 0;
                if (init != null) {
                    for (Init.Item item : init.items()) {
                        if (item.address() == null && item.offset() == offset && item.size() == i.size()) {
                            value = i.wrap(item.value());
                        }
                    }
                }
                out.add(new Observed(path, i, label, offset, value));
            }
            case Type.Array a -> {
                for (int k = 0; k < a.length(); k++) {
                    flatten(path + "[" + k + "]", a.element(), label, offset + k * a.element().size(), init, out);
                }
            }
            case Type.Struct s -> {
                for (Type.Field f : s.def().fields()) {
                    flatten(path + "." + f.name(), f.type(), label, offset + f.offset(), init, out);
                }
            }
            default -> { } // pointers
        }
    }
}
