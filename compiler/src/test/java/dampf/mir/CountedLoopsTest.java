package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The loop analysis every loop pass is to use (CountedLoops): the forms of the spelling
 * families, the loops it must refuse, and random loops whose claims the interpreter checks.
 */
class CountedLoopsTest {
    private static final Ranges.Range ANY = null;

    /** The function of the last {@link #only}. */
    private MFunction function;

    private CountedLoops.Loop only(String text) {
        MModule m = MirParser.parse(text);
        Verifier.verify(m);
        function = m.functions().getFirst();
        List<CountedLoops.Loop> loops = CountedLoops.find(function);
        assertEquals(1, loops.size());
        return loops.getFirst();
    }

    private CountedLoops.Oracle ranges() {
        return CountedLoops.of(new Ranges(function));
    }

    /** for (i = START; i PRED BOUND; i += STEP): the test in the head, on the φ. */
    private static String forLoop(String type, long start, String pred, String bound, long step) {
        return """
                func @_f(i16 %%n) -> i16 {
                b0_entry:
                    jump b1_for
                b1_for:
                    %%0 = phi %s [b0_entry, %d], [b2_body, %%2]
                    %%1 = cmp %s %s %%0, %s
                    br %%1, b2_body, b3_end
                b2_body:
                    %%2 = add %s %%0, %d
                    jump b1_for
                b3_end:
                    ret i16 0
                }
                """.formatted(type, start, pred, type, bound, type, step);
    }

    private static void interval(CountedLoops.Loop l, CountedLoops.Oracle o, Ranges.Range s, Ranges.Range u) {
        CountedLoops.Interval i = l.interval(l.counters().getFirst(), o);
        if (s != ANY) {
            assertEquals(s, i.signed());
        }
        if (u != ANY) {
            assertEquals(u, i.unsigned());
        }
    }

    private static Ranges.Range r(long lo, long hi) {
        return new Ranges.Range(lo, hi);
    }

    @Test
    void upToAConstant() {
        // a_u16_lt100, b_u8_lt100 (u8 below 128 and above: the bound cliffs)
        for (String t : new String[] {"i16", "i8"}) {
            CountedLoops.Loop l = only(forLoop(t, 0, "ult", "100", 1));
            assertEquals(1, l.counters().size());
            assertEquals(1, l.exits().size());
            CountedLoops.Exit e = l.exits().getFirst();
            assertFalse(e.stepped());
            assertEquals(Instr.Pred.ULT, e.stay());
            assertEquals(101, l.exactRuns());
            assertEquals(101, l.runs(CountedLoops.CONSTANTS));
            interval(l, CountedLoops.CONSTANTS, r(0, 100), r(0, 100));
        }
        CountedLoops.Loop l = only(forLoop("i8", 0, "ult", "200", 1));
        assertEquals(201, l.exactRuns());
        interval(l, CountedLoops.CONSTANTS, ANY, r(0, 200));
        assertNull(l.interval(l.counters().getFirst(), CountedLoops.CONSTANTS).signed());
        // c_i16_lt100: signed
        l = only(forLoop("i16", 0, "slt", "100", 1));
        interval(l, CountedLoops.CONSTANTS, r(0, 100), r(0, 100));
        // a step that does not divide the distance: 0, 3, ..., 99, 102
        l = only(forLoop("i16", 0, "ult", "100", 3));
        assertEquals(35, l.exactRuns());
        interval(l, CountedLoops.CONSTANTS, r(0, 102), r(0, 102));
    }

