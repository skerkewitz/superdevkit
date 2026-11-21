# ADR-6F0R6DZD: Three IR levels, and HIR waits until something needs it

**Context.** High-level optimizations need the structure of the program: loops, structs,
arrays, calls. Low-level ones need the details of the 65816: registers, processor state, memory
regions, cycle costs. One representation does both badly; Swift goes from SIL to LLVM IR, Rust
from MIR to LLVM IR to LLVM's machine IR.

**Decision.** Three levels after the checked AST, for the three kinds of knowledge:

- HIR, the structure of C (loops, struct fields, array indexing, hints);
- MIR, data flow: SSA with explicit widths, no structs, still independent of the machine
  (comparable to LLVM IR, not to Rust's MIR);
- LIR, the machine: 65816 instructions with the processor state (ADR-6F3CJEQA).

Each level has a text format that prints and parses back, a verifier, and for HIR and MIR an
interpreter. Deliberately not more than three: every level costs a lowering, a verifier, a text
format and tests, and another is added only if an optimization demonstrably fits nowhere
cleanly. HIR is built only when something needs it: until then MIR is lowered straight from the
AST, which already makes the conversions explicit.

**Consequences.** The recursion check runs on the AST and inlining and loops on MIR. The split
of struct arrays (`DAMPF_SPLIT`) and `DAMPF_TABLE`, which belong in HIR, wait for it.
