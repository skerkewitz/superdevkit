package dampf.emu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dampf.Compiler;
import dampf.ExamplesTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line of {@link ExampleCheck}: the checksums and the ROM's sound go into the files named. */
class ExampleCheckMainTest {
    @TempDir
    Path tmp;

    @Test
    void itWritesTheChecksumsAndTheSoundOfAGameWithEffects() throws Exception {
        Path example = Path.of("examples/steinschlag");
        Compiler.Result built = ExamplesTest.compile(example, tmp.resolve("game.sfc"));
        Path rom = built.rom().rom();
        Path check = example.resolve("check.txt");
        Path hashes = tmp.resolve("hashes.txt");
        Path sound = tmp.resolve("sound.txt");

        ExampleCheck.main(new String[] { rom.toString(), check.toString(), hashes.toString(), sound.toString() });

        ExampleCheck.Input input = ExampleCheck.parse(check);
        ExampleCheck.Result run = ExampleCheck.run(Files.readAllBytes(rom), input, 40 * input.ticks() + 2000);
        assertEquals(run.lines(), Files.readAllLines(hashes));
        List<String> written = Files.readAllLines(sound);
        assertEquals(run.sound(), written);
        assertFalse(written.isEmpty(), "a game with sound effects has DSP writes");
    }
}
