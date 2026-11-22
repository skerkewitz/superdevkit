package dampf.frontend;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * dampf's own system headers ({@code <stdint.h>}, {@code <dampf.h>}, {@code <string.h>}) in a
 * directory on disk. The launcher names the distribution's {@code include/} directory in the
 * property {@code dampf.include}, and the tests name the one in the sources, so that the debug
 * file and the assembly name a header that exists and the same path on every run; without the
 * property they are copied from the class path into a temporary directory.
 */
public final class SystemHeaders {
    private static final List<String> HEADERS = List.of("stdint.h", "dampf.h", "string.h");
    private static Path dir;

    private SystemHeaders() {}

    public static synchronized Path directory() {
        if (dir == null) {
            String given = System.getProperty("dampf.include");
            if (given != null && HEADERS.stream().allMatch(h -> Files.isRegularFile(Path.of(given, h)))) {
                dir = Path.of(given).toAbsolutePath();
                return dir;
            }
            try {
                Path d = Files.createTempDirectory("dampf-include");
                for (String h : HEADERS) {
                    try (InputStream in = SystemHeaders.class.getResourceAsStream("/dampf/include/" + h)) {
                        if (in == null) {
                            throw new IllegalStateException("missing resource /dampf/include/" + h);
                        }
                        Files.copy(in, d.resolve(h), StandardCopyOption.REPLACE_EXISTING);
                    }
                    d.resolve(h).toFile().deleteOnExit();
                }
                d.toFile().deleteOnExit();
                dir = d;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return dir;
    }
}
