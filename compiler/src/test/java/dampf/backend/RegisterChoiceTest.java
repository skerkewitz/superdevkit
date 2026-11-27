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
        return choose(dampf.util.PassLimit.none(), items);
    }

    private static List<String> choose(dampf.util.PassLimit limit, LItem... items) {
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
        RegisterChoice.run(f, o -> Set.of(P, V, W).contains(o.key()), frame, Map.of(), false, false, List.of(), limit);
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
    void eachChoiceIsAnApplicationOfThePassLimit() {
        // T3: dev/bisect_passes.sh can stop the choice before any group goes to a register, and names it
        java.util.function.Supplier<LItem[]> code = () -> new LItem[] {ins("lda", place(P)), ins("clc"),
                ins("adc", new LOperand.Imm("$0001")), ins("sta", place(V)), ins("ldx", place(V)),
                ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_out", LOperand.Index.NONE)),
                ins("lda", place(V)), ins("sta", global("_out2", LOperand.Index.NONE))};
        dampf.util.PassLimit none = new dampf.util.PassLimit(0, "");
        assertEquals(List.of("lda p", "clc", "adc #$0001", "sta v", "ldx v", "lda _t,x", "sta _out", "lda v", "sta _out2", "rtl"),
                choose(none, code.get()));
        assertEquals(0, none.applied());
        dampf.util.PassLimit all = dampf.util.PassLimit.none();
        choose(all, code.get());
        assertEquals(1, all.applied());
        assertEquals("RegisterChoice X on f", all.last());
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

    @Test
    void aLoopCounterStepsAndComparesInX() {
        // v = 0; loop: t[v] = 1; v = v + 2; while v < 16 (A is not read after the loop)
        assertEquals(List.of("ldx #$0000", "lda #$0001", "sta _t,x", "inx", "inx", "cpx #$0010", "bcc loop", "rtl"),
                choose(ins("stz", place(V)),
                        new dampf.lir.LLabel("loop"),
                        ins("lda", new LOperand.Imm("$0001")), ins("ldx", place(V)), ins("sta", global("_t", LOperand.Index.X)),
                        ins("clc"), ins("lda", place(V)), ins("adc", new LOperand.Imm("$0002")), ins("sta", place(V)),
                        ins("lda", place(V)), ins("cmp", new LOperand.Imm("$0010")), ins("bcc", new LOperand.Label("loop"))));
    }

    @Test
    void aCallAfterTheLastUseDoesNotKeepAValueOutOfX() {
        // a call writes X and Y, but takes no arguments in them: X is free again before it
        assertEquals(List.of("lda p", "inc a", "tax", "lda _t,x", "sta _out", "lda _u,x", "sta _out2", "jsl g", "rtl"),
                choose(ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                        ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_out", LOperand.Index.NONE)),
                        ins("ldx", place(V)), ins("lda", global("_u", LOperand.Index.X)), ins("sta", global("_out2", LOperand.Index.NONE)),
                        ins("jsl", new LOperand.Label("g"))));
    }

    @Test
    void aStepInXLeavesNothingInA() {
        // the loop's test reloads v, which A held after STA v; as INX the step leaves A as it
        // was, so the reload stays (TXA) (a random loop program, seed 20778041)
        assertEquals(List.of("ldx #$0000", "lda #$7FFF", "sta _g", "inx", "inx", "inx", "txa", "eor #$8000", "cmp #$002A",
                        "bcc loop", "rtl"),
                choose(ins("stz", place(V)),
                        new dampf.lir.LLabel("loop"),
                        ins("lda", new LOperand.Imm("$7FFF")), ins("sta", global("_g", LOperand.Index.NONE)),
                        ins("clc"), ins("lda", place(V)), ins("adc", new LOperand.Imm("$0003")), ins("sta", place(V)),
                        ins("lda", place(V)), ins("eor", new LOperand.Imm("$8000")), ins("cmp", new LOperand.Imm("$002A")),
                        ins("bcc", new LOperand.Label("loop"))));
    }

    @Test
    void aConstantStoredToAPlaceInXIsLoadedIntoX() {
        // LDA #k / STA v with A not read afterwards: LDX #k
        assertEquals(List.of("ldx #$0040", "lda _t,x", "sta _out", "lda _u,x", "sta _out2", "rtl"),
                choose(ins("lda", new LOperand.Imm("$0040")), ins("sta", place(V)),
                        ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_out", LOperand.Index.NONE)),
                        ins("ldx", place(V)), ins("lda", global("_u", LOperand.Index.X)), ins("sta", global("_out2", LOperand.Index.NONE))));
    }

    @Test
    void aZeroStoredBeforeABranchKeepsTheFlagsOfA() {
        // STZ v between AND (which sets Z from A) and BNE: LDX #0, then CMP #0 sets Z from A again
        assertEquals(List.of("lda _g", "and #$00FF", "ldx #$0000", "cmp #$0000", "bne skip", "inx", "skip:", "lda _t,x", "sta _out",
                        "lda _u,x", "sta _out2", "rtl"),
                chooseWithLabels(ins("lda", global("_g", LOperand.Index.NONE)), ins("and", new LOperand.Imm("$00FF")), ins("stz", place(V)),
                        ins("bne", new LOperand.Label("skip")), ins("inc", place(V)),
                        new dampf.lir.LLabel("skip"),
                        ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_out", LOperand.Index.NONE)),
                        ins("ldx", place(V)), ins("lda", global("_u", LOperand.Index.X)), ins("sta", global("_out2", LOperand.Index.NONE))));
    }

    /** As choose(), with labels in the result. */
    private static List<String> chooseWithLabels(LItem... items) {
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
        RegisterChoice.run(f, o -> Set.of(P, V, W).contains(o.key()), frame, Map.of(), false, false);
        List<String> out = new ArrayList<>();
        for (LItem it : f.items()) {
            if (it instanceof LInstr i) {
                String op = i.op() == null ? "" : i.op() instanceof LOperand.Mem m && m.owner() != null ? " " + m.owner().key()
                        : " " + i.op().text();
                out.add(i.mnemonic() + op);
            } else if (it instanceof dampf.lir.LLabel l) {
                out.add(l.name() + ":");
            }
        }
        return out;
    }

    @Test
    void anIndexInYIsAddressedWithY() {
        // X holds another index while v is live: v goes to Y, and its LDX / LDA t,X is LDA t,Y
        assertEquals(List.of("lda p", "inc a", "tay", "ldx #$0005", "lda _a,x", "sta _o", "lda _t,y", "sta _o2", "tya", "sta _o3",
                        "ldx #$0007", "lda _c,x", "sta _o4", "rtl"),
                choose(ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                        ins("ldx", new LOperand.Imm("$0005")), ins("lda", global("_a", LOperand.Index.X)), ins("sta", global("_o", LOperand.Index.NONE)),
                        ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_o2", LOperand.Index.NONE)),
                        ins("lda", place(V)), ins("sta", global("_o3", LOperand.Index.NONE)),
                        ins("ldx", new LOperand.Imm("$0007")), ins("lda", global("_c", LOperand.Index.X)), ins("sta", global("_o4", LOperand.Index.NONE))));
    }

    @Test
    void aPlaceGoesToXForALoopOnly() {
        // v is an operand of SBC before and after the loop, so it lives in memory there; in the
        // loop it is X: loaded before, stored after (the loop changes it)
        LFunction f = new LFunction(new LDirective(LDirective.Kind.PROC, null, List.of("f"), null, ".proc f"));
        f.rebuild(new ArrayList<>(List.of(
                ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                ins("lda", global("_g", LOperand.Index.NONE)), ins("sec"), ins("sbc", place(V)), ins("sta", global("_o1", LOperand.Index.NONE)),
                new dampf.lir.LLabel("loop"),
                ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_o2", LOperand.Index.NONE)),
                ins("inc", place(V)), ins("lda", place(V)), ins("cmp", new LOperand.Imm("$0010")), ins("bcc", new LOperand.Label("loop")),
                ins("lda", global("_h", LOperand.Index.NONE)), ins("sec"), ins("sbc", place(V)), ins("sta", global("_o3", LOperand.Index.NONE)),
                ins("rtl"))));
        java.util.Set<dampf.lir.LBlock> loop = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        loop.add(f.block("loop"));
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
        Map<dampf.lir.LBlock, Double> weights = Map.of(f.block("loop"), 8.0);
        RegisterChoice.run(f, o -> Set.of(P, V, W).contains(o.key()), frame, weights, false, false, List.of(loop));
        List<String> out = new ArrayList<>();
        for (LInstr i : f.instrs()) {
            out.add(i.mnemonic() + (i.op() == null ? "" : i.op() instanceof LOperand.Mem m && m.owner() != null ? " " + m.owner().key()
                    : " " + i.op().text()));
        }
        assertEquals(List.of("lda p", "inc a", "sta v", "lda _g", "sec", "sbc v", "sta _o1", "ldx v",
                "lda _t,x", "sta _o2", "inx", "cpx #$0010", "bcc loop",
                "stx v", "lda _h", "sec", "sbc v", "sta _o3", "rtl"), out);
    }

    @Test
    void aPlaceGoesToXForTheCommonPartOfALoop() {
        // the loop's rare way needs X for another index and a call: v stays in X on the common
        // way, is stored where the rare way starts and loaded where it ends
        LFunction f = new LFunction(new LDirective(LDirective.Kind.PROC, null, List.of("f"), null, ".proc f"));
        f.rebuild(new ArrayList<>(List.of(
                ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                ins("lda", global("_g", LOperand.Index.NONE)), ins("sec"), ins("sbc", place(V)), ins("sta", global("_o1", LOperand.Index.NONE)),
                new dampf.lir.LLabel("loop"),
                ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("beq", new LOperand.Label("step")),
                new dampf.lir.LLabel("rare"),
                ins("ldx", new LOperand.Imm("$0005")), ins("lda", global("_a", LOperand.Index.X)), ins("sta", global("_o", LOperand.Index.NONE)),
                ins("jsl", new LOperand.Label("g")),
                new dampf.lir.LLabel("step"),
                ins("inc", place(V)), ins("lda", place(V)), ins("cmp", new LOperand.Imm("$0010")), ins("bcc", new LOperand.Label("loop")),
                ins("lda", global("_h", LOperand.Index.NONE)), ins("sec"), ins("sbc", place(V)), ins("sta", global("_o3", LOperand.Index.NONE)),
                ins("rtl"))));
        java.util.Set<dampf.lir.LBlock> loop = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        loop.add(f.block("loop"));
        loop.add(f.block("rare"));
        loop.add(f.block("step"));
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
        Map<dampf.lir.LBlock, Double> weights = Map.of(f.block("loop"), 8.0, f.block("rare"), 1.0, f.block("step"), 8.0);
        RegisterChoice.run(f, o -> Set.of(P, V, W).contains(o.key()), frame, weights, false, false, List.of(loop));
        List<String> out = new ArrayList<>();
        for (LInstr i : f.instrs()) {
            out.add(i.mnemonic() + (i.op() == null ? "" : i.op() instanceof LOperand.Mem m && m.owner() != null ? " " + m.owner().key()
                    : " " + i.op().text()));
        }
        assertEquals(List.of("lda p", "inc a", "sta v", "lda _g", "sec", "sbc v", "sta _o1", "ldx v",
                "lda _t,x", "beq step",
                "stx v", "ldx #$0005", "lda _a,x", "sta _o", "jsl g", "ldx v",
                "inx", "cpx #$0010", "bcc loop",
                "stx v", "lda _h", "sec", "sbc v", "sta _o3", "rtl"), out);
    }

    @Test
    void theLoopsAreKnownAfterAnEarlierChoice() {
        // w goes to X first, which cuts the code into new blocks; v, in Y for the loop only (the
        // loop needs X for another index), must still find the loop among them
        LFunction f = new LFunction(new LDirective(LDirective.Kind.PROC, null, List.of("f"), null, ".proc f"));
        f.rebuild(new ArrayList<>(List.of(
                ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(W)),
                ins("ldx", place(W)), ins("lda", global("_s", LOperand.Index.X)), ins("sta", global("_o0", LOperand.Index.NONE)),
                ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                ins("lda", global("_g", LOperand.Index.NONE)), ins("sec"), ins("sbc", place(V)), ins("sta", global("_o1", LOperand.Index.NONE)),
                new dampf.lir.LLabel("loop"),
                ins("ldx", new LOperand.Imm("$0005")), ins("lda", global("_a", LOperand.Index.X)), ins("sta", global("_o", LOperand.Index.NONE)),
                ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_o2", LOperand.Index.NONE)),
                ins("inc", place(V)), ins("lda", place(V)), ins("cmp", new LOperand.Imm("$0010")), ins("bcc", new LOperand.Label("loop")),
                ins("lda", global("_h", LOperand.Index.NONE)), ins("sec"), ins("sbc", place(V)), ins("sta", global("_o3", LOperand.Index.NONE)),
                ins("rtl"))));
        java.util.Set<dampf.lir.LBlock> loop = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        loop.add(f.block("loop"));
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
        RegisterChoice.run(f, o -> Set.of(P, V, W).contains(o.key()), frame, Map.of(f.block("loop"), 8.0), false, false, List.of(loop));
        List<String> out = new ArrayList<>();
        for (LInstr i : f.instrs()) {
            out.add(i.mnemonic() + (i.op() == null ? "" : i.op() instanceof LOperand.Mem m && m.owner() != null ? " " + m.owner().key()
                    : " " + i.op().text()));
        }
        assertEquals(List.of("lda p", "inc a", "tax", "lda _s,x", "sta _o0", "lda p", "inc a", "sta v",
                "lda _g", "sec", "sbc v", "sta _o1", "ldy v",
                "ldx #$0005", "lda _a,x", "sta _o", "lda _t,y", "sta _o2", "iny", "cpy #$0010", "bcc loop",
                "sty v", "lda _h", "sec", "sbc v", "sta _o3", "rtl"), out);
    }

    @Test
    void aLoadLeftOutNeverReliesOnAStoreTheLoadFormTakes() {
        // v = g1, w = g6, both indexes later (registers); the reload of v before EOR must stay
        // (A held v after STA v only while LDA _g1 / STA v was not LDY _g1; a random loop
        // program, seed 27879, compared the wrong value)
        List<String> out = choose(ins("lda", global("_g1", LOperand.Index.NONE)), ins("sta", place(V)),
                ins("lda", global("_g6", LOperand.Index.NONE)), ins("sta", place(W)),
                ins("lda", place(V)), ins("eor", new LOperand.Imm("$8000")), ins("sta", global("_r", LOperand.Index.NONE)),
                ins("ldx", place(W)), ins("lda", global("_t", LOperand.Index.X)), ins("sta", global("_o", LOperand.Index.NONE)),
                ins("ldx", place(V)), ins("lda", global("_u", LOperand.Index.X)), ins("sta", global("_o2", LOperand.Index.NONE)));
        String before = out.get(out.indexOf("eor #$8000") - 1);
        assertEquals(true, before.equals("tya") || before.equals("txa") || before.equals("lda v") || before.equals("lda _g1"), out.toString());
    }

    @Test
    void anIndexStaysInXWhereTheCommutativeFormNeedsIt() {
        // v in Y (X is taken while v is live); LDA t,X / CLC / ADC v becomes TYA / CLC / ADC t,X,
        // which needs X = v: the LDX before stays (as TYX), the access is not made Y-indexed
        // (a random loop program, seed 29577)
        List<String> out = choose(ins("lda", place(P)), ins("inc", new LOperand.Acc()), ins("sta", place(V)),
                ins("ldx", new LOperand.Imm("$0005")), ins("lda", global("_a", LOperand.Index.X)), ins("sta", global("_o", LOperand.Index.NONE)),
                ins("ldx", place(V)), ins("lda", global("_t", LOperand.Index.X)), ins("clc"), ins("adc", place(V)),
                ins("sta", global("_t", LOperand.Index.X)),
                ins("ldx", new LOperand.Imm("$0007")), ins("lda", global("_c", LOperand.Index.X)), ins("sta", global("_o4", LOperand.Index.NONE)));
        int adc = out.indexOf("adc _t,x");
        if (adc >= 0) {
            assertEquals(true, out.subList(0, adc).contains("tyx") || out.subList(0, adc).contains("ldx v"), out.toString());
        }
    }

    @Test
    void aCounterGoingDownStepsWithDexAndTestsTheFlagsOfIt() {
        // v = 126; loop: t[v] = 1; v = v - 2 (ADC #$FFFE, as MIR writes it); while v >= 0: the
        // step is DEX DEX, and the test needs no TXA (N describes X on both ways in)
        assertEquals(List.of("ldx #$007E", "bra test", "body:", "lda #$0001", "sta _t,x", "dex", "dex", "test:", "bpl body", "rtl"),
                chooseWithLabels(ins("lda", new LOperand.Imm("$007E")), ins("sta", place(V)), ins("bra", new LOperand.Label("test")),
                        new dampf.lir.LLabel("body"),
                        ins("lda", new LOperand.Imm("$0001")), ins("ldx", place(V)), ins("sta", global("_t", LOperand.Index.X)),
                        ins("clc"), ins("lda", place(V)), ins("adc", new LOperand.Imm("$FFFE")), ins("sta", place(V)),
                        new dampf.lir.LLabel("test"),
                        ins("lda", place(V)), ins("bpl", new LOperand.Label("body"))));
    }

    @Test
    void aTransferForTheFlagsStaysAfterAPhiCopy() {
        // LDY sets N and Z from Y, but the φ copy between (LDA / STA once expanded) sets them from A:
        // the TYA before BEQ stays (a random loop program, seed 32199)
        List<String> out = chooseWithLabels(ins("lda", global("_g", LOperand.Index.NONE)), ins("sta", place(V)),
                new LCopy(place(W), place(P)),
                ins("lda", place(V)), ins("beq", new LOperand.Label("done")),
                ins("lda", place(W)), ins("sta", global("_o", LOperand.Index.NONE)),
                new dampf.lir.LLabel("done"),
                ins("ldy", place(V)), ins("lda", global("_t", LOperand.Index.Y)), ins("sta", global("_o2", LOperand.Index.NONE)));
        int beq = out.indexOf("beq done");
        assertEquals(true, beq > 0 && !out.get(beq - 1).startsWith("copy") && !out.get(beq - 1).startsWith("sta"), out.toString());
    }
}
