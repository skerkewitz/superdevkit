package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A block that only returns a φ is copied into its predecessors: a jump becomes the return, a
 * branch and a switch get a block per edge; every path returns the value it brought.
 */
class ReturnsTest {
    private static final String PROGRAM = """
            func @_main(i16 %a, i16 %b) -> i16 {
            b0_entry:
                %0 = cmp ult i16 %a, 10
                br %0, b5_ret, b1_more
            b1_more:
                %1 = and i16 %a, 3
                switch i16 %1, b4_other [0: b5_ret, 1: b2_one, 2: b5_ret]
            b2_one:
                %2 = add i16 %b, 7
                jump b5_ret
            b4_other:
                %3 = cmp eq i16 %b, 0
                br %3, b6_keep, b5_ret
            b6_keep:
                jump b5_ret
            b5_ret:
                %4 = phi i16 [b0_entry, 1], [b1_more, %a], [b1_more, %a], [b2_one, %2], [b4_other, %b], [b6_keep, 99]
                ret i16 %4
            }
            """;

    @Test
    void everyPathReturnsItsOwnValue() {
        MModule plain = MirParser.parse(PROGRAM);
        MModule m = MirParser.parse(PROGRAM);
        MFunction f = m.function("_main");
        assertTrue(Returns.duplicate(f));
        Verifier.verify(m);
        assertFalse(f.blocks().stream().anyMatch(b -> b.name().equals("b5_ret")), "the shared return is gone");
        assertTrue(f.blocks().stream().noneMatch(b -> b.instrs().stream().anyMatch(i -> i instanceof Instr.Phi)));
        for (long a : new long[] {0, 9, 10, 11, 12, 13, 40000}) {
            for (long b : new long[] {0, 3, 65535}) {
                assertEquals(new Interpreter(plain, 10_000).call("_main", List.of(a, b)),
                        new Interpreter(m, 10_000).call("_main", List.of(a, b)), "a=" + a + " b=" + b);
            }
        }
        assertFalse(Returns.duplicate(f), "nothing left to copy");
    }
}
