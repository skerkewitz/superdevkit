package dampf.frontend;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** dampf's own system headers ({@code <stdint.h>}, {@code <dampf.h>}), copied from the classpath into a directory gcc can read. */
public final class SystemHeaders {
    private static final List<String> HEADERS = List.of("stdint.h", "dampf.h");
    private static Path dir;

    private SystemHeaders() {}

    public static synchronized Path directory() {
        if (dir == null) {
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
