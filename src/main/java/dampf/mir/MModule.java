package dampf.mir;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole program in MIR: global objects (with their initial contents) and functions.
 * Global objects keep the AST's byte layout, so their initial contents and dampf's struct
 * offsets mean the same here.
 */
public final class MModule {
    /**
     * Part of a global's initial contents: {@code size} bytes at {@code offset}, either the number
     * {@code value} or (if {@code symbol} is not null) the address of {@code symbol} plus
     * {@code value}. Bytes no datum covers are zero.
     */
    public record Datum(int offset, int size, long value, String symbol) {}

    /** A global object: {@code size} bytes; {@code init} empty for all zeros. */
    public record Global(String symbol, int size, List<Datum> init, boolean readOnly) {}

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
