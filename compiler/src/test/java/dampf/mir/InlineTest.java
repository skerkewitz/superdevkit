package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Inlining on hand-written MIR: the call goes, the results stay. */
class InlineTest {
    private static final String PROGRAM = """
            global @_bubbles : 32

            func @_floating(ptr %b) -> i8 {
            b0_entry:
                %0 = load i8 %b
                %1 = cmp eq i8 %0, 2
                br %1, b1, b2
            b1:
                jump b3
            b2:
                %2 = cmp eq i8 %0, 3
                %3 = zext i1 %2 to i8
                jump b3
            b3:
                %4 = phi i8 [b1, 1], [b2, %3]
                ret i8 %4
            }

            func @_count() -> i16 {
            b0_entry:
                jump b1
            b1:
                %0 = phi i16 [b0_entry, 0], [b4, %7]
                %1 = phi i16 [b0_entry, 0], [b4, %8]
                %2 = cmp ult i16 %0, 8
                br %2, b2, b3
            b2:
                %3 = shl i16 %0, 2
                %4 = ptradd @_bubbles, i16 %3
                %5 = call i8 @_floating(ptr %4)
                %6 = cmp ne i8 %5, 0
                br %6, b5, b4
            b5:
                %9 = add i16 %1, 1
                jump b4
            b4:
                %8 = phi i16 [b2, %1], [b5, %9]
                %7 = add i16 %0, 1
                jump b1
            b3:
                ret i16 %1
            }
            """;

    private static long run(MModule m) {
        Interpreter in = new Interpreter(m, 1_000_000);
        int base = in.addressOf("_bubbles");
        int[] states = {2, 0, 3, 1, 2, 3, 3, 0};
        for (int k = 0; k < states.length; k++) {
            in.call("_set", List.of((long) base + 4 * k, (long) states[k]));
        }
        return in.call("_count", List.of());
    }

    private static final String SETTER = """

            func @_set(ptr %p, i8 %v) -> void {
            b0_entry:
                store i8 %p, %v
                ret
            }
            """;

    @Test
    void aSmallFunctionIsInlinedAndTheResultStaysTheSame() {
        MModule plain = MirParser.parse(PROGRAM + SETTER);
        MModule inlined = MirParser.parse(PROGRAM + SETTER);
        Optimizer.run(inlined, true);
        String count = Printer.print(inlined.function("_count"));
        assertFalse(count.contains("call"), count);
        assertEquals(5, run(plain));
        assertEquals(5, run(inlined));
    }

    @Test
    void anInlinedPhiKeepsTheRangeAPassKnewOfIt() {
        // what Induction leaves: the offset 2 * i stepped by 2, with the range it knows
        MModule m = MirParser.parse("""
                global @_w : 128

                func @_fill() -> void {
                b0_entry:
                    jump b1
                b1:
                    %0 = phi i16 [b0_entry, 0], [b2, %3]
                    %1 = cmp ult i16 %0, 128
                    br %1, b2, b3
                b2:
                    %2 = ptradd @_w, i16 %0
                    store i16 %2, 3073
                    %3 = add i16 %0, 2
                    jump b1
                b3:
                    ret
                }

                func @_main() -> void {
                b0_entry:
                    call void @_fill()
                    ret
                }
                """);
        Instr.Phi offset = (Instr.Phi) m.function("_fill").blocks().get(1).instrs().getFirst();
        offset.known = new Ranges.Range(0, 128);
        assertTrue(Inline.run(m));
        List<Instr.Phi> copies = m.function("_main").blocks().stream().flatMap(b -> b.instrs().stream())
                .filter(i -> i instanceof Instr.Phi).map(i -> (Instr.Phi) i).toList();
        assertEquals(1, copies.size(), Printer.print(m.function("_main")));
        assertEquals(new Ranges.Range(0, 128), copies.getFirst().known);
    }

    /** A function of about 200 instructions (above the limit for one call site): a chain of additions, with a loop if asked. */
    private static String large(String name, boolean loop) {
        StringBuilder sb = new StringBuilder("func @" + name + "(i16 %x) -> i16 {\nb0_entry:\n");
        String last = "%x";
        if (loop) {
            sb.append("    jump b1\nb1:\n    %l0 = phi i16 [b0_entry, %x], [b2, %l1]\n    %l2 = cmp ult i16 %l0, 9\n")
                    .append("    br %l2, b2, b3\nb2:\n    %l1 = add i16 %l0, 1\n    jump b1\nb3:\n");
            last = "%l0";
        }
        for (int k = 0; k < 200; k++) {
            sb.append("    %a").append(k).append(" = ").append(k % 2 == 0 ? "add" : "xor").append(" i16 ").append(last)
                    .append(", ").append(k + 3).append("\n");
            last = "%a" + k;
        }
        return sb.append("    ret i16 ").append(last).append("\n}\n").toString();
    }

    /** A caller: one call of callee in a loop of 4 rounds, or before it. */
    private static String caller(String name, String callee, boolean inLoop) {
        String call = "    %c = call i16 @" + callee + "(i16 %0)\n";
        return "func @" + name + "() -> i16 {\nb0_entry:\n" + (inLoop ? "" : call.replace("%0)", "7)"))
                + "    jump b1\nb1:\n    %0 = phi i16 [b0_entry, 0], [b2, %2]\n    %1 = phi i16 [b0_entry, 0], [b2, %3]\n"
                + "    %4 = cmp ult i16 %0, 4\n    br %4, b2, b3\nb2:\n" + (inLoop ? call : "")
                + "    %3 = add i16 %1, " + (inLoop ? "%c" : "%c") + "\n    %2 = add i16 %0, 1\n    jump b1\nb3:\n    ret i16 %1\n}\n";
    }

    @Test
    void aLargeFunctionCalledOnceInALoopIsInlinedWhereItHasNoLoop() {
        String program = large("_plain", false) + large("_once", false) + large("_looping", true)
                + caller("_inLoop", "_plain", true) + caller("_before", "_once", false) + caller("_withLoop", "_looping", true);
        MModule plain = MirParser.parse(program);
        MModule inlined = MirParser.parse(program);
        Optimizer.run(inlined, true);
        assertFalse(Printer.print(inlined.function("_inLoop")).contains("call"), "a call in every round goes");
        assertTrue(Printer.print(inlined.function("_before")).contains("call"), "a call outside a loop stays (above 150)");
        assertTrue(Printer.print(inlined.function("_withLoop")).contains("call"), "a callee with a loop of its own stays");
        for (String f : List.of("_inLoop", "_before", "_withLoop")) {
            assertEquals(new Interpreter(plain, 1_000_000).call(f, List.of()), new Interpreter(inlined, 1_000_000).call(f, List.of()), f);
        }
    }
}
