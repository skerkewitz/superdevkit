package dampf.lir;

/**
 * A label: {@code name:}, or an unnamed one ({@code :}, name ""), which {@code :+} and {@code :-}
 * refer to. {@code text} is the line as written (a data label may carry a comment).
 */
public record LLabel(String name, String text) implements LItem {
    public LLabel(String name) {
        this(name, name + ":");
    }

    public boolean unnamed() {
        return name.isEmpty();
    }
}
