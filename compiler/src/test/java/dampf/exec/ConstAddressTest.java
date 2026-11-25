package dampf.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pointers made from numbers, as for hardware registers ({@code REG8(0x210B + i)}): the code
 * generator addresses them directly (long addressing, with X for an index), not through
 * dampf_ptr. Such a program cannot run on the PC, so it runs in dampf's emulator only, on WRAM
 * addresses, and the values it reads back are compared with what C says.
 */
class ConstAddressTest {
    @TempDir
    Path tmp;

    private static final String PROGRAM = """
            #include <stdint.h>
            #define MEM8(a) (*(volatile uint8_t *)(a))
            #define MEM16(a) (*(volatile uint16_t *)(a))

            uint8_t read_far, read_low[4], idx;
            uint16_t read_word;

            static void put(uint8_t i, uint8_t v)
            {
                MEM8(0x1E40 + i) = v;           /* bank 0, the WRAM mirror below $2000 */
            }

            void test_main(void)
            {
                uint8_t i;
                MEM8(0x7F8000) = 77;            /* a long address in bank $7F */
                MEM16(0x7F8002) = 0x1234;
                for (i = 0; i < 4; i++) {
                    put(i, (uint8_t)(i * 10 + 5));
                }
                idx = 2;
                put(idx, 99);
                read_far = MEM8(0x7F8000);
                read_word = MEM16(0x7F8002);
                for (i = 0; i < 4; i++) {
                    read_low[i] = MEM8(0x7E1E40 + i);   /* the same bytes, read through bank $7E */
                }
            }
            """;

    @Test
    void constantAddressesAreAddressedDirectly() throws Exception {
        Path src = tmp.resolve("ca.c");
        Files.writeString(src, PROGRAM);
        Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                tmp.resolve("ca.sfc"), Map.of(), Compiler.Backend.MIR));
        String asm = String.join("\n", r.assembly());
        assertTrue(asm.contains("f:$7F8000"), "a long address for the constant pointer");
        assertTrue(asm.contains("f:$001E40,x"), "a long address indexed with X");
        assertFalse(asm.contains("[dampf_ptr]"), "no access through dampf_ptr");
        Map<String, Long> v = new EmulatorRunner().run(r.rom(), Observed.of(r.program().globals())).values();
        assertEquals(77L, v.get("read_far"));
        assertEquals(0x1234L, v.get("read_word"));
        assertEquals(5L, v.get("read_low[0]"));
        assertEquals(15L, v.get("read_low[1]"));
        assertEquals(99L, v.get("read_low[2]"));
        assertEquals(35L, v.get("read_low[3]"));
    }

    /**
     * A register pair as the SNES platform's sdevkit_bg_set_scroll writes it: h = 0x210D + 2 * n, then
     * REG8(h) and REG8(h + 1). h is the sum of one constant address and the index of the other,
     * so it must be computed (the code generator once folded it away and crashed), and the access
     * through h must index with h: indexing with n << 1 read a place the shift was never stored
     * in, since it was handed to the sum in A (found by the place check of B2). The numbers come
     * from tables, so the calls are not folded into constants.
     */
    @Test
    void aSumThatIsAlsoAnIndexIsComputed() throws Exception {
        Path src = tmp.resolve("pair.c");
        Files.writeString(src, """
                #include <stdint.h>
                #define MEM8(a) (*(volatile uint8_t *)(a))

                uint8_t read_low[6];
                uint8_t which[3] = {0, 2, 1};
                uint16_t what[3] = {0x1234, 0xABCD, 0x5678};

                static void put2(uint8_t n, uint16_t x)
                {
                    uint16_t h = (uint16_t)(0x1E40 + (n << 1));
                    MEM8(h) = (uint8_t)x;
                    MEM8(h + 1) = (uint8_t)(x >> 8);
                }

                void test_main(void)
                {
                    uint8_t i;
                    for (i = 0; i < 3; i++) {
                        put2(which[i], what[i]);
                    }
                    for (i = 0; i < 6; i++) {
                        read_low[i] = MEM8(0x7E1E40 + i);
                    }
                }
                """);
        Compiler.Result r = Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                tmp.resolve("pair.sfc"), Map.of(), Compiler.Backend.MIR));
        Map<String, Long> v = new EmulatorRunner().run(r.rom(), Observed.of(r.program().globals())).values();
        long[] want = {0x34, 0x12, 0x78, 0x56, 0xCD, 0xAB};
        for (int k = 0; k < want.length; k++) {
            assertEquals(want[k], v.get("read_low[" + k + "]"), "read_low[" + k + "]");
        }
    }
}
