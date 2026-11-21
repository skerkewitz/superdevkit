package dampf.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dampf.lir.LCopy;
import dampf.lir.LDirective;
import dampf.lir.LFunction;
import dampf.lir.LInstr;
import dampf.lir.LItem;
import dampf.lir.LOperand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The choice of X and Y for places (B3) on hand-written LIR whose operands name their owners. */
class RegisterChoiceTest {
    private static final String P = "p";
    private static final String V = "v";
    private static final String W = "w";

    /** A place at its own predicted address (what RedundantLoads would find matters to the cost). */
    private static LOperand.Mem place(Object key) {
        int at = key == P ? 0 : key == V ? 2 : 4;
        return new LOperand.Mem(LOperand.Width.DP, LOperand.FRAME, at, LOperand.Index.NONE, false,
                new LOperand.Owner(key, false, 0));
    }

    private static LOperand.Mem global(String name, LOperand.Index index) {
        return new LOperand.Mem(LOperand.Width.NONE, name, 0, index, false);
    }

    private static LInstr ins(String m, LOperand op) {
        return new LInstr(m, op, null, true, true, false);
    }

    private static LInstr ins(String m) {
        return new LInstr(m, (LOperand) null, null, true, true, false);
    }

    /** The code after the choice, one instruction per line, frame places by owner. */
    private static List<String> choose(LItem... items) {
        LFunction f = new LFunction(new LDirective(LDirective.Kind.PROC, null, List.of("f"), null, ".proc f"));
        List<LItem> all = new ArrayList<>(List.of(items));
        all.add(ins("rtl"));
        f.rebuild(all);
        PlaceColouring.Frame frame = new PlaceColouring.Frame() {
            @Override
            public Integer fixed(PlaceColouring.Place p) {
                return p.key() == P ? 0 : null;
            }

            @Override
            public int bytes(PlaceColouring.Place p) {
                return 2;
            }
        };
        RegisterChoice.run(f, o -> Set.of(P, V, W).contains(o.key()), frame, Map.of(), false);
        List<String> out = new ArrayList<>();
        for (LItem it : f.items()) {
            if (it instanceof LInstr i) {
                String op = i.op() == null ? "" : i.op() instanceof LOperand.Mem m && m.owner() != null ? " " + m.owner().key()
                        : " " + i.op().text();
                out.add(i.mnemonic() + op);
            } else if (it instanceof LCopy c) {
                out.add("copy " + c.to().owner().key() + " " + c.from().owner().key());
            }
        }
        return out;
    }

    @Test
    void aValueUsedAsAnIndexAndLoadedLiesInX() {
        assertEquals(List.of("lda p", "clc", "adc #$0001", "tax", "lda _t,x", "sta _out", "txa", "sta _out2", "rtl"),
                choose(ins("lda", place(P)), ins("clc"), ins("adc", new LOperand.Imm("$0001")), ins("sta", place(V)),
                        ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_out", LOperand.Index.NONE)),
                        ins("lda", place(V)), ins("sta", global("_out2", LOperand.Index.NONE))));
    }

    @Test
    void anOperandOfASubtractionStaysInMemory() {
        // SBC has no form with X, and the operands cannot change places
        List<LItem> code = List.of(ins("lda", place(P)), ins("clc"), ins("adc", new LOperand.Imm("$0001")), ins("sta", place(V)),
                ins("lda", global("_g", LOperand.Index.NONE)), ins("sec"), ins("sbc", place(V)), ins("sta", global("_out", LOperand.Index.NONE)));
        assertEquals(List.of("lda p", "clc", "adc #$0001", "sta v", "lda _g", "sec", "sbc v", "sta _out", "rtl"),
                choose(code.toArray(LItem[]::new)));
    }