    @Test
    void notEqualToTheBound() {
        // d_u8_ne100, n_u16_ne100
        for (String t : new String[] {"i8", "i16"}) {
            CountedLoops.Loop l = only(forLoop(t, 0, "ne", "100", 1));
            assertEquals(Instr.Pred.NE, l.exits().getFirst().stay());
            assertEquals(101, l.exactRuns());
            interval(l, CountedLoops.CONSTANTS, r(0, 100), r(0, 100));
        }
        // g_u16_down, h_u8_down, *_down of the bound families: i > 0 is i != 0
        for (String t : new String[] {"i8", "i16"}) {
            CountedLoops.Loop l = only(forLoop(t, 100, "ne", "0", -1));
            assertEquals(101, l.exactRuns());
            interval(l, CountedLoops.CONSTANTS, r(0, 100), r(0, 100));
        }
        // an even step that meets the bound, and one that does not
        CountedLoops.Loop l = only(forLoop("i16", 0, "ne", "100", 4));
        assertEquals(26, l.exactRuns());
        l = only(forLoop("i16", 0, "ne", "101", 4));
        assertEquals(-1, l.exactRuns());
        assertEquals(CountedLoops.UNKNOWN, l.runs(CountedLoops.CONSTANTS));
        // from above the bound, a step of 1 goes round the type once: 250, ..., 255, 0, ..., 100
        l = only(forLoop("i8", 250, "ne", "100", 1));
        assertEquals(107, l.runs(CountedLoops.CONSTANTS));
        interval(l, CountedLoops.CONSTANTS, null, null);
        // an odd step meets every value: finite, but it wraps
        l = only(forLoop("i8", 0, "ne", "100", 3));
        assertEquals(256, l.runs(CountedLoops.CONSTANTS));
        assertEquals(-1, l.exactRuns());
        interval(l, CountedLoops.CONSTANTS, null, null);
    }

    @Test
    void downToAConstant() {
        // i16_down: for (i = 99; i >= 0; i--) is signed, sge 0
        CountedLoops.Loop l = only(forLoop("i16", 99, "sge", "0", -1));
        assertEquals(101, l.exactRuns());
        interval(l, CountedLoops.CONSTANTS, r(-1, 99), null);
        // unsigned down to 1: uge 1 is ne 0 in the canonical form, but a pass may write it
        l = only(forLoop("i16", 100, "uge", "5", -2));
        assertEquals(49, l.exactRuns());   // 100, 98, ..., 6, 4
        interval(l, CountedLoops.CONSTANTS, r(4, 100), r(4, 100));
    }

    @Test
    void aDoLoopComparesTheSteppedValue() {
        // i_u16_dowhile, fill_do
        CountedLoops.Loop l = only("""
                func @_f() -> i16 {
                b0_entry:
                    jump b1_do
                b1_do:
                    %0 = phi i16 [b0_entry, 0], [b1_do, %1]
                    %1 = add i16 %0, 1
                    %2 = cmp ult i16 %1, 100
                    br %2, b1_do, b2_end
                b2_end:
                    ret i16 0
                }
                """);
        CountedLoops.Exit e = l.exits().getFirst();
        assertTrue(e.stepped());
        assertEquals(100, l.exactRuns());
        interval(l, CountedLoops.CONSTANTS, r(0, 99), r(0, 99));
    }

    @Test
    void aZeroExtendedCounterAndABoundFromOutside() {
        // k_u8_var_bound after C7 (the bound loaded once), pairs: the inner loop from i + 1 to n
        CountedLoops.Loop l = only("""
                func @_f(i16 %n) -> i16 {
                b0_entry:
                    %9 = and i16 %n, 63
                    jump b1_for
                b1_for:
                    %0 = phi i8 [b0_entry, 0], [b2_body, %2]
                    %3 = zext i8 %0 to i16
                    %1 = cmp ult i16 %3, %9
                    br %1, b2_body, b3_end
                b2_body:
                    %2 = add i8 %0, 1
                    jump b1_for
                b3_end:
                    ret i16 0
                }
                """);
        CountedLoops.Exit e = l.exits().getFirst();
        assertTrue(e.widened());
        assertEquals(e.bound(), e.cmp().op(1));
        assertEquals(-1, l.exactRuns());
        CountedLoops.Oracle o = ranges();
        assertEquals(64, l.runs(o));
        interval(l, o, ANY, r(0, 63));
        // without the bound's range: any i16, which the byte cannot reach without wrapping
        assertEquals(CountedLoops.UNKNOWN, l.runs(CountedLoops.CONSTANTS));
    }

