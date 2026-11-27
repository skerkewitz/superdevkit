package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.frontend.CompileError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The IRQ's window (ADR-6FRX1GYC, ADR-6FVWK4JE): the game's IRQ handler's last PPU register write
 * after sdevkit_irq_wait_hblank must end within the horizontal blank's master cycles, or the build
 * fails. Built with the kit's lib/snes, as a game is.
 */
class IrqWindowTest {
    @TempDir
    Path tmp;

    private static final String PROGRAM = """
            #include <stdint.h>
            #include <dampf.h>
            #include "sdevkit/snes.h"
            #include "sdevkit/irq.h"
            uint16_t x;
            uint16_t y;
            DAMPF_INTERRUPT(irq) void game_irq(void)
            {
                DECLARE
                uint16_t a = x, b = y;
                sdevkit_irq_wait_hblank();
                WRITES
            }
            void test_main(void) { sdevkit_irq_line(100); }
            """;

    private Compiler.Result build(String writes) throws Exception {
        return build("", writes);
    }

    private Compiler.Result build(String declare, String writes) throws Exception {
        Path src = tmp.resolve("irq.c");
        Files.writeString(src, PROGRAM.replace("DECLARE", declare).replace("WRITES", writes));
        List<Path> sources = new ArrayList<>(List.of(src));
        try (Stream<Path> s = Files.list(Path.of("lib/snes"))) {
            s.filter(p -> p.toString().endsWith(".c") && !p.getFileName().toString().equals("main.c")).sorted()
                    .forEach(sources::add);
        }
        return Compiler.compile(new Compiler.Options(sources, List.of(Path.of("lib/include"), Path.of("lib/snes")),
                List.of(), "test_main", tmp.resolve("irq.sfc")).withBackend(Compiler.Backend.MIR));
    }

    @Test
    void oneScrollRegisterFits() throws Exception {
        build("sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, a);");
        String report = Files.readString(tmp.resolve("irq.timing.txt"));
        // TYA, SEP, STA long, REP, TXA, SEP, STA long: 23 CPU cycles, 138 master cycles from FastROM
        assertTrue(report.contains("158          138  _game_irq   (after the wait; room, worst)"), report);
    }

    @Test
    void declaredHdmaTakesFromTheWindow() throws Exception {
        // the same handler fits without HDMA, but not with a channel of one byte on its line:
        // 18 + 16 + 8 = 42 of the 158 leave 116
        CompileError e = assertThrows(CompileError.class, () -> build("SDEVKIT_IRQ_HDMA(SDEVKIT_HDMA_CHANNEL1, 1);",
                "sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, a);"));
        String msg = e.diagnostics().getFirst().message();
        assertTrue(msg.contains("'game_irq' ends its last register write up to 138 master cycles after"
                + " sdevkit_irq_wait_hblank returns, over the 116 the horizontal blank leaves"), msg);
        assertTrue(msg.contains("158 less the 42 of the HDMA it declares (SDEVKIT_IRQ_HDMA: channels $02, 1 bytes)"), msg);
        assertEquals(42, dampf.backend.IrqWindow.hdma(0x02, 1));
        assertEquals(0, dampf.backend.IrqWindow.hdma(0, 0));
    }

    @Test
    void theDeclarationBelongsInTheIrqHandler() throws Exception {
        Path src = tmp.resolve("irq.c");
        Files.writeString(src, PROGRAM.replace("DECLARE", "").replace("WRITES", "")
                .replace("void test_main(void) {", "void test_main(void) { SDEVKIT_IRQ_HDMA(2, 1);"));
        CompileError e = assertThrows(CompileError.class, () -> Compiler.compile(new Compiler.Options(List.of(src),
                List.of(Path.of("lib/include"), Path.of("lib/snes")), List.of(), "test_main", tmp.resolve("irq.sfc"))
                .withBackend(Compiler.Backend.MIR)));
        assertTrue(e.diagnostics().getFirst().message().contains("SDEVKIT_IRQ_HDMA belongs in the IRQ handler"),
                e.diagnostics().getFirst().message());
    }

    @Test
    void writesTooLateFailTheBuild() throws Exception {
        CompileError e = assertThrows(CompileError.class, () -> build(
                "sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, a);\n"
                + "sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, b);\n"
                + "sdevkit_reg_write_twice(SDEVKIT_PPU_BG2HOFS, a);"));
        String msg = e.diagnostics().getFirst().message();
        assertTrue(msg.matches("'game_irq' ends its last register write up to \\d+ master cycles after sdevkit_irq_wait_hblank"
                + " returns, over the 158 the horizontal blank leaves.*"), msg);
        assertTrue(msg.contains("without HDMA on the line, which SDEVKIT_IRQ_HDMA would declare"), msg);
        assertEquals(7, e.diagnostics().getFirst().pos().line());
    }

    @Test
    void aWriteAfterALoopHasNoBound() throws Exception {
        CompileError e = assertThrows(CompileError.class, () -> build(
                "while (y != 0) { y--; }\nsdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, a);"));
        assertTrue(e.diagnostics().getFirst().message().contains("'game_irq' has a loop between sdevkit_irq_wait_hblank and a"
                + " register write"), e.diagnostics().getFirst().message());
    }

    @Test
    void workAfterTheLastWriteIsFree() throws Exception {
        build("sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, a);\nwhile (y != 0) { y--; }\nsdevkit_irq_line(150);");
    }
}
