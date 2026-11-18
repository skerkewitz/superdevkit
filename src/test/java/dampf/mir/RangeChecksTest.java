package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Chains of tests against neighbouring constants become one range check ({@link RangeChecks}):
 * the result must be the same as before for every input, and the chain must be gone.
 */
class RangeChecksTest {
    /** x == a || x == b (|| x == c), as branches, with a φ where the ways meet. */
    private static String or(String type, long... ks) {
        StringBuilder s = new StringBuilder("func @_f(" + type + " %x) -> i8 {\nb0_entry:\n");
        for (int i = 0; i < ks.length; i++) {
            if (i > 0) {
                s.append("b").append(10 + i).append("_or:\n");
            }
            s.append("    %").append(i).append(" = cmp eq ").append(type).append(" %x, ").append(ks[i]).append('\n');
            String next = i + 1 < ks.length ? "b" + (11 + i) + "_or" : "b2_no";
            s.append("    br %").append(i).append(", b1_yes, ").append(next).append('\n');
        }
        s.append("b1_yes:\n    jump b3_join\nb2_no:\n    jump b3_join\n");
        s.append("b3_join:\n    %9 = phi i8 [b1_yes, 1], [b2_no, 0]\n    ret i8 %9\n}\n");
        return s.toString();
    }

    /** x != a && x != b: the same with the branches swapped. */
    private static String and(String type, long a, long b) {
        return "func @_f(" + type + " %x) -> i8 {\nb0_entry:\n"
                + "    %0 = cmp ne " + type + " %x, " + a + "\n    br %0, b10_and, b1_no\n"
                + "b10_and:\n    %1 = cmp ne " + type + " %x, " + b + "\n    br %1, b2_yes, b1_no\n"
                + "b1_no:\n    ret i8 0\nb2_yes:\n    ret i8 7\n}\n";
    }

    private static void same(String text, long[] inputs, boolean merged) {
        MModule plain = MirParser.parse(text);
        MModule checked = MirParser.parse(text);
        MFunction f = checked.functions().getFirst();
        int blocks = f.blocks().size();
        RangeChecks.run(f);
        Verifier.verify(checked);
        assertEquals(merged, f.blocks().size() < blocks, () -> "merged:\n" + Printer.print(checked));
        Interpreter a = new Interpreter(plain, Long.MAX_VALUE);
        Interpreter b = new Interpreter(checked, Long.MAX_VALUE);
        for (long x : inputs) {
            assertEquals(a.call("_f", List.of(x)), b.call("_f", List.of(x)), () -> "x = " + x + ":\n" + Printer.print(checked));
        }
    }

    private static long[] all8() {
        long[] v = new long[256];
        for (int i = 0; i < 256; i++) {
            v[i] = i;
        }
        return v;
    }

    @Test
    void neighbouringConstantsMerge() {
        same(or("i8", 2, 3), all8(), true);
        same(or("i8", 3, 2), all8(), true);
        same(or("i8", 0, 1), all8(), true);
        same(or("i8", 254, 255), all8(), true);
        same(or("i8", 2, 3, 4), all8(), true);
        same(or("i8", 4, 2, 3), all8(), true);
        same(or("i8", 4, 5, 6, 7), all8(), true);   // {4, 5} as a mask, then {4..6}, then {4..7} as a mask
        same(and("i8", 2, 3), all8(), true);
        long[] wide = {0, 1, 2, 99, 100, 101, 102, 103, 0x7FFF, 0x8000, 0xFFFF};
        same(or("i16", 100, 101), wide, true);
        same(or("i16", 0xFFFE, 0xFFFF), wide, true);
    }

    @Test
    void otherConstantsStay() {
        same(or("i8", 2, 4), all8(), false);
        same(or("i8", 255, 0), all8(), false);   // would wrap around
    }
}