    @Test
    void aTestFurtherDownCountsAndOtherCountersFollowTheRuns() {
        // search: for (i = 0; i < 100; i++) { if (t[i] == x) return i; } has two ways out; and j
        // steps by 4 beside i
        CountedLoops.Loop l = only("""
                global @_t : 100

                func @_f(i8 %x) -> i16 {
                b0_entry:
                    jump b1_for
                b1_for:
                    %0 = phi i16 [b0_entry, 0], [b3_next, %5]
                    %1 = phi i16 [b0_entry, 8], [b3_next, %6]
                    %2 = cmp ult i16 %0, 100
                    br %2, b2_body, b4_end
                b2_body:
                    %3 = ptradd @_t, i16 %0
                    %4 = load i8 %3
                    %7 = cmp eq i8 %4, %x
                    br %7, b5_found, b3_next
                b3_next:
                    %5 = add i16 %0, 1
                    %6 = add i16 %1, 4
                    jump b1_for
                b4_end:
                    ret i16 -1
                b5_found:
                    ret i16 %1
                }
                """);
        assertEquals(2, l.counters().size());
        assertEquals(1, l.exits().size());   // the second test compares no counter
        assertEquals(2, l.exitEdges().size());
        assertEquals(-1, l.exactRuns());     // two ways out
        assertEquals(101, l.runs(CountedLoops.CONSTANTS));
        CountedLoops.Interval j = l.interval(l.counters().get(1), CountedLoops.CONSTANTS);
        assertEquals(r(8, 408), j.signed());
    }

    @Test
    void loopsItMustRefuse() {
        // a counter stepped twice
        CountedLoops.Loop l = only("""
                func @_f() -> i16 {
                b0_entry:
                    jump b1_for
                b1_for:
                    %0 = phi i16 [b0_entry, 0], [b2_body, %3]
                    %1 = cmp ult i16 %0, 100
                    br %1, b2_body, b3_end
                b2_body:
                    %2 = add i16 %0, 1
                    %3 = add i16 %2, 1
                    jump b1_for
                b3_end:
                    ret i16 %0
                }
                """);
        assertTrue(l.counters().isEmpty());
        assertTrue(l.exits().isEmpty());
        // a step in a branch
        l = only("""
                func @_f(i16 %x) -> i16 {
                b0_entry:
                    jump b1_for
                b1_for:
                    %0 = phi i16 [b0_entry, 0], [b4_join, %3]
                    %1 = cmp ult i16 %0, 100
                    br %1, b2_body, b5_end
                b2_body:
                    %6 = cmp ne i16 %x, 0
                    br %6, b3_step, b4_join
                b3_step:
                    %2 = add i16 %0, 1
                    jump b4_join
                b4_join:
                    %3 = phi i16 [b2_body, %0], [b3_step, %2]
                    jump b1_for
                b5_end:
                    ret i16 0
                }
                """);
        assertTrue(l.counters().isEmpty());
        // a bound read anew every round, and written
        l = only("""
                global @_n : 2

                func @_f() -> i16 {
                b0_entry:
                    jump b1_for
                b1_for:
                    %0 = phi i16 [b0_entry, 0], [b2_body, %2]
                    %3 = load i16 @_n
                    %1 = cmp ult i16 %0, %3
                    br %1, b2_body, b3_end
                b2_body:
                    store i16 @_n, %0
                    %2 = add i16 %0, 1
                    jump b1_for
                b3_end:
                    ret i16 0
                }
                """);
        // a bound written in the loop: what the test says holds still, as the bound lies in its
        // range (here any i16) every round, but it is no invariant
        assertEquals(1, l.exits().size());
        assertFalse(l.exits().getFirst().invariant(l));
        assertEquals(65536, l.runs(ranges()));
        // counters that wrap: every byte is at most 255; 254 + 2 wraps to 0; counting away
        for (String[] c : new String[][] {{"i8", "ule", "255", "1"}, {"i8", "ult", "255", "2"},
                {"i16", "ult", "100", "-1"}, {"i16", "uge", "0", "-1"}, {"i16", "eq", "7", "1"}}) {
            l = only(forLoop(c[0], 0, c[1], c[2], Long.parseLong(c[3])));
            assertEquals(1, l.exits().size(), String.join(" ", c));
            assertEquals(CountedLoops.UNKNOWN, l.runs(CountedLoops.CONSTANTS), String.join(" ", c));
            assertEquals(-1, l.exactRuns());
        }
        // a test that does not end every round (it is not passed on the way back through b3)
        l = only("""
                func @_f(i16 %x) -> i16 {
                b0_entry:
                    jump b1_head
                b1_head:
                    %0 = phi i16 [b0_entry, 0], [b2_a, %2], [b3_b, %2]
                    %2 = add i16 %0, 1
                    %4 = cmp ne i16 %x, 0
                    br %4, b2_a, b3_b
                b2_a:
                    %1 = cmp ult i16 %0, 100
                    br %1, b1_head, b5_end
                b3_b:
                    jump b1_head
                b5_end:
                    ret i16 0
                }
                """);
        assertEquals(1, l.counters().size());
        assertTrue(l.exits().isEmpty());
    }

