package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Constant folding, common subexpressions and redundant loads on hand-written MIR. */
class GvnTest {
    private static String optimize(String body) {
        MModule m = MirParser.parse(body);
        Optimizer.run(m, true);
        StringBuilder out = new StringBuilder();
        for (String line : Printer.print(m).split("\n")) {
            if (line.startsWith("    ")) {
                out.append(line.strip()).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void theSameFieldIsLoadedOnceWhenNothingWritesInBetween() {
        // b->state == 2 || b->state == 3
        assertEquals("""
                %0 = load i8 %b
                %1 = cmp eq i8 %0, 2
                br %1, b1, b2
                ret i8 1
                %2 = cmp eq i8 %0, 3
                %3 = zext i1 %2 to i8
                ret i8 %3
                """, optimize("""
                func @_f(ptr %b) -> i8 {
                b0_entry:
                    %0 = load i8 %b
                    %1 = cmp eq i8 %0, 2
                    br %1, b1, b2
                b1:
                    ret i8 1
                b2:
                    %2 = load i8 %b
                    %3 = cmp eq i8 %2, 3
                    %4 = zext i1 %3 to i8
                    ret i8 %4
                }
                """));
    }

    @Test
    void aStoreThroughAnUnknownPointerKillsAKnownValue() {
        assertEquals("""
                %0 = load i16 @_g
                store i16 %p, 5
                %1 = load i16 @_g
                %2 = add i16 %0, %1
                ret i16 %2
                """, optimize("""
                global @_g : 2

                func @_f(ptr %p) -> i16 {
                b0_entry:
                    %0 = load i16 @_g
                    store i16 %p, 5
                    %1 = load i16 @_g
                    %2 = add i16 %0, %1
                    ret i16 %2
                }
                """));
    }

    @Test
    void aStoreToAnotherGlobalKeepsIt_andAStoredValueIsForwarded() {
        assertEquals("""
                store i16 @_g, %x
                store i16 @_h, 5
                %0 = add i16 %x, 5
                ret i16 %0
                """, optimize("""
                global @_g : 2
                global @_h : 2

                func @_f(i16 %x) -> i16 {
                b0_entry:
                    store i16 @_g, %x
                    store i16 @_h, 5
                    %0 = load i16 @_g
                    %1 = load i16 @_h
                    %2 = add i16 %0, %1
                    ret i16 %2
                }
                """));
    }

    @Test
    void aCallOfAFunctionThatWritesMemoryKillsKnownValues_aPureOneDoesNot() {
        assertEquals("""
                %0 = load i16 @_g
                %1 = call i16 @_pure(i16 %0)
                call void @_writer()
                %2 = load i16 @_g
                %3 = add i16 %1, %2
                ret i16 %3
                """, optimize("""
                global @_g : 2

                func @_pure(i16 %a) -> i16 {
                b0_entry:
                    ret i16 %a
                }

                func @_writer() -> void {
                b0_entry:
                    store i16 @_g, 1
                    ret
                }

                func @_f() -> i16 {
                b0_entry:
                    %0 = load i16 @_g
                    %1 = call i16 @_pure(i16 %0)
                    %2 = load i16 @_g
                    call void @_writer()
                    %3 = load i16 @_g
                    %4 = add i16 %1, %3
                    %5 = add i16 %4, 0
                    ret i16 %5
                }
                """).lines().skip(3).collect(java.util.stream.Collectors.joining("\n", "", "\n")));
    }

    @Test
    void constantsFoldAndABranchOnAConstantBecomesAJump() {
        assertEquals("""
                ret i16 42
                """, optimize("""
                func @_f() -> i16 {
                b0_entry:
                    %0 = add i16 40, 2
                    %1 = cmp ult i16 %0, 50
                    br %1, b1, b2
                b1:
                    ret i16 %0
                b2:
                    ret i16 0
                }
                """));
    }
}
