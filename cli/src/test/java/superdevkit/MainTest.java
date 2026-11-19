package superdevkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/** The command's version is the kit's, which dampf has too. */
class MainTest {
    @Test
    void versionIsTheKits() {
        PrintStream old = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            Main.run(new String[] { "--version" });
        } finally {
            System.setOut(old);
        }
        assertTrue(out.toString().equals("superdevkit " + dampf.Main.version() + System.lineSeparator()), out.toString());
    }
}