    // ------------------------------------------------------------------ random loops

    /**
     * Random counting loops, in three shapes (test in the head on the φ, in the head on the
     * stepped value, in the latch on the stepped value), with any predicate, either side staying,
     * the counter on either side, possibly zero-extended, from constant or ranged starts to
     * constant or ranged bounds, or to a bound that changes every round. The interpreter runs each for several arguments: the head runs
     * at most as often as claimed (exactly when exactRuns says), and every counter's value in the
     * head lies in its intervals.
     */
    @Test
    void randomLoopsKeepTheirClaims() {
        Random rnd = new Random(20261120);
        String[] preds = {"eq", "ne", "ult", "ule", "ugt", "uge", "slt", "sle", "sgt", "sge"};
        long[] steps = {1, 1, 1, -1, -1, 2, -2, 3, -3, 4, 5, -7, 127, -128};
        int checked = 0;
        int bounded = 0;
        for (int n = 0; n < 2000; n++) {
            String t = rnd.nextInt(3) == 0 ? "i16" : "i8";
            boolean widened = rnd.nextInt(3) == 0;
            String bt = widened ? (t.equals("i8") ? "i16" : "i32") : t;
            int shape = rnd.nextInt(3);
            String pred = preds[rnd.nextInt(preds.length)];
            boolean staysIfTrue = rnd.nextBoolean();
            boolean counterLeft = rnd.nextInt(4) != 0;
            long step = steps[rnd.nextInt(steps.length)];
            int bits = t.equals("i8") ? 8 : 16;
            String start = rnd.nextInt(3) == 0 ? "%s0" : Long.toString(small(rnd, bits));
            int kind = rnd.nextInt(4);   // a constant, a parameter, or a bound that changes every round
            String bound = kind == 0 ? "%n0" : kind == 1 ? "%n1" : Long.toString(small(rnd, widened ? bits + 1 : bits));
            String text = randomLoop(t, bt, widened, shape, pred, staysIfTrue, counterLeft, step, start, bound,
                    rnd.nextInt(4), rnd.nextInt(4));
            MModule m = MirParser.parse(text);
            Verifier.verify(m);
            MFunction f = m.function("_f");
            List<CountedLoops.Loop> loops = CountedLoops.find(f);
            assertEquals(1, loops.size(), text);
            CountedLoops.Loop l = loops.getFirst();
            CountedLoops.Oracle o = CountedLoops.of(new Ranges(f));
            long runs = l.headRuns(o);
            long exact = l.exactRuns();
            Map<Instr.Phi, CountedLoops.Interval> intervals = new HashMap<>();
            for (CountedLoops.Counter k : l.counters()) {
                intervals.put(k.phi(), l.interval(k, o));
            }
            if (runs != CountedLoops.UNKNOWN) {
                bounded++;
            }
            for (long[] args : new long[][] {{0, 0}, {1, 7}, {5, 300}, {255, 65535}, {200, 1}, {77, 129}}) {
                long[] headRuns = {0};
                List<String> wrong = new ArrayList<>();
                Interpreter in = new Interpreter(m, 150_000);
                in.observe(new Interpreter.Observer() {
                    @Override
                    public void value(Instr i, long v) {
                        if (i.block() == l.head() && i instanceof Instr.Phi p) {
                            if (p == l.head().instrs().getFirst()) {
                                headRuns[0]++;
                            }
                            CountedLoops.Interval iv = intervals.get(p);
                            if (iv != null) {
                                long sv = p.type().signed(v);
                                long uv = v & ((1L << p.type().bits()) - 1);
                                if (iv.signed() != null && !iv.signed().contains(sv)
                                        || iv.unsigned() != null && !iv.unsigned().contains(uv)) {
                                    wrong.add(Printer.instr(p) + " = " + sv + " outside " + iv);
                                }
                            }
                        }
                    }
                });
                try {
                    in.call("_f", List.of(args[0], args[1]));
                } catch (IllegalStateException ex) {
                    // endless (or too long): only wrong if the claim says it is short
                    if (runs < 15_000) {
                        fail("claimed at most " + runs + " head runs, but it did not end, args " + args[0] + ", " + args[1]
                                + "\n" + text);
                    }
                    continue;
                }
                checked++;
                if (!wrong.isEmpty()) {
                    fail(wrong.getFirst() + ", args " + args[0] + ", " + args[1] + "\n" + text);
                }
                assertTrue(headRuns[0] <= runs, "claimed at most " + runs + " head runs, ran " + headRuns[0]
                        + ", args " + args[0] + ", " + args[1] + "\n" + text);
                if (exact >= 0) {
                    assertEquals(exact, headRuns[0], "exact runs, args " + args[0] + ", " + args[1] + "\n" + text);
                }
            }
        }
        assertTrue(checked > 5_000, "checked " + checked);
        assertTrue(bounded > 500, "bounded " + bounded);
    }

