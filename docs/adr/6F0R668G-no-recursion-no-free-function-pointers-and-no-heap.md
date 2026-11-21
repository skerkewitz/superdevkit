# ADR-6F0R668G: No recursion, no free function pointers and no heap, so frames have fixed places

**Context.** The direct page is the 65816's fast memory; values on the stack are slow to reach.
Blubber Blasen's call graph (609 edges) has no cycle and no indirect call, the deepest chain is
9 calls, and the game has no heap.

**Decision.** The subset rejects recursion (the checker names the cycle), free function
pointers and every kind of heap; all objects are static. Function pointers in constant tables
known at compile time are allowed by design, so the call graph stays complete, but are not built
until something needs them. With the complete call graph (ADR-6F0R65FW), every local, parameter and
temporary gets a fixed place: a compiled stack from `$0020`, where a function's frame starts
after the frames of the functions it calls, so the leaves of the call graph lie in the direct
page, and functions that are never active at the same time share places.

**Consequences.** Total memory and the deepest stack are known at compile time, and a stack
overflow cannot happen. The address of a local is a fixed address, so `&x` costs nothing extra.
The code is not reentrant: C code called from an interrupt would overwrite the frames of the
code it interrupted, so the platform layer has no interrupt handler (ADR-6F0R6NS5). The frames must
fit into `$0020-$1DFF`, or the build fails.
