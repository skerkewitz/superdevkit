package dampf.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dampf.lir.LDirective;
import dampf.lir.LFunction;
import dampf.lir.LInstr;
import dampf.lir.LItem;
import dampf.lir.LMark;
import dampf.lir.LOperand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The colouring of frame places on hand-written LIR whose operands name their owners. */
class PlaceColouringTest {
    private static final String P = "p";
    private static final String V = "v";
    private static final String W = "w";
    private static final String Q = "q";

    private static LOperand.Mem place(Object key) {
        return place(key, 0);
    }

    private static LOperand.Mem place(Object key, int word) {
        // the address selection wrote does not matter: the colouring reads the owners
        return new LOperand.Mem(LOperand.Width.DP, LOperand.FRAME, 0, LOperand.Index.NONE, false,
                new LOperand.Owner(key, false, word));
    }

    private static LInstr ins(String m, LOperand op) {
        return new LInstr(m, op, null, true, true, false);
    }

    private static Map<PlaceColouring.Place, Integer> colour(Map<Object, Integer> predicted, LItem... items) {
        LFunction f = new LFunction(new LDirective(LDirective.Kind.PROC, null, List.of("f"), null, ".proc f"));
        List<LItem> all = new ArrayList<>(List.of(items));
        all.add(ins("rtl", null));
        f.rebuild(all);
        PlaceColouring.Frame frame = new PlaceColouring.Frame() {
            @Override
            public Integer fixed(PlaceColouring.Place p) {
                return p.key() == P ? 0 : null;
            }

            @Override
            public int bytes(PlaceColouring.Place p) {
                return p.key() == Q ? 4 : 2;
            }

            @Override
            public Integer predicted(PlaceColouring.Place p) {
                return predicted.get(p.key());
            }
        };
        return PlaceColouring.colour(f, o -> Set.of(P, V, W, Q).contains(o.key()), frame, 2);
    }

    private static int at(Map<PlaceColouring.Place, Integer> m, Object key) {
        return m.get(new PlaceColouring.Place(key, false));
    }

    @Test
    void valuesLiveAtOnceGetTheirOwnPlaces() {
        var m = colour(Map.of(),
                ins("lda", place(P)), ins("sta", place(V)),
                ins("inc", new LOperand.Acc()), ins("sta", place(W)),
                ins("lda", place(V)), ins("adc", place(W)));
        assertEquals(0, at(m, P));
        assertEquals(2, at(m, V));
        assertEquals(4, at(m, W));
    }

    @Test
    void aValueDeadBeforeTheNextIsWrittenSharesItsPlace() {
        var m = colour(Map.of(),
                ins("lda", place(P)), ins("sta", place(V)),
                ins("lda", place(V)), ins("inc", new LOperand.Acc()), ins("sta", place(W)),
                ins("lda", place(W)));
        assertEquals(at(m, V), at(m, W));
    }

    @Test
    void aPredictionThatFitsIsKeptAndOneThatDoesNotIsNot() {
        var m = colour(Map.of(V, 8, W, 8),
                ins("lda", place(P)), ins("sta", place(V)),
                ins("inc", new LOperand.Acc()), ins("sta", place(W)),
                ins("lda", place(V)), ins("adc", place(W)));
        assertEquals(8, at(m, V));
        assertNotEquals(8, at(m, W));
    }

    @Test
    void aCopyMayShareExactlyTheSamePlaceButNotHalfOfIt() {
        // q (4 bytes) copied word by word to v and w; q's high word is still read after v is
        // written, so v may not take q's place; w copies q's low word where q is dead
        var m = colour(Map.of(Q, 2, V, 4, W, 2),
                ins("lda", place(P)), ins("sta", place(Q)), ins("sta", place(Q, 2)),
                ins("lda", place(Q)), ins("sta", place(V)),
                ins("lda", place(Q, 2)), ins("adc", place(V)),
                ins("lda", place(Q)), ins("sta", place(W)),
                ins("lda", place(W)));
        assertNotEquals(4, at(m, V));   // would overlap q's live high word
        assertEquals(2, at(m, W));      // exactly q's place: a copy
    }

    @Test
    void placesMarkedAsSharedAreOne() {
        var m = colour(Map.of(V, 2, W, 6),
                ins("lda", place(P)), ins("sta", place(V)),
                new LMark(List.of(new LMark.Copy(new LOperand.Owner(W, false, 0), new LOperand.Owner(V, false, 0), 2))),
                ins("inc", place(W)), ins("lda", place(W)));
        assertEquals(at(m, V), at(m, W));
    }

    @Test
    void aPointerReadAsIndirectComesFirst() {
        LOperand.Mem pointer = new LOperand.Mem(LOperand.Width.NONE, LOperand.FRAME, 0, LOperand.Index.Y, true,
                new LOperand.Owner(Q, false, 0));
        var m = colour(Map.of(V, 2, Q, 300),
                ins("lda", place(P)), ins("sta", place(V)), ins("sta", place(Q)), ins("sta", place(Q, 2)),
                ins("lda", pointer), ins("adc", place(V)));
        assertEquals(2, at(m, Q));   // the predicted 300 is not in the direct page
        assertEquals(6, at(m, V));
    }
}
