# 14. LIR is basic blocks with explicit successors, and its text is ca65 source

**Context.** The first code generator from MIR wrote ca65 text directly and did several jobs on
the side (register contents, dead stores, branch distances), writing each function up to 14
times. The plan to separate them ([transition-to-lir.md](../archive/transition-to-lir.md)) needed a
low-level IR, and every pass it was for is a data flow analysis over blocks.

**Decision.**

- A LIR function is basic blocks: a block is entered only at its start and left only at its
  end; every label starts one, every branch, jump or return ends one. Each block knows its
  successors (the block it falls through to included), its place in the layout and the MIR block
  it comes from. This is how LLVM's `MachineBasicBlock` works.
- Every instruction knows the accumulator width it runs in.
- The text of LIR is valid ca65 source, with a parser and a verifier.
- The cycles of an instruction belong to the instruction (`LInstr.cycles()`, from
  `Cycles65816`); the cycles of a C line belong to `Timing`, and the printer prints what it is
  given.

**Alternatives.**

- A flat list of instructions and labels, with the control flow computed when a pass needs it:
  simpler at first, but every pass would rebuild the graph or work on a stale one.
- One block per MIR block: the small branches inside a MIR block (shifts, copies, skips) would
  disturb the analyses as they disturbed `Timing`.
- The cost of a C line in the printer, as before: it would be computed in two places, and stay
  empty for every line with a branch.

**Consequences.** The passes of the back end (redundant loads, dead stores, branch relaxation,
peephole rules) each live in a class of their own. The line costs in the `.s` file need a run of
`Timing`, which every build does anyway. The `.s` file is what ca65 reads in the tests
(record 19).
