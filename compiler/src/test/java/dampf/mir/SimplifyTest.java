package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The canonical form Simplify writes (its class comment; docs/current-compiler-flaws.md, flaw C):
 * each rule on MIR text, the result printed, and the interpreter checks that the function means
 * the same for the edge values of its type.
 */
class SimplifyTest {
    private static final long[] EDGES = {0, 1, 2, 3, 99, 100, 101, 126, 127, 128, 129, 254, 255, 256, 32766, 32767, 32768,
            32769, 65533, 65534, 65535};

    /** func @_f(i16 %a) -> i16 { body }, simplified until nothing changes; its text. */
    private static String simplified(String type, String body) {
        return simplified(type, body, "");
    }

    private static String simplified(String type, String body, String globals) {
        String text = globals + "func @_f(" + type + " %a) -> i32 {\nb0_entry:\n" + body + "}\n";
        MModule plain = MirParser.parse(text);
        MModule m = MirParser.parse(text);
        MFunction f = m.function("_f");
        for (int round = 0; round < 10 && (Simplify.run(f) | DeadCode.run(f)); round++) {
            Verifier.verify(m);
        }
        MType t = MType.valueOf(type.toUpperCase());
        for (long a : EDGES) {
            long arg = t.wrap(a);
            assertEquals(new Interpreter(plain, 1000).call("_f", List.of(arg)), new Interpreter(m, 1000).call("_f", List.of(arg)),
                    "a = " + arg + " in\n" + Printer.print(m));
        }
        return Printer.print(f);
    }

    private static String compare(String type, String pred, long k) {
        return simplified(type, "    %0 = cmp " + pred + " " + type + " %a, " + k + "\n"
                + "    %1 = zext i1 %0 to i32\n    ret i32 %1\n");
    }

    @Test
    void c1SubtractingAConstantIsAddingItsNegative() {
        String s = simplified("i16", "    %0 = sub i16 %a, 3\n    %1 = zext i16 %0 to i32\n    ret i32 %1\n");
        assertTrue(s.contains("add i16 %a, -3"), s);
        assertFalse(s.contains("sub"), s);
        s = simplified("i8", "    %0 = sub i8 %a, 1\n    %1 = zext i8 %0 to i32\n    ret i32 %1\n");
        assertTrue(s.contains("add i8 %a, -1"), s);
        // a subtraction from a constant stays (it is no step)
        s = simplified("i16", "    %0 = sub i16 5, %a\n    %1 = zext i16 %0 to i32\n    ret i32 %1\n");
        assertTrue(s.contains("sub i16 5, %a"), s);
    }

    @Test
    void c2ACommutativeOperationHasItsConstantOnTheRight() {
        for (String op : new String[] {"add", "mul", "and", "or", "xor"}) {
            String s = simplified("i16", "    %0 = " + op + " i16 7, %a\n    %1 = zext i16 %0 to i32\n    ret i32 %1\n");
            assertTrue(s.contains(op + " i16 %a, 7"), s);
        }
        String s = simplified("i16", "    %0 = shl i16 7, %a\n    %1 = zext i16 %0 to i32\n    ret i32 %1\n");
        assertTrue(s.contains("shl i16 7, %a"), s);   // not commutative
    }

    @Test
    void c3AComparisonWithAConstantIsOneOfSixAndTheTypeDecidesTheRest() {
        // x > c is x >= c + 1, x <= c is x < c + 1
        assertTrue(compare("i16", "ugt", 99).contains("cmp uge i16 %a, 100"));
        assertTrue(compare("i16", "ule", 99).contains("cmp ult i16 %a, 100"));
        assertTrue(compare("i16", "sgt", 99).contains("cmp sge i16 %a, 100"));
        assertTrue(compare("i16", "sle", -5).contains("cmp slt i16 %a, -4"));
        // against the least value plus one: equality
        assertTrue(compare("i16", "uge", 1).contains("cmp ne i16 %a, 0"));
        assertTrue(compare("i16", "ult", 1).contains("cmp eq i16 %a, 0"));
        assertTrue(compare("i16", "ugt", 0).contains("cmp ne i16 %a, 0"));   // through x >= 1
        assertTrue(compare("i16", "ule", 0).contains("cmp eq i16 %a, 0"));
        assertTrue(compare("i16", "sge", -32767).contains("cmp ne i16 %a, -32768"));
        assertTrue(compare("i8", "slt", -127).contains("cmp eq i8 %a, -128"));
        // decided by the type
        for (String[] c : new String[][] {{"ugt", "65535"}, {"sgt", "32767"}, {"slt", "-32768"}, {"ult", "0"}}) {
            String s = compare("i16", c[0], Long.parseLong(c[1]));
            assertFalse(s.contains("cmp"), s);
            assertTrue(s.contains("i1 false"), s);
        }
        for (String[] c : new String[][] {{"ule", "65535"}, {"sle", "32767"}, {"sge", "-32768"}, {"uge", "0"}}) {
            String s = compare("i16", c[0], Long.parseLong(c[1]));
            assertFalse(s.contains("cmp"), s);
            assertTrue(s.contains("i1 true"), s);
        }
        // the constant on the right
        String s = simplified("i16", "    %0 = cmp ult i16 10, %a\n    %1 = zext i1 %0 to i32\n    ret i32 %1\n");
        assertTrue(s.contains("cmp uge i16 %a, 11"), s);
        // the six stay as they are
        for (String p : new String[] {"eq", "ne", "ult", "uge", "slt", "sge"}) {
            assertTrue(compare("i16", p, 100).contains("cmp " + p + " i16 %a, 100"));
        }
    }

