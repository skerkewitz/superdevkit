package dampf.lir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The place check (B2) on hand-written functions whose operands name their owners. */
class PlaceCheckTest {
    /** Two values and a parameter: the owners' keys. */
    private static final Object P = "p";
    private static final Object V = "v";
    private static final Object W = "w";
    private static final Set<Object> OWN = Set.of(P, V, W);

    private static LOperand.Mem place(int at, Object key) {
        return new LOperand.Mem(LOperand.Width.DP, LOperand.FRAME, at, LOperand.Index.NONE, false,
                new LOperand.Owner(key, false, 0));
    }

    private static LInstr ins(String m, LOperand op) {
        return new LInstr(m, op, null, true, true, false);
    }

    private static LFunction function(LItem... items) {
        LFunction f = new LFunction(new LDirective(LDirective.Kind.PROC, null, List.of("f"), null, ".proc f"));
        List<LItem> all = new ArrayList<>(List.of(items));
        all.add(ins("rtl", null));
        f.rebuild(all);
        return f;
    }

    private static List<String> check(LFunction f) {
        return PlaceCheck.check(f, PlaceCheck.of(OWN), o -> o.key() == P);
    }

    @Test
    void aValueWrittenThenReadIsFine() {
        LFunction f = function(
                ins("lda", place(0, P)),
                ins("sta", place(2, V)),
                ins("ldx", place(2, V)));
        assertEquals(List.of(), check(f));
    }

    @Test
    void aReadOfAPlaceNeverWrittenIsFound() {
        // the index of REG8(0x1E40 + (n << 1)) read from the place of n << 1, which was handed
        // to the sum in A and never stored (ConstAddressTest)
        LFunction f = function(
                ins("lda", place(0, P)),
                ins("asl", new LOperand.Acc()),
                ins("ldx", place(2, V)));
        List<String> errors = check(f);
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("where it was not written on every way"), errors.getFirst());
    }

    @Test
    void aReadWrittenOnOneWayOnlyIsFound() {
        LFunction f = function(
                ins("lda", place(0, P)),
                ins("beq", new LOperand.Label("skip")),
                ins("sta", place(2, V)),
                new LLabel("skip"),
                ins("ldx", place(2, V)));
        assertEquals(2, check(f).size());
    }

    private static List<String> written(LFunction f) {
        return PlaceCheck.written(f, PlaceCheck.of(OWN), o -> o.key() == P);
    }

    @Test
    void beforeTheAddressesAPhiCopyReadsItsSourceAndWritesItsTarget() {
        LFunction fine = function(
                ins("lda", place(0, P)),
                ins("sta", place(0, V)),
                new LCopy(place(0, W), place(0, V)),
                ins("ldy", place(0, W)));
        assertEquals(List.of(), written(fine));
        LFunction wrong = function(
                new LCopy(place(0, W), place(0, V)),
                ins("ldy", place(0, W)));
        List<String> errors = written(wrong);
        assertEquals(2, errors.size(), errors.toString());   // the two bytes of V; W counts as written
        assertTrue(errors.getFirst().contains("where it was not written on every way"), errors.getFirst());
    }

    @Test
    void beforeTheAddressesPlacesAtOneOffsetAreNoOverlap() {
        // selection's offsets are not the final addresses: only the second check compares them
        LFunction f = function(
                ins("lda", place(0, P)),
                ins("sta", place(2, V)),
                ins("sta", place(2, W)),
                ins("ldx", place(2, V)),
                ins("ldy", place(2, W)));
        assertEquals(List.of(), written(f));
    }

    @Test
    void beforeTheAddressesAReadOfAPlaceNeverWrittenIsFound() {
        // the bug of exec/regress_pointer_index_sum.c: the index handed on in A, read from its place
        LFunction f = function(
                ins("lda", place(0, P)),
                ins("ldy", place(2, V)));
        assertEquals(2, written(f).size());
    }

    @Test
    void aWriteOverALiveValueIsFound() {
        LFunction f = function(
                ins("lda", place(0, P)),
                ins("sta", place(2, V)),
                ins("lda", new LOperand.Imm("$0001")),
                ins("sta", place(2, W)),
                ins("lda", place(2, W)),
                ins("ldx", place(2, V)));
        List<String> errors = check(f);
        assertTrue(errors.stream().anyMatch(e -> e.contains("which is live")), errors.toString());
    }

    @Test
    void aCopyBetweenValuesThatShareAPlaceIsFine() {
        // w = v in v's place: what is stored is what is there already
        LFunction f = function(
                ins("lda", place(0, P)),
                ins("sta", place(2, V)),
                ins("lda", place(2, V)),
                ins("sta", place(2, W)),
                ins("ldx", place(2, V)),
                ins("ldy", place(2, W)));
        assertEquals(List.of(), check(f));
    }

    @Test
    void aMarkedCopyIsFine() {
        // an in-place step: w = v + 1 written over v's place, the mark says w takes v's bytes first
        LFunction f = function(
                ins("lda", place(0, P)),
                ins("sta", place(2, V)),
                new LMark(List.of(new LMark.Copy(new LOperand.Owner(W, false, 0), new LOperand.Owner(V, false, 0), 2))),
                ins("inc", place(2, W)),
                ins("ldx", place(2, W)));
        assertEquals(List.of(), check(f));
    }
}
