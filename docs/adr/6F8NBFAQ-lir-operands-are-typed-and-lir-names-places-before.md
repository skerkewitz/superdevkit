# ADR-6F8NBFAQ: LIR operands are typed, and LIR names places before it has addresses

**Context.** ADR-6F3CJEQA made LIR basic blocks whose text is ca65 source, and an instruction's
operand is that text (`"z:dampf_frame+6"`). The passes after selection find out what an
instruction reads and writes by parsing the text (`Selection.frameOffset`, `RegContents`), and
a value's place is an address from the start: `FrameLayout` assigns every MIR value its place
before any code exists, predicting what selection will write. A wrong prediction is a
miscompilation no verifier sees; three bugs of that kind were found in November 2026 (the φ
copies of `b33eeba`, the two loops of `02fa308`, the φ a branch reads, `50d5c04`).
docs/current-compiler-flaws.md, flaw B, plans to assign places after selection, on LIR, as LLVM
and GCC do with virtual registers; that needs operands a pass can ask, and places that are not
addresses yet.

**Decision.**

- An LIR instruction's operand is a typed value (`LOperand`, a sealed type): none, the
  accumulator, an immediate (a number or a symbol's word or bank), a label, or a memory
  operand, a base plus an offset with its addressing form (direct page, absolute, long; indexed
  by X or Y; indirect long through a pointer, indexed by Y). The base is a place of the frame, a
  register of the runtime, a symbol, a fixed address or the pointer `dampf_ptr`.
- A frame place is either assigned (an offset into `dampf_frame`) or virtual: a word of a MIR
  value, a φ's incoming place, or a temporary of selection. LIR has two stages: after selection
  it names virtual places, and a phase assigns them addresses. Its text in the second stage is
  ca65 source, as ADR-6F3CJEQA says; in the first it names the places symbolically and is read back
  by the same parser.
- The passes ask the operand what it reads and writes; none parses operand text.

**Alternatives.** Keep text operands and parse them in each pass: what dampf does today, and what
makes every pass guess. Allocate on MIR with a better prediction of selection: the bugs above
are all wrong predictions.

**Consequences.** The change comes in steps that keep every hash of `BackendOutputTest` (record
24): first the type, printed exactly as today's text; then the passes on it; then selection
writing virtual places, assigned as `FrameLayout` assigns them now. Assigning places by cost on
LIR (B2, B3 of the plan) comes after and is decided there.