    @Test
    void c9AnElementOfATwoDimensionalArrayIsOneIndexIntoIt() {
        // t[r][c] with r = a & 7, c = a & 15: stored through one index, loaded through two
        String text = """
                global @_t : 128

                func @_f(i16 %a) -> i32 {
                b0_entry:
                    %0 = and i16 %a, 7
                    %1 = mul i16 %0, 16
                    %2 = and i16 %a, 15
                    %3 = add i16 %1, %2
                    %4 = ptradd @_t, i16 %3
                    %9 = trunc i16 %a to i8
                    store i8 %4, %9
                    %5 = ptradd @_t, i16 %1
                    %6 = ptradd %5, i16 %2
                    %7 = load i8 %6
                    %8 = zext i8 %7 to i32
                    ret i32 %8
                }
                """;
        MModule plain = MirParser.parse(text);
        MModule m = MirParser.parse(text);
        MFunction f = m.function("_f");
        for (int round = 0; round < 10 && (Simplify.run(f) | DeadCode.run(f)); round++) {
            Verifier.verify(m);
        }
        String printed = Printer.print(f);
        assertFalse(printed.matches("(?s).*ptradd %.*"), printed);   // no ptradd of a ptradd left
        for (long a : EDGES) {
            assertEquals(new Interpreter(plain, 1000).call("_f", List.of(a)), new Interpreter(m, 1000).call("_f", List.of(a)), printed);
        }
        // a constant second offset stays: the load folds it into its address
        String c = simplified("i16", "    %0 = and i16 %a, 7\n    %1 = ptradd @_g, i16 %0\n    %2 = ptradd %1, i16 3\n"
                + "    %3 = ptrtoint ptr %2 to i32\n    ret i32 %3\n", "global @_g : 16\n\n");
        assertTrue(c.contains("ptradd %"), c);
    }

    @Test
    void c10ShiftsAndMasksThatKeepEveryBitAreTheValue() {
        // (a >> 4) * 16 + (a & 15) is a
        String s = simplified("i16", "    %0 = lshr i16 %a, 4\n    %1 = mul i16 %0, 16\n    %2 = and i16 %a, 15\n"
                + "    %3 = add i16 %1, %2\n    %4 = zext i16 %3 to i32\n    ret i32 %4\n");
        assertFalse(s.contains("lshr") || s.contains("mul") || s.contains("add"), s);
        // (a >> 3) << 3 is a & -8; with | and the other mask, a
        s = simplified("i16", "    %0 = lshr i16 %a, 3\n    %1 = shl i16 %0, 3\n    %2 = and i16 %a, 7\n"
                + "    %3 = or i16 %2, %1\n    %4 = zext i16 %3 to i32\n    ret i32 %4\n");
        assertFalse(s.contains("lshr") || s.contains("shl") || s.contains("or "), s);
        s = simplified("i16", "    %0 = ashr i16 %a, 2\n    %1 = shl i16 %0, 2\n    %2 = zext i16 %1 to i32\n    ret i32 %2\n");
        assertTrue(s.contains("and i16 %a, -4"), s);
        // other shift counts and masks that overlap stay
        s = simplified("i16", "    %0 = lshr i16 %a, 4\n    %1 = mul i16 %0, 8\n    %2 = zext i16 %1 to i32\n    ret i32 %2\n");
        assertTrue(s.contains("lshr") && s.contains("mul"), s);
        s = simplified("i16", "    %0 = and i16 %a, 12\n    %1 = and i16 %a, -8\n    %2 = add i16 %0, %1\n"
                + "    %3 = zext i16 %2 to i32\n    ret i32 %3\n");
        assertTrue(s.contains("add"), s);
    }
}
