package dampf.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.mir.Ranges;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When a switch becomes a jump table (ADR-6FM7MWGX), and that the execution test's switches
 * (exec/switch_table.c) are compiled as it says: the results there are checked against the PC.
 */
class SwitchTableTest {
    @TempDir
    Path tmp;

    private static final Ranges.Range U16 = new Ranges.Range(0, 0xFFFF);
    private static final Ranges.Range S16 = new Ranges.Range(-0x8000, 0x7FFF);

    private static List<Integer> from(int first, int n) {
        List<Integer> l = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            l.add((first + k) & 0xFFFF);
        }
        return l;
    }

    @Test
    void aTableFromSixCasesFrom0WithAnUpperCheck() {
        assertNull(SwitchSelection.plan(from(0, 5), U16, S16));
        SwitchSelection.Table t = SwitchSelection.plan(from(0, 6), U16, S16);
        assertNotNull(t);
        assertEquals(15, t.cycles());   // CMP/BCS, ASL, TAX, JMP (abs,X)
        assertTrue(t.zeroFirst() && !t.subtract() && !t.below() && t.above());
    }

    @Test
    void aTableFromFourCasesWhenTheRangeNeedsNoCheck() {
        Ranges.Range r = new Ranges.Range(0, 3);
        assertNull(SwitchSelection.plan(from(0, 3), r, r));   // (the 4th value goes to default)
        SwitchSelection.Table t = SwitchSelection.plan(from(0, 4), r, r);
        assertNotNull(t);
        assertEquals(10, t.cycles());
        assertFalse(t.below() || t.above());
        // widened to the value's range, 0..7, to drop the check
        Ranges.Range seven = new Ranges.Range(0, 7);
        SwitchSelection.Table w = SwitchSelection.plan(List.of(1, 2, 3, 4), seven, seven);
        assertNotNull(w);
        assertEquals(8, w.size());
        assertEquals(10, w.cycles());
    }

    @Test
    void aTableFromSevenCasesFarFrom0OrNegative() {
        assertNull(SwitchSelection.plan(from(100, 6), U16, S16));
        SwitchSelection.Table t = SwitchSelection.plan(from(100, 7), U16, S16);
        assertNotNull(t);
        assertEquals(20, t.cycles());
        assertNull(SwitchSelection.plan(from(-3, 6), U16, S16));
        SwitchSelection.Table s = SwitchSelection.plan(from(-3, 7), U16, S16);
        assertNotNull(s);
        assertTrue(s.subtract());
        assertEquals(0xFFFD, s.lo());
        assertEquals(7, s.size());
    }

    @Test
    void noTableForSparseCases() {
        // 8 cases over 33 values: more than four entries per case
        assertNull(SwitchSelection.plan(List.of(0, 1, 2, 3, 4, 5, 6, 32), U16, S16));
        assertNotNull(SwitchSelection.plan(List.of(0, 1, 2, 3, 4, 5, 6, 31), U16, S16));
    }

    @Test
    void theExecutionTestHasItsTables() throws Exception {
        Path src = Path.of(SwitchTableTest.class.getResource("/exec/switch_table.c").toURI());
        Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main", tmp.resolve("t.sfc"))
                .withBackend(Compiler.Backend.MIR));
        String asm = Files.readString(tmp.resolve("t.s"));
        // the jump tables per function
        Map<String, Integer> tables = new HashMap<>();
        String function = null;
        for (String line : asm.split("\n")) {
            if (line.startsWith(".proc ")) {
                function = line.substring(6).strip();
            } else if (line.contains("jmp     (.loword(")) {
                tables.merge(function, 1, Integer::sum);
            }
        }
        for (String f : List.of("_dense8", "_dense16", "_sparse", "_signed16", "_signed8", "_fall", "_masked",
                "_no_default", "_loop_head", "_big")) {
            assertEquals(1, tables.getOrDefault(f, 0), f + " has a jump table");
        }
        assertEquals(0, tables.getOrDefault("_chain", 0), "_chain compares");
        assertEquals(0, tables.getOrDefault("_kinds", 0), "_kinds compares: its loop's offset lives in X");
        assertTrue(asm.contains("b0_entry_x0:"), "loop_head loads X for its loop on the way from the table");
        // _big and its table lie in a bank of their own, not $80
        Map<String, Integer> labels = new HashMap<>();
        Matcher m = Pattern.compile("al ([0-9A-F]{6}) \\.(\\S+)").matcher(Files.readString(tmp.resolve("t.lbl")));
        int big = -1;
        while (m.find()) {
            int address = Integer.parseInt(m.group(1), 16);
            labels.put(m.group(2), address);
            if (m.group(2).equals("_big")) {
                big = address;
            }
        }
        assertTrue(big >> 16 > 0x80, "_big lies in bank $" + Integer.toHexString(big >> 16));
        boolean tableInBank = false;
        Matcher t = Pattern.compile("al ([0-9A-F]{6}) \\.b0_entry_table").matcher(Files.readString(tmp.resolve("t.lbl")));
        while (t.find()) {
            int at = Integer.parseInt(t.group(1), 16);
            tableInBank |= at >> 16 == big >> 16 && at > big;
        }
        assertTrue(tableInBank, "the table of _big lies after its code, in its bank");
        assertFalse(labels.isEmpty());
    }
}
