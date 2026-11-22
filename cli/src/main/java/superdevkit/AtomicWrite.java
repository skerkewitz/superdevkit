package superdevkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a generated file so that a reader sees either the old content or the new, never a file
 * half written: the text goes to a file of its own beside the target, which is then renamed over
 * it. The conversions into build/gen use it, since the compiler's tests convert the same example
 * from several test JVMs at once while others compile from it.
 */
public final class AtomicWrite {
    private AtomicWrite() {
    }

    public static void writeString(Path file, String text) throws IOException {
        // Not Files.createTempFile: its file is readable by its owner only, and the move would
        // carry that over to the target.
        Path tmp = file.resolveSibling("." + file.getFileName() + "." + ProcessHandle.current().pid() + "."
                + Thread.currentThread().threadId() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
