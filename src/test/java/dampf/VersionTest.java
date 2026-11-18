package dampf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** {@code dampf --version} prints the version that {@code build.gradle.kts} sets (ADR record 26). */
class VersionTest {

    @Test
    void theVersionIsTheOneTheBuildSets() throws IOException {
        String version = Main.version();
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?"), "not a version: " + version);
        assertTrue(Files.readString(Path.of("build.gradle.kts")).contains("version = \"" + version + "\""),
                "build.gradle.kts does not set version " + version);
    }
}
