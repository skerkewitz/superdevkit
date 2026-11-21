package dampf.lir;

import java.util.List;

/**
 * A copy between two frame places that share their address, so it has no code (B2 of
 * docs/archive/compiler-flaws.md): a φ whose operand is in the φ's own place, a value stepped in
 * the place of the one it steps. {@link PlaceCheck} reads it as the copy it stands for; it is
 * taken out before the LIR passes and never printed.
 */
public record LMark(List<Copy> copies) implements LItem {
    /** {@code bytes} bytes of {@code from}'s place are now {@code to}'s, each from the byte its owner names on. */
    public record Copy(LOperand.Owner to, LOperand.Owner from, int bytes) {}
}
