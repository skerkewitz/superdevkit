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
}
