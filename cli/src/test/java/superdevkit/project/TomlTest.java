package superdevkit.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The TOML reader: the subset superdevkit.toml uses, and an error with the line for the rest. */
class TomlTest {
    @Test
    void readsTheSubset() throws Exception {
        Map<String, Object> t = Toml.parse("t.toml", """
                # a comment
                name = "catchme"          # after a value
                kit = '../..'
                sram = 1_024
                neg = -3
                on = true
                sources = ["src", "more"]
                long = [
                    "a",   # one
                    "b",
                ]
                empty = []
                text = "tab\\tquote\\" back\\\\ \\u00e4"

                [graphics]
                tiles = ["x.png", { png = "y.png", name = "y_gfx" }]
                mode7 = [{ png = "m.png", first = 33 }]
                """);
        assertEquals("catchme", t.get("name"));
        assertEquals("../..", t.get("kit"));
        assertEquals(1024L, t.get("sram"));
        assertEquals(-3L, t.get("neg"));
        assertEquals(true, t.get("on"));
        assertEquals(List.of("src", "more"), t.get("sources"));
        assertEquals(List.of("a", "b"), t.get("long"));
        assertEquals(List.of(), t.get("empty"));
        assertEquals("tab\tquote\" back\\ ä", t.get("text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) t.get("graphics");
        assertEquals(List.of("x.png", Map.of("png", "y.png", "name", "y_gfx")), g.get("tiles"));
        assertEquals(List.of(Map.of("png", "m.png", "first", 33L)), g.get("mode7"));
    }

    private static String error(String text) {
        return assertThrows(Toml.Error.class, () -> Toml.parse("t.toml", text)).getMessage();
    }

    @Test
    void namesTheLineOfAnError() {
        assertTrue(error("a = 1\nb = \"open\n").startsWith("t.toml:2:"), error("a = 1\nb = \"open\n"));
        assertTrue(error("a = 1\na = 2\n").contains("'a' is there twice"));
        assertTrue(error("a = 1.5\n").contains("'1.5' is no value"));
        assertTrue(error("a = [1 2]\n").contains("expected ',' or ']'"));
        assertTrue(error("a = 1 b\n").contains("more after the value"));
        assertTrue(error("[t]\n[t]\n").startsWith("t.toml:2:"));
        assertTrue(error("= 3\n").contains("expected a key"));
        assertTrue(error("a = { b = 1\n").contains("inline table"));
    }
}
