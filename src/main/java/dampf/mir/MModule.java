package dampf.mir;

import dampf.ast.Init;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole program in MIR: global objects (with their initial contents) and functions.
 * Global objects keep the AST's byte layout, so their initial contents and dampf's struct
 * offsets mean the same here.
 */
public final class MModule {
    /** A global object: {@code size} bytes; {@code init} null for all zeros. */
    public record Global(String symbol, int size, Init init, boolean readOnly) {}

    private final List<Global> globals = new ArrayList<>();
    private final List<MFunction> functions = new ArrayList<>();

    public List<Global> globals() {
        return globals;
    }

    public List<MFunction> functions() {
        return functions;
    }

    public MFunction function(String name) {
        return functions.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }
}
