package dampf.ast;

import java.util.List;
import java.util.Map;

/**
 * The whole program: dampf always compiles all files together.
 *
 * @param initialValues initial contents of globals; a global that is missing here starts as zero
 */
public record Program(List<Variable> globals, Map<Variable, Init> initialValues, List<Function> functions) {
    /** The initial value of a scalar global, or null if it has none (it starts as zero). */
    public Long initialScalar(Variable v) {
        Init i = initialValues.get(v);
        return i == null || i.items().isEmpty() ? null : i.items().getFirst().value();
    }

    public Function function(String name) {
        return functions.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    public Variable global(String name) {
        return globals.stream().filter(v -> v.name().equals(name)).findFirst().orElse(null);
    }
}
