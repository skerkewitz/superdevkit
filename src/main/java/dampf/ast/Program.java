package dampf.ast;

import java.util.List;
import java.util.Map;

/**
 * The whole program: dampf always compiles all files together.
 *
 * @param initialValues initial values of globals; a global that is missing here starts as zero
 */
public record Program(List<Variable> globals, Map<Variable, Long> initialValues, List<Function> functions) {
    public Function function(String name) {
        return functions.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    public Variable global(String name) {
        return globals.stream().filter(v -> v.name().equals(name)).findFirst().orElse(null);
    }
}
