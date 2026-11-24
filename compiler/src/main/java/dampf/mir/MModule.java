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

    /**
     * The functions that are entries of a function table (a datum names them): calls through
     * the table reach them too, so passes that change a function's parameters for its callers
     * leave them alone.
     */
    public java.util.Set<String> tableFunctions() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Global g : globals) {
            for (Datum d : g.init()) {
                if (d.symbol() != null && function(d.symbol()) != null) {
                    out.add(d.symbol());
                }
            }
        }
        return out;
    }

    public MFunction function(String name) {
        return functions.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }
}
