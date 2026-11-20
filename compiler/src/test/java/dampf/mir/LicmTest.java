package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Loop-invariant code moves before the loop; conditional, expensive and varying code stays. */
class LicmTest {
    private static final String PROGRAM = """
            func @_main(i16 %a, i16 %b) -> i16 {
            b0_entry:
                jump b1_head
            b1_head:
                %0 = phi i16 [b0_entry, 0], [b4_latch, %9]
                %1 = phi i16 [b0_entry, 0], [b4_latch, %8]
                %2 = add i16 %a, %b
                %3 = shl i16 %2, 2
                %4 = udiv i16 %a, 3
                %5 = cmp ult i16 %0, 10
                br %5, b2_body, b5_end
            b2_body:
                %6 = cmp eq i16 %0, 7
                br %6, b3_rare, b4_latch
            b3_rare:
                %7 = xor i16 %a, 255
                jump b4_latch
            b4_latch:
                %10 = phi i16 [b2_body, 0], [b3_rare, %7]
                %11 = add i16 %3, %4
                %12 = add i16 %1, %11
                %8 = add i16 %12, %10
                %9 = add i16 %0, 1
                jump b1_head
            b5_end:
                ret i16 %1
            }
            """;

    @Test
    void invariantCodeMovesBeforeTheLoop() {
        MModule plain = MirParser.parse(PROGRAM);
        MModule m = MirParser.parse(PROGRAM);
        MFunction f = m.function("_main");
        assertTrue(Licm.run(f));
        Verifier.verify(m);
        Block entry = f.entry();
        String moved = entry.instrs().stream().map(Printer::instr).toList().toString();
        assertTrue(moved.contains("add i16 %a, %b") && moved.contains("shl i16"), moved);
        assertTrue(!moved.contains("udiv") && !moved.contains("xor"), moved);   // expensive, and conditional
        for (long a : new long[] {0, 5, 1000, 65535}) {
            for (long b : new long[] {0, 3, 40000}) {
                assertEquals(new Interpreter(plain, 10_000).call("_main", List.of(a, b)),
                        new Interpreter(m, 10_000).call("_main", List.of(a, b)));
            }
        }
    }
    @Test
    void aLoadNothingInTheLoopWritesMovesOut() {
        // the bound in a global moves; the one the loop stores to stays; a call of a function that
        // writes memory keeps every load in its loop
        String text = """
                global @_n : 2
                global @_m : 2
                global @_t : 64

                func @_w() -> void {
                b0_entry:
                    store i16 @_t, 1
                    ret
                }

                func @_main() -> i16 {
                b0_entry:
                    jump b1_head
                b1_head:
                    %0 = phi i16 [b0_entry, 0], [b2_body, %5]
                    %1 = load i16 @_n
                    %2 = load i16 @_m
                    %4 = cmp ult i16 %0, %1
                    br %4, b2_body, b3_end
                b2_body:
                    %6 = ptradd @_t, i16 %0
                    store i8 %6, 1
                    store i16 @_m, %0
                    %5 = add i16 %0, 1
                    jump b1_head
                b3_end:
                    ret i16 %2
                }

                func @_calls() -> i16 {
                b0_entry:
                    jump b1_head
                b1_head:
                    %0 = phi i16 [b0_entry, 0], [b2_body, %5]
                    %1 = load i16 @_n
                    %4 = cmp ult i16 %0, %1
                    br %4, b2_body, b3_end
                b2_body:
                    call void @_w()
                    %5 = add i16 %0, 1
                    jump b1_head
                b3_end:
                    ret i16 %0
                }
                """;
        MModule m = MirParser.parse(text);
        MFunction f = m.function("_main");
        assertTrue(Licm.run(m, f, Gvn.writesMemory(m)));
        assertTrue(!Licm.run(m, m.function("_calls"), Gvn.writesMemory(m)));
        Verifier.verify(m);
        String printed = Printer.print(f);
        String entry = printed.substring(0, printed.indexOf("b1_head:"));
        assertTrue(entry.contains("load i16 @_n") && !entry.contains("load i16 @_m"), printed);
        Interpreter want = new Interpreter(MirParser.parse(text), 100_000);
        Interpreter got = new Interpreter(m, 100_000);
        want.call("_main", List.of());
        got.call("_main", List.of());
        for (int a = 0; a < 2; a++) {
            assertEquals(want.load(want.addressOf("_m") + a, 1), got.load(got.addressOf("_m") + a, 1));
        }
    }
}
