package dampf.exec;

import dampf.ast.Program;
import dampf.mir.Interpreter;
import dampf.mir.Lower;
import dampf.mir.MModule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs a program on the MIR level: lowered from the AST, executed by the MIR interpreter. */
final class MirRunner {
    private MirRunner() {}

    static Map<String, Long> run(Program program, String entry, List<Observed> globals) {
        MModule m = Lower.lower(program);
        Interpreter in = new Interpreter(m, 200_000_000L);
        in.call(program.function(entry).label(), List.of());
        Map<String, Long> values = new LinkedHashMap<>();
        for (Observed o : globals) {
            values.put(o.path(), o.type().wrap(in.load(in.addressOf(o.label()) + o.offset(), o.type().size())));
        }
        return values;
    }
}
