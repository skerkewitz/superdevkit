package dampf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The tests of the dev kit's tools for game developers (tools/, Python for now), in compiler/src/test/python. */
class ToolsTest {

    @Test
    void png2snes() throws IOException, InterruptedException {
        python("compiler/src/test/python/test_png2snes.py");
    }

    @Test
    void sdevkitRename() throws IOException, InterruptedException {
        python("compiler/src/test/python/test_sdevkit_rename.py");
    }

    private static void python(String test) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("python3", "-I", test).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        assertEquals(0, p.exitValue(), out);
    }
}