    /** A number that is often near the ends of the type or near 0. */
    private static long small(Random rnd, int bits) {
        long m = 1L << bits;
        return switch (rnd.nextInt(5)) {
            case 0 -> rnd.nextInt(8);
            case 1 -> m - 1 - rnd.nextInt(8);
            case 2 -> (m >> 1) - 4 + rnd.nextInt(8);
            default -> rnd.nextLong(Math.min(m, 400));
        } & (m - 1);
    }

    private static String randomLoop(String t, String bt, boolean widened, int shape, String pred, boolean staysIfTrue,
                                     boolean counterLeft, long step, String start, String bound, int startMask, int boundMask) {
        long[] masks = {1, 15, 127, 255};
        StringBuilder sb = new StringBuilder();
        sb.append("func @_f(").append(t).append(" %s, ").append(bt).append(" %n) -> i16 {\n");
        sb.append("b0_entry:\n");
        sb.append("    %s0 = and ").append(t).append(" %s, ").append(masks[startMask]).append('\n');
        sb.append("    %n0 = and ").append(bt).append(" %n, ").append(masks[boundMask]).append('\n');
        sb.append("    jump b1_head\n");
        String latch = shape == 2 ? "b1_head" : "b2_body";
        sb.append("b1_head:\n");
        sb.append("    %0 = phi ").append(t).append(" [b0_entry, ").append(start).append("], [").append(latch).append(", %2]\n");
        sb.append("    %9 = phi i32 [b0_entry, 0], [").append(latch).append(", %10]\n");
        // a bound that changes every round: the round's number, masked
        sb.append(bt.equals("i32") ? "    %t = add i32 %9, 0\n" : "    %t = trunc i32 %9 to " + bt + "\n");
        sb.append("    %n1 = and ").append(bt).append(" %t, ").append(masks[boundMask]).append('\n');
        if (shape != 0) {
            sb.append("    %2 = add ").append(t).append(" %0, ").append(step).append('\n');
            sb.append("    %10 = add i32 %9, 1\n");
        }
        String q = shape == 0 ? "%0" : "%2";
        if (widened) {
            sb.append("    %3 = zext ").append(t).append(' ').append(q).append(" to ").append(bt).append('\n');
            q = "%3";
        }
        String a = counterLeft ? q : bound;
        String b = counterLeft ? bound : q;
        sb.append("    %1 = cmp ").append(pred).append(' ').append(bt).append(' ').append(a).append(", ").append(b).append('\n');
        String stay = shape == 2 ? "b1_head" : "b2_body";
        sb.append("    br %1, ").append(staysIfTrue ? stay : "b3_end").append(", ").append(staysIfTrue ? "b3_end" : stay).append('\n');
        if (shape != 2) {
            sb.append("b2_body:\n");
            if (shape == 0) {
                sb.append("    %2 = add ").append(t).append(" %0, ").append(step).append('\n');
                sb.append("    %10 = add i32 %9, 1\n");
            }
            sb.append("    jump b1_head\n");
        }
        sb.append("b3_end:\n    ret i16 0\n}\n");
        return sb.toString();
    }

    @Test
    void theRandomLoopsAreWellFormed() {
        assertNotNull(MirParser.parse(randomLoop("i8", "i16", true, 2, "ult", true, false, -1, "%s0", "%n0", 1, 2)));
    }
}
