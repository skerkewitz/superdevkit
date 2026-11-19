package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Multiplication and division by constants: the reduced MIR must give the same result as the
 * original for every 8-bit input, and for a spread of 16- and 32-bit inputs, at every constant
 * up to 300 and the powers of two.
 */
class StrengthReduceTest {
    private static final String[] OPS = {"mul", "udiv", "urem", "sdiv", "srem"};

    private static MModule function(String op, String type, long c) {
        return MirParser.parse("func @_f(" + type + " %x) -> " + type + " {\nb0_entry:\n    %0 = " + op + " " + type
                + " %x, " + c + "\n    ret " + type + " %0\n}\n");
    }

    private static void check(String type, long[] inputs, long[] constants) {
        MType t = MType.valueOf(type.toUpperCase(Locale.ROOT));
        for (String op : OPS) {
            for (long c : constants) {
                if (t.wrap(c) == 0 && !op.equals("mul")) {
                    continue;   // division by zero
                }
                MModule plain = function(op, type, t.signed(c));
                MModule reduced = function(op, type, t.signed(c));
                Optimizer.run(reduced, true);
                Interpreter a = new Interpreter(plain, Long.MAX_VALUE);
                Interpreter b = new Interpreter(reduced, Long.MAX_VALUE);
                for (long x : inputs) {
                    long want = a.call("_f", List.of(x));
                    long got = b.call("_f", List.of(x));
                    assertEquals(want, got, () -> op + " " + type + " " + t.signed(x) + ", " + t.signed(c) + ":\n" + Printer.print(reduced));
                }
            }
        }
    }

    private static long[] constants(int bits) {
        List<Long> l = new java.util.ArrayList<>();
        for (long c = 0; c <= 300; c++) {
            l.add(c);
        }
        for (int k = 0; k < bits; k++) {
            l.add(1L << k);
            l.add(-(1L << k));
            l.add((1L << k) - 1);
        }
        l.add(-3L);
        l.add(-12L);
        return l.stream().mapToLong(Long::longValue).toArray();
    }

    @Test
    void i8() {
        long[] all = new long[256];
        for (int i = 0; i < 256; i++) {
            all[i] = i;
        }
        check("i8", all, constants(8));
    }

    @Test
    void i16() {
        long[] inputs = {0, 1, 2, 3, 7, 8, 100, 255, 256, 1000, 0x7FFF, 0x8000, 0x8001, 0xFFFF, 0xFFFE, 0xFFF0, 0xC000, 12345, 54321};
        check("i16", inputs, constants(16));
    }

    @Test
    void i32() {
        long[] inputs = {0, 1, 2, 3, 7, 8, 100, 0xFFFF, 0x10000, 0x7FFFFFFFL, 0x80000000L, 0x80000001L, 0xFFFFFFFFL, 0xFFFFFFF0L, 123456789L, 3000000000L};
        check("i32", inputs, constants(32));
    }
}
