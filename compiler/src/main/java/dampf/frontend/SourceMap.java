package dampf.frontend;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps lines of preprocessed text back to the original files, using the line markers
 * gcc -E writes ({@code # 142 "bubble.c" 1}).
 */
public final class SourceMap {
    private static final Pattern MARKER = Pattern.compile("^#\\s*(?:line\\s+)?(\\d+)\\s+\"((?:[^\"\\\\]|\\\\.)*)\".*$");

    private final String[] files;
    private final int[] lines;

    private SourceMap(String[] files, int[] lines) {
        this.files = files;
        this.lines = lines;
    }

    /** Builds the map for preprocessed text; {@code fallbackFile} names text before the first marker. */
    public static SourceMap of(String preprocessed, String fallbackFile) {
        String[] textLines = preprocessed.split("\n", -1);
        String[] files = new String[textLines.length + 1];
        int[] lines = new int[textLines.length + 1];
        String file = fallbackFile;
        int line = 1;
        for (int i = 0; i < textLines.length; i++) {
            Matcher m = MARKER.matcher(textLines[i]);
            files[i + 1] = file;
            lines[i + 1] = line;
            if (m.matches()) {
                line = Integer.parseInt(m.group(1));
                file = m.group(2).replace("\\\\", "\\").replace("\\\"", "\"");
            } else {
                line++;
            }
        }
        return new SourceMap(files, lines);
    }

    /** The original position of a line (1-based) and column (0-based, as ANTLR counts) of preprocessed text. */
    public SourcePos map(int ppLine, int ppColumn) {
        int i = Math.clamp(ppLine, 1, lines.length - 1);
        return new SourcePos(files[i], lines[i], ppColumn + 1);
    }

    @Override
    public String toString() {
        return "SourceMap" + Arrays.toString(Arrays.copyOf(files, Math.min(files.length, 4))) + "...";
    }
}
