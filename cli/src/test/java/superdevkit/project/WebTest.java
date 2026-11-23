package superdevkit.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the target web looks for emcc on Windows, where cmd /c would start for any name. */
class WebTest {
    @Test
    void emccIsFoundInAFolderOfThePathWithTheExtensionsOfWindows(@TempDir Path dir) throws IOException {
        Path empty = Files.createDirectory(dir.resolve("empty"));
        Path tools = Files.createDirectory(dir.resolve("with space"));
        Files.writeString(tools.resolve("emcc.bat"), "");
        assertTrue(Web.found("emcc", empty + File.pathSeparator + "\"" + tools + "\""));
        assertFalse(Web.found("emcc", empty.toString()));
        assertFalse(Web.found("emcc", ""));
        assertFalse(Web.found("emcc", null));
    }

    @Test
    void aPathNamedInEmccIsTakenAsItIs(@TempDir Path dir) throws IOException {
        Path emcc = Files.writeString(dir.resolve("emcc.bat"), "");
        assertTrue(Web.found(emcc.toString(), ""));
        assertTrue(Web.found(dir.resolve("emcc").toString(), ""));
        assertFalse(Web.found(dir.resolve("other").toString(), dir.toString()));
    }
}
