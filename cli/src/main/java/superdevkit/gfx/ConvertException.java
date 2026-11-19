package superdevkit.gfx;

/** A picture the converter cannot take, with the reason. */
public final class ConvertException extends Exception {
    public ConvertException(String message) {
        super(message);
    }
}
