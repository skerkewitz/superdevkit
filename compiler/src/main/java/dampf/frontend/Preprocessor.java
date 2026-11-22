package dampf.frontend;

import dampf.frontend.pp.PpException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Preprocesses one source file with dampf's own preprocessor ({@link dampf.frontend.pp.Preprocess}),
 * a subset of C99's (ADR-6FD4GDN5): host headers never reach the code, dampf's own {@code include/}
 * directory provides {@code <stdint.h>} and the other headers, {@code __DAMPF__} is defined so
 * headers can tell the dampf build apart, and line markers in the text map every token back to its
 * file and line.
 */
public final class Preprocessor {
    private final Path dampfInclude;
    private final List<Path> includeDirs;
    private final List<String> defines;

    public Preprocessor(Path dampfInclude, List<Path> includeDirs, List<String> defines) {
        this.dampfInclude = dampfInclude;
        this.includeDirs = List.copyOf(includeDirs);
        this.defines = List.copyOf(defines);
    }

    /** Preprocesses {@code source}; an error is a CompileError at its place, like any other. */
    public String run(Path source) throws IOException, CompileError {
        try {
            return new dampf.frontend.pp.Preprocess(dampfInclude, includeDirs, defines).run(source);
        } catch (PpException e) {
            throw new CompileError(new SourcePos(e.file(), e.line(), e.col()), e.getMessage());
        }
    }
}
