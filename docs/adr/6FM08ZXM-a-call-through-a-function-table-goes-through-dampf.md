# ADR-6FM08ZXM: A call through a function table goes through dampf_icall, with its arguments in dampf_regs

**Context.** ADR-6F0R668G allows function pointers in constant tables known at compile time, so
that the call graph stays complete, and left them unbuilt. A game's state machine wants them
(`tick[state]()`), and so does any table of handlers. Three things had to be settled: what
counts as a constant table, how the 65816 calls through one, and how the arguments reach the
callee. dampf calls with `JSL`/`RTL` and a function can lie in any ROM bank; the 65816 has no
indirect `JSL`, only `JML [abs]`, which reads a 24-bit address in bank 0. Every function has
its parameters at fixed places of its own (the compiled stack), and the functions of one table
have them at different places.

**Decision.** A function pointer exists only in a `const` object with static storage and an
initializer (a global or a `static` local in ROM): an array, a struct, or a single pointer.
Every function pointer in it names a function of its type; null or unset entries are errors.
A call goes straight from the table (`handlers[i](x)`, `rules[r].react(x)`, `(*t[i])()`); the
functions it can reach are the entries its place can be, and the call graph has an edge to each.
Any other use of a function pointer is an error: a variable, a parameter, a result, a value
read, compared or converted.

An entry is 4 bytes, like every pointer. The caller copies it into `dampf_fptr` (4 bytes in the
runtime's direct page) and its arguments into `dampf_regs`, word by word, then calls the
runtime's `dampf_icall` with `JSL`, which is one `JML [dampf_fptr]`: the function's `RTL`
returns to the caller. A function with parameters is entered through a thunk that the code
generator from MIR writes, which copies them from `dampf_regs` into its frame and jumps to it;
the table holds the thunk's address. The naive code generator, which has no shared frames,
stores the arguments into the parameters of every function the call can reach instead. The
arguments of a table call may take at most 18 bytes, the size of `dampf_regs`. The timing
counts a table call as its dispatch plus the costliest (and the cheapest) entry.

**Alternatives.** Lowering a table call to a `switch` over the index: no runtime support, but
it is limited to plain one-dimensional tables, and its cost grows with the number of entries.
`PHK`, `PER` and `JML [abs]` at each call site instead of `JSL dampf_icall`: one cycle more and
three bytes more per call, and a jump the LIR passes would have to learn. Entries of 3 bytes:
a byte less per entry, but a pointer type of another size than every other pointer. Giving the
functions of one table the same parameter places: no copy in a thunk, but it constrains the
frame layout, and fails when one of them calls another.

**Consequences.** A call through a table costs 26 CPU cycles more than a direct call: 20 to
copy the entry into `dampf_fptr`, 6 for the `JML`. A function with parameters adds 4 for its
thunk's jump and 8 or 9 for each word of arguments, which are copied twice. A table call is
never inlined. A `switch` that calls the handlers is cheaper for a few of them; the C style
guide measures where it stops being so (`slow-table-call-few`, `fast-table-call-many`).
`dampf_fptr` fills the runtime's direct page to 30 of its 32 bytes.
