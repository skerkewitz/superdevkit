package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Dead-code elimination over the whole program: what the roots reach through calls, taken
 * addresses and the addresses in global initializers stays; the functions they do not reach go,
 * and the globals only those used.
 */
class ReachableTest {
    private static final String PROGRAM = """
            global @_used : 2
            global @_table : 4 const = [ 0: ptr @_data ]
            global @_data : 2
            global @_unused : 64
            global @_only_dead : 2

            func @_main() -> void {
            b0_entry:
                store i16 @_used, 1
                %0 = load ptr @_table
                call void @_helper()
                ret
            }

            func @_helper() -> void {
            b0_entry:
                store i16 @_used, 2
                ret
            }

            func @_dead() -> void {
            b0_entry:
                store i16 @_only_dead, 3
                call void @_also_dead()
                ret
            }

            func @_also_dead() -> void {
            b0_entry:
                ret
            }

            func @_handler() -> void {
            b0_entry:
                ret
            }
            """;

    @Test
    void whatTheRootsDoNotReachGoes() {
        MModule m = MirParser.parse(PROGRAM);
        assertEquals(3, Reachable.run(m, Set.of("_main", "_handler")));
        Verifier.verify(m);
        assertEquals(List.of("_main", "_helper", "_handler"), m.functions().stream().map(MFunction::name).toList());
        // _only_dead goes with the function that used it; _unused, which nothing names, stays
        assertEquals(List.of("_used", "_table", "_data", "_unused"), m.globals().stream().map(MModule.Global::symbol).toList());
    }

    @Test
    void aRootNamedByAssemblyKeepsWhatItReaches() {
        MModule m = MirParser.parse(PROGRAM);
        Reachable.run(m, Set.of("_main", "_dead"));
        assertEquals(List.of("_main", "_helper", "_dead", "_also_dead"), m.functions().stream().map(MFunction::name).toList());
    }

    @Test
    void afterInliningOnlyWhatIsStillCalledNeedsCode() {
        MModule m = MirParser.parse("""
                global @_jump : 4 const = [ 0: ptr @_by_table ]

                func @_main() -> void {
                b0_entry:
                    call void @_called()
                    ret
                }

                func @_called() -> void {
                b0_entry:
                    ret
                }

                func @_inlined() -> void {
                b0_entry:
                    call void @_called()
                    ret
                }

                func @_by_table() -> void {
                b0_entry:
                    ret
                }
                """);
        // _inlined is no longer called (its calls were inlined): timed, but no code; a global
        // the ROM keeps reaches _by_table
        assertEquals(Set.of("_main", "_called", "_by_table"), Reachable.needCode(m, Set.of("_main")));
        assertEquals(4, m.functions().size());
    }
}
