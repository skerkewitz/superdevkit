package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A comparison two branches in other blocks use is computed next to each of them. */
class SinkComparesTest {
    private static final String PROGRAM = """
            func @_main(i16 %a) -> i16 {
            b0_entry:
                %0 = cmp slt i16 %a, 0
                br %0, b1_neg, b2_pos
            b1_neg:
                %1 = sub i16 0, %a
                jump b3_join
            b2_pos:
                jump b3_join
            b3_join:
                %2 = phi i16 [b1_neg, %1], [b2_pos, %a]
                br %0, b4_left, b5_right
            b4_left:
                %3 = add i16 %2, 100
                ret i16 %3
            b5_right:
                ret i16 %2
            }
            """;

    @Test
    void aComparisonIsComputedAtEachBranchThatUsesIt() {
        MModule plain = MirParser.parse(PROGRAM);
        MModule m = MirParser.parse(PROGRAM);
        MFunction f = m.function("_main");
        assertTrue(SinkCompares.run(f));
        Verifier.verify(m);
        for (Block b : f.blocks()) {
            if (b.terminator() instanceof Instr.Branch br) {
                assertTrue(br.op(0) instanceof Instr.Cmp c && c.block() == b, "the branch of " + b + " tests a comparison of its own block");
            }
        }
        assertFalse(SinkCompares.run(f), "nothing more to do");
        for (long a : new long[] { 0, 1, 5, 32767, 32768, 65535 }) {
            assertEquals(new Interpreter(plain, 1000).call("_main", List.of(a)), new Interpreter(m, 1000).call("_main", List.of(a)));
        }
    }
}
