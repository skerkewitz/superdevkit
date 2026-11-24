package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.frontend.CompileError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A library's files ({@code --lib}) are compiled without the -D defines, with --sram's (ADR-6FKYXP8C). */
class LibraryDefinesTest {

    @Test
    void theDefinesReachTheProgramsFilesAndNotTheLibrarys(@TempDir Path dir) throws Exception {
        Path game = Files.writeString(dir.resolve("game.c"), """
                #if !defined(LEVELS) || LEVELS != 3 || SDEVKIT_SRAM_SIZE != 8
                #error the program's file did not get its defines
                #endif
                void lib_init(void);
                void main(void) { lib_init(); }
                """);
        Path lib = Files.writeString(dir.resolve("lib.c"), """
                #if defined(LEVELS) || SDEVKIT_SRAM_SIZE != 8
                #error the library got the program's defines, or not the save RAM's
                #endif
                void lib_init(void) { }
                """);
        Compiler.Options o = new Compiler.Options(List.of(game, lib), List.of(), List.of("LEVELS=3"), "main",
                dir.resolve("game.sfc")).withSram(8);
        assertEquals(List.of("LEVELS=3", "SDEVKIT_SRAM_SIZE=8"), o.withLibrary(List.of(lib)).definesFor(game));
        assertEquals(List.of("SDEVKIT_SRAM_SIZE=8"), o.withLibrary(List.of(lib)).definesFor(lib));
        Compiler.frontEnd(o.withLibrary(List.of(lib)));
        CompileError e = assertThrows(CompileError.class, () -> Compiler.frontEnd(o));
        assertTrue(e.diagnostics().getFirst().message().contains("the library got the program's defines"), e.toString());
    }
}
