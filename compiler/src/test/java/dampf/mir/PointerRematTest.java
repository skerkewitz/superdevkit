package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A pointer sum needed as a value only on a rare way gets its own copy there ({@link PointerRemat});
 * the program computes the same.
 */
class PointerRematTest {
    private static final String LOOP = """
            global @_items : 48
            global @_hits : 2
            func @_touch(ptr %p) -> void {
            b0_entry:
                %0 = load i16 %p
                %1 = add i16 %0, 1
                store i16 %p, %1
                ret
            }
            func @_run() -> void {
            b0_entry:
                jump b1_for
            b1_for:
                %0 = phi i16 [b0_entry, 0], [b4_step, %9]
                %1 = cmp ult i16 %0, 48
                br %1, b2_body, b5_end
            b2_body:
                %2 = ptradd @_items, i16 %0
                %3 = load i8 %2
                %4 = cmp eq i8 %3, 0
                br %4, b3_rare, b4_step
            b3_rare:
                %5 = ptradd %2, i16 2
                call void @_touch(ptr %5)
                jump b4_step
            b4_step:
                %9 = add i16 %0, 6
                jump b1_for
            b5_end:
                ret
            }
            """;

    @Test
    void aRareUseGetsItsOwnCopy() {
        MModule plain = MirParser.parse(LOOP);
        MModule remat = MirParser.parse(LOOP);
        MFunction f = remat.function("_run");
        assertTrue(PointerRemat.run(f));
        Verifier.verify(remat);
        // the call's operand is a copy defined in the call's block
        Instr call = f.blocks().stream().flatMap(b -> b.instrs().stream()).filter(i -> i instanceof Instr.Call)
                .findFirst().orElseThrow();
        assertEquals(call.block(), ((Instr) call.op(0)).block());
        Interpreter a = new Interpreter(plain, 1_000_000);
        Interpreter b = new Interpreter(remat, 1_000_000);
        a.call("_run", List.of());
        b.call("_run", List.of());
        for (long at = 0; at < 48; at += 2) {
            assertEquals(a.load(a.addressOf("_items") + (int) at, 2), b.load(b.addressOf("_items") + (int) at, 2));
        }
    }
}
