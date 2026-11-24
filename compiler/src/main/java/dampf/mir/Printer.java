package dampf.mir;

import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * MIR as text. The format, one instruction per line:
 *
 * <pre>
 * global @_result : 2
 * global @_table : 4 const = [0: i16 1234, 2: i16 -1]
 * global @_names : 4 const = [0: ptr @__str3+2]
 * (ptradd %p, i16 %k: an offset within the object, see Instr.PtrAdd)
 * func @_add3(i16 %a, i16 %b) -&gt; i16 {
 *     slot $buf : 8
 * b0_entry:
 *     %0 = zext i16 %a to i32                ; calls.c:11
 *     %1 = add i32 %0, 5
 *     %2 = load i16 @_result
 *     store i16 @_result, %2
 *     %3 = phi i16 [b1, %2], [b2, 0]
 *     br %4, b1, b2
 *     ret i16 %2
 * }
 * </pre>
 */
public final class Printer {
    private Printer() {}

    public static String print(MModule m) {
        StringBuilder out = new StringBuilder();
        for (MModule.Global g : m.globals()) {
            out.append("global @").append(g.symbol()).append(" : ").append(g.size()).append(g.readOnly() ? " const" : "");
            if (!g.init().isEmpty()) {
                out.append(" = [");
                for (int k = 0; k < g.init().size(); k++) {
                    MModule.Datum d = g.init().get(k);
                    out.append(k == 0 ? "" : ", ").append(d.offset()).append(": ");
                    if (d.symbol() != null) {
                        out.append("ptr @").append(d.symbol()).append(d.value() == 0 ? "" : (d.value() > 0 ? "+" : "") + d.value());
                    } else {
                        MType t = d.size() == 1 ? MType.I8 : d.size() == 2 ? MType.I16 : MType.I32;
                        out.append(t).append(' ').append(t.signed(d.value()));
                    }
                }
                out.append(']');
            }
            out.append('\n');
        }
        for (MFunction f : m.functions()) {
            out.append('\n').append(print(f));
        }
        return out.toString();
    }

    public static String print(MFunction f) {
        f.number();
        StringBuilder out = new StringBuilder();
        out.append("func @").append(f.name()).append('(')
                .append(f.params().stream().map(p -> p.type() + " " + p).collect(Collectors.joining(", ")))
                .append(") -> ").append(f.result()).append(" {\n");
        for (Value.Slot s : f.slots()) {
            out.append("    slot $").append(s.name()).append(" : ").append(s.size()).append('\n');
        }
        for (Block b : f.blocks()) {
            out.append(b.name()).append(':');
            if (!b.preds().isEmpty()) {
                // in block order, so the text does not depend on the order the edges were made in
                out.append("                    ; from ").append(b.preds().stream().distinct()
                        .sorted(java.util.Comparator.comparingInt(f.blocks()::indexOf)).map(Block::name).collect(Collectors.joining(", ")));
            }
            out.append('\n');
            for (Instr i : b.instrs()) {
                String text = instr(i);
                out.append("    ").append(text);
                if (i.pos() != null) {
                    out.append(" ".repeat(Math.max(1, 44 - text.length()))).append("; ")
                            .append(Path.of(i.pos().file()).getFileName()).append(':').append(i.pos().line());
                }
                out.append('\n');
            }
        }
        return out.append("}\n").toString();
    }

    public static String instr(Instr i) {
        String lhs = i.type() == MType.VOID ? "" : i + " = ";
        return lhs + switch (i) {
            case Instr.Bin b -> b.op + " " + b.type() + " " + b.op(0) + ", " + b.op(1);
            case Instr.Cmp c -> "cmp " + c.pred + " " + c.op(0).type() + " " + c.op(0) + ", " + c.op(1);
            case Instr.Cast c -> c.kind + " " + c.op(0).type() + " " + c.op(0) + " to " + c.type();
            case Instr.PtrAdd p -> "ptradd " + p.op(0) + ", " + p.op(1).type() + " " + p.op(1);
            case Instr.Load l -> "load" + (l.isVolatile ? " volatile" : "") + " " + l.type() + " " + l.op(0);
            case Instr.Store s -> "store" + (s.isVolatile ? " volatile" : "") + " " + s.op(1).type() + " " + s.op(0) + ", " + s.op(1);
            case Instr.Copy c -> "copy " + c.size + " " + c.op(0) + ", " + c.op(1);
            case Instr.Call c -> "call " + c.type() + " @" + c.callee + "("
                    + c.operands().stream().map(v -> v.type() + " " + v).collect(Collectors.joining(", ")) + ")";
            case Instr.CallPtr c -> "callptr " + c.type() + " " + c.op(0) + " ["
                    + c.targets.stream().map(n -> "@" + n).collect(Collectors.joining(", ")) + "]("
                    + c.operands().subList(1, c.operands().size()).stream().map(v -> v.type() + " " + v)
                            .collect(Collectors.joining(", ")) + ")";
            case Instr.Phi p -> {
                StringBuilder s = new StringBuilder("phi " + p.type());
                for (int k = 0; k < p.from.size(); k++) {
                    s.append(k == 0 ? " " : ", ").append('[').append(p.from.get(k).name()).append(", ").append(p.op(k)).append(']');
                }
                yield s.toString();
            }
            case Instr.Jump j -> "jump " + j.target.name();
            case Instr.Branch b -> "br " + b.op(0) + ", " + b.ifTrue.name() + ", " + b.ifFalse.name();
            case Instr.Ret r -> r.operands().isEmpty() ? "ret" : "ret " + r.op(0).type() + " " + r.op(0);
            case Instr.Unreachable u -> "unreachable";
            case Instr.Switch s -> {
                StringBuilder t = new StringBuilder("switch " + s.op(0).type() + " " + s.op(0) + ", " + s.otherwise.name() + " [");
                for (int k = 0; k < s.cases.size(); k++) {
                    t.append(k == 0 ? "" : ", ").append(s.cases.get(k)).append(": ").append(s.targets.get(k).name());
                }
                yield t.append(']').toString();
            }
        };
    }
}
