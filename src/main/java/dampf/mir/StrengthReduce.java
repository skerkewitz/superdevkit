package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Multiplication and division by constants (optimization I). The 65816 has no multiply
 * instruction in the CPU; dampf's runtime multiplies in a loop of 16 or 32 steps (about 300 and
 * 1,250 cycles). Most multiplications in game code are by a constant: the size of a struct when
 * indexing an array of structs.
 *
 * <ul>
 * <li>{@code mul x, c}: c in non-adjacent form (signed binary digits, no two neighbours nonzero:
 *     12 = 8 + 4, 7 = 8 - 1), one shift and one add or subtract per digit; up to
 *     {@link #MAX_DIGITS} digits. 0 and 1 are trivial.</li>
 * <li>{@code udiv x, 2^k} is {@code lshr x, k}; {@code urem x, 2^k} is {@code and x, 2^k - 1}.</li>
 * <li>{@code sdiv x, 2^k} rounds towards zero, as C does: negative x first get 2^k - 1 added
 *     ({@code (x ashr (bits-1)) lshr (bits-k)}), then {@code ashr k}. {@code srem x, 2^k} is
 *     x minus that quotient times 2^k.</li>
 * </ul>
 * Every rule is exact for all inputs (modulo 2^bits, as MIR's arithmetic is).
 */
public final class StrengthReduce {
    /** More digits than this: the runtime multiplication is cheaper or about the same. */
    static final int MAX_DIGITS = 4;

    private final Map<Instr, Value> replaced = new HashMap<>();

    private StrengthReduce() {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        StrengthReduce s = new StrengthReduce();
        for (Block b : f.blocks()) {
            for (Instr i : List.copyOf(b.instrs())) {
                for (int k = 0; k < i.operands().size(); k++) {
                    Value v = i.op(k);
                    while (v instanceof Instr d && s.replaced.containsKey(d)) {
                        v = s.replaced.get(d);
                    }
                    if (v != i.op(k)) {
                        i.setOp(k, v);
                    }
                }
                if (i instanceof Instr.Bin bin) {
                    Value r = s.reduce(bin);
                    if (r != null) {
                        s.replaced.put(bin, r);
                    }
                }
            }
        }
        DeadCode.replaceUses(f, s.replaced);
        return !s.replaced.isEmpty();
    }

    private Value reduce(Instr.Bin b) {
        MType t = b.type();
        int bits = t.bits();
        Value x = b.op(0);
        Value y = b.op(1);
        if (b.op == Instr.BinOp.MUL && x instanceof Value.Const && !(y instanceof Value.Const)) {
            Value s = x;
            x = y;
            y = s;
        }
        if (!(y instanceof Value.Const k) || x instanceof Value.Const) {
            return null;
        }
        long c = k.bits();
        int log = c != 0 && (c & (c - 1)) == 0 ? Long.numberOfTrailingZeros(c) : -1;
        return switch (b.op) {
            // 8 and 16 bits: the code generator multiplies by a constant in A itself (shifts and
            // adds, Horner's way), and keeps the exact value range of the product in MIR
            case MUL -> t == MType.I32 ? multiply(b, x, c) : null;
            case UDIV -> log < 0 ? null : log == 0 ? x : bin(b, Instr.BinOp.LSHR, x, new Value.Const(t, log));
            case UREM -> log < 0 ? null : bin(b, Instr.BinOp.AND, x, new Value.Const(t, c - 1));
            case SDIV -> log < 0 || log > bits - 2 ? null : log == 0 ? x : bin(b, Instr.BinOp.ASHR, roundedForShift(b, x, log), new Value.Const(t, log));
            case SREM -> {
                if (log < 0 || log > bits - 2) {
                    yield null;
                }
                if (log == 0) {
                    yield new Value.Const(t, 0);
                }
                // x - ((x + bias) & -2^k)
                Value multiple = bin(b, Instr.BinOp.AND, roundedForShift(b, x, log), new Value.Const(t, -(1L << log)));
                yield bin(b, Instr.BinOp.SUB, x, multiple);
            }
            default -> null;
        };
    }

    /** x + (2^k - 1 if x is negative, else 0), so an arithmetic shift by k rounds towards zero. */
    private Value roundedForShift(Instr.Bin at, Value x, int k) {
        MType t = at.type();
        Value sign = bin(at, Instr.BinOp.ASHR, x, new Value.Const(t, t.bits() - 1));     // 0 or -1
        Value bias = bin(at, Instr.BinOp.LSHR, sign, new Value.Const(t, t.bits() - k));  // 0 or 2^k - 1
        return bin(at, Instr.BinOp.ADD, x, bias);
    }

    private Value multiply(Instr.Bin b, Value x, long c) {
        MType t = b.type();
        if (c == 0) {
            return new Value.Const(t, 0);
        }
        List<long[]> digits = naf(c, t.bits());
        if (digits.size() > MAX_DIGITS) {
            return null;
        }
        Value acc = null;
        for (int d = digits.size() - 1; d >= 0; d--) {     // the highest digit first
            int shift = (int) digits.get(d)[0];
            boolean plus = digits.get(d)[1] > 0;
            Value term = shift == 0 ? x : bin(b, Instr.BinOp.SHL, x, new Value.Const(t, shift));
            if (acc == null) {
                acc = plus ? term : bin(b, Instr.BinOp.SUB, new Value.Const(t, 0), term);
            } else {
                acc = bin(b, plus ? Instr.BinOp.ADD : Instr.BinOp.SUB, acc, term);
            }
        }
        return acc == null ? new Value.Const(t, 0) : acc;
    }

    /**
     * The non-adjacent form of c: (position, +1 or -1) from the lowest digit up; digits at
     * positions of {@code bits} and above are left out (they are 0 modulo 2^bits).
     */
    public static List<long[]> naf(long c, int bits) {
        List<long[]> digits = new ArrayList<>();
        for (int k = 0; c != 0 && k < bits; k++) {
            if ((c & 1) != 0) {
                long d = 2 - (c & 3);   // 1 or -1
                digits.add(new long[] {k, d});
                c -= d;
            }
            c >>>= 1;
        }
        return digits;
    }

    private static Instr bin(Instr at, Instr.BinOp op, Value a, Value b) {
        Instr i = new Instr.Bin(op, a, b, at.pos());
        at.block().insertBefore(at, i);
        return i;
    }
}