    @Test
    void whereXIsNeededForAnotherIndexYTakesIt() {
        assertEquals(List.of("lda p", "inc a", "tay", "ldx #$0005", "lda _t,x", "sta _out", "tya", "sta _out2", "rtl"),
                choose(ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                        ins("ldx", new LOperand.Imm("$0005")), ins("lda", global("_t", LOperand.Index.X)),
                        ins("sta", global("_out", LOperand.Index.NONE)), ins("lda", place(V)), ins("sta", global("_out2", LOperand.Index.NONE))));
    }

    @Test
    void aValueLiveOverACallStaysInMemory() {
        assertEquals(List.of("lda p", "inc a", "sta v", "jsl g", "lda v", "sta _out", "rtl"),
                choose(ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                        ins("jsl", new LOperand.Label("g")), ins("lda", place(V)), ins("sta", global("_out", LOperand.Index.NONE))));
    }

    @Test
    void aStepInPlaceIsAnIncrementOfTheRegister() {
        // v = p + 1 stepped again in its place (a counter), then read as an index twice
        assertEquals(List.of("lda p", "inc a", "tax", "inx", "lda _t,x", "sta _out", "lda _u,x", "sta _out2", "rtl"),
                choose(ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)), ins("inc", place(V)),
                        ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_out", LOperand.Index.NONE)),
                        ins("ldx", place(V)), ins("lda", global("_u", LOperand.Index.X)), ins("sta", global("_out2", LOperand.Index.NONE))));
    }

    @Test
    void aCopyBetweenRegistersIsATransfer() {
        // w is v on one way and 0 on the other (a φ): written there while v is live, so the two
        // do not coalesce; w goes to X for the index, v to Y (its ADC takes the other operand)
        assertEquals(List.of("lda p", "inc a", "tay", "lda _g", "beq other", "tyx", "bra join", "ldx #$0000",
                        "lda _t,x", "sta _out", "tya", "clc", "adc _h", "sta _out2", "rtl"),
                choose(ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                        ins("lda", global("_g", LOperand.Index.NONE)), ins("beq", new LOperand.Label("other")),
                        new LCopy(place(W), place(V)), ins("bra", new LOperand.Label("join")),
                        new dampf.lir.LLabel("other"), ins("stz", place(W)),
                        new dampf.lir.LLabel("join"), ins("ldx", place(W)), ins("lda", global("_t", LOperand.Index.X)),
                        ins("sta", global("_out", LOperand.Index.NONE)),
                        ins("lda", global("_h", LOperand.Index.NONE)), ins("clc"), ins("adc", place(V)),
                        ins("sta", global("_out2", LOperand.Index.NONE))));
    }

    @Test
    void aValueNobodyReadsIsLeftToDeadStores() {
        assertEquals(List.of("lda p", "inc a", "sta v", "rtl"),
                choose(ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V))));
    }

    @Test
    void aLoadWhoseFlagsABranchReadsIsATransfer() {
        // the branch reads the load's Z: TXA, which sets it the same, not nothing
        assertEquals(List.of("lda p", "and #$00FF", "tax", "lda _g", "sta _out0", "txa", "beq done", "txa", "sta _out", "rtl"),
                choose(ins("lda", place(P)), ins("and", new LOperand.Imm("$00FF")), ins("sta", place(V)),
                        ins("lda", global("_g", LOperand.Index.NONE)), ins("sta", global("_out0", LOperand.Index.NONE)),
                        ins("lda", place(V)), ins("beq", new LOperand.Label("done")),
                        ins("lda", place(V)), ins("sta", global("_out", LOperand.Index.NONE)),
                        new dampf.lir.LLabel("done")));
    }

    @Test
    void aCommutativeOperationTakesItsOtherOperandInstead() {
        // LDA q / CLC / ADC v with v in X: TXA / CLC / ADC q
        assertEquals(List.of("lda p", "inc a", "tax", "lda _t,x", "sta _out", "txa", "clc", "adc _g", "sta _out2", "rtl"),
                choose(ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                        ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_out", LOperand.Index.NONE)),
                        ins("lda", global("_g", LOperand.Index.NONE)), ins("clc"), ins("adc", place(V)),
                        ins("sta", global("_out2", LOperand.Index.NONE))));
    }
}
