# Subrange types and value ranges

Date: 2026-11-17 · Status: Proposal, not scheduled; what is built of it (inferred ranges, pointer
compression) is in [optimizations.md](optimizations.md). Related: [hints.md](hints.md)
(`DAMPF_RANGE` and the other hints), [subset.md](subset.md) (the subset rules).

This document describes how dampf learns the range of values a variable can hold, and what it
does with that knowledge. There are two sources:

1. Subrange types that the programmer declares: "this is an 8-bit value, but only 2..6 ever occur".
2. Ranges dampf infers on its own: from loops, array accesses, conditions, call sites and pointers.

Declared subrange types are also checked: `dampf check` proves on the PC, without building a ROM,
that every one holds; only explicit conversions are checked at run time. (The `dampf check` that
exists today checks the subset rules only.) The same source compiles on the PC with gcc, where it
must behave bit-identically, so every hint that states a fact is checked there
([hints.md](hints.md)).

---

## 1. Why value ranges matter on the 65816

On a modern CPU, knowing that a value lies in 2..6 rarely matters. On the 65816 it matters a lot:

- **8 instead of 16 bit.** The CPU switches between 8- and 16-bit operations as processor state. A value known to fit in 8 bits is cheaper to load, store, compare and compute.
- **Indices go straight into X or Y.** An index known to be inside an array needs no bounds handling and fits the 8-bit index registers.
- **No page-crossing penalty.** An indexed read with an 8-bit index costs an extra cycle when base + index crosses a 256-byte page. With a known index range, dampf can place the array so no page is crossed. The cycle disappears, and the cost no longer depends on the data, which keeps the timing analysis exact. This needs X and Y in 8-bit mode (processor state, like the accumulator width): with a 16-bit index the extra cycle is always paid ([timing.md](timing.md)), and dampf's code keeps X and Y at 16 bits so far.
- **Comparisons disappear.** If `lane` is always 2..6, then `lane >= 2` is always true and `lane > 10` always false.
- **Jump tables and lookup tables get small.** A `switch` over a value in 2..6 is a dense five-entry jump table. A function evaluated at compile time (`DAMPF_TABLE`) needs only five table entries.
- **Loops get bounds.** The worst-case timing analysis (`DAMPF_BUDGET`) needs a bound for every loop. Ranges often provide it without a hint.

---

## 2. Prior art: languages with range types

Range types exist, mostly in languages that are not mainstream today. They are called **subrange types**; the more general, mathematical form is called **refinement types**.

| Language | Syntax | How it is checked |
| --- | --- | --- |
| **Ada** | `subtype Lane is Integer range 2 .. 6;` | At run time: a value outside raises `Constraint_Error`. `for Lane'Size use 8;` additionally fixes the storage size. |
| **SPARK** (an Ada subset) | as Ada | **Proven at compile time**: the tools show mathematically that the value never leaves its range. Used in railway and aviation software. |
| **Pascal, Delphi, Free Pascal, Modula-2** | `type Lane = 2..6;` | At run time when range checking is enabled (`{$R+}`) |
| **Nim** | `type Lane = range[2..6]` | At run time |
| **VHDL** (hardware description) | `signal lane : integer range 2 to 6;` | The synthesis tool derives from the range **how many bits** the hardware register needs. That is exactly the idea behind dampf's ranges. |
| **Liquid Haskell, F\*** | `{v:Int \| 2 <= v && v <= 6}` | Proven at compile time, with arbitrary conditions, not only ranges |
| **TypeScript** | `type Lane = 2 \| 3 \| 4 \| 5 \| 6;` | At compile time, by enumerating values; only for constants |

Languages with bit widths but no arbitrary ranges:

- **Zig:** arbitrary widths such as `u3` (0..7), but not 2..6. The standard library can compute the smallest type that fits a range, but does not enforce the range.
- **C23:** `_BitInt(3)`, likewise width only.
- **Rust:** not built in. Libraries provide ranged integers; experimental work on "pattern types" exists but is not stable.
- **C, C++, Java, Go, Swift:** no. C++ has template libraries; Java/Android has annotations such as `@IntRange` that only a lint tool reads.

dampf's subrange types follow the Ada model: a named type with a range, checked wherever a value is stored into it.

---

## 3. Declaring subrange types in dampf

### Syntax

dampf's input is C, so a subrange type is declared with a macro:

```c
DAMPF_RANGED(uint8_t, Lane, 2, 6);          // like Ada: subtype Lane is ... range 2 .. 6
DAMPF_RANGED(uint8_t, BubbleIndex, 0, 15);
DAMPF_RANGED(uint8_t, BubbleCount, 0, 16);
DAMPF_RANGED(int8_t,  Speed, -4, 4);
```

- **PC build:** expands to `typedef uint8_t Lane;` plus a compile-time check (`_Static_assert`) that the base type can hold the whole range.
- **dampf build:** expands to a built-in the dampf parser recognises, so the type carries its range.

Both builds see the same source after preprocessing with `gcc -E`, so both agree on every declaration.

### Why C syntax is good enough here

A dedicated syntax such as `u8 lane in 2..6` would read better. But in C only the one declaring line looks unusual. Everywhere else the code just uses a type name:

```c
void move_to(Lane target);
Lane next_lane(Lane current);

struct Bubble {
    BubbleIndex next;
    Lane        lane;
    Speed       vx;
};
```

That reads like a language with subrange types, while keeping C's IDE support, tools and gcc as an independent reference. If dampf ever moves to another input language (for example Zig, once stable), this is one of the first features that would get real syntax.

---

## 4. Where subrange types can be used

A subrange type is an ordinary type and works anywhere a type can stand:

| Where | Example | What dampf gains |
| --- | --- | --- |
| Local variable | `Lane l = ...;` | 8-bit value, index directly in X |
| Global variable | `Lane player_lane;` | The same, across the whole program |
| **Struct field** | `struct Bubble { Lane lane; };` | Especially valuable: the field of *every* object is constrained. Arrays of such fields become compact and fast. |
| **Parameter** | `void move_to(Lane target)` | The function may assume the range. The obligation lies with the caller (see sections 5 and 6). |
| Return value | `Lane next_lane(Lane current)` | Every caller knows the range of the result |
| Array element | `Lane lanes[8];` | As for struct fields |

---

## 5. Semantics

### The rule

**Every value stored into a location of a subrange type is inside its range.** "Location" means a variable, a parameter, a struct field, an array element or a return value.

### Locations that were never written

C zero-initialises globals and statics, and dampf's runtime clears them to zero. A `Lane player_lane;` therefore starts at 0, outside 2..6, and so does the `lane` field of every element of a `Bubble bubbles[16]`. The rule above is about values *written*, so these initial zeros do not violate it. Instead, **reading a location of a subrange type before anything was written to it is an error**, as in Java and C# (definite assignment) and Ada (reading an uninitialised scalar):

- **Locals:** checked at compile time. A read before any write is visible in MIR as `undef`; dampf reports it.
- **Globals, statics, struct fields and array elements:** checked at run time. The interpreter keeps a "written" bit per byte and reports a read of a subrange location that was never written (file, line, frame, location).
- **An explicit initializer counts as a write.** `Lane player_lane = 2;` can be read at once.

On the SNES the memory still starts as zeros, but no correct program can observe them, so dampf may assume the range for every value it reads from a subrange location.

### Arithmetic

Arithmetic on subrange values happens in the base type, following the normal C rules; intermediate results are not constrained. The range applies when a value is **stored** into a location of the subrange type:

```c
Lane l = 6;
uint8_t t = l + 1;      // fine: t is uint8_t, value 7
l = l + 1;              // compile error: the result may be 7 (see section 6)
l = (l == 6) ? 2 : l + 1;   // fine: dampf can prove the result is in 2..6
```

### Where the rule is enforced

Every place where a value enters a location of a subrange type:

- assignment, including compound assignment (`+=`, `-=`, ...) and `++`/`--`
- initialisation (for constants checked at compile time)
- passing an argument to a parameter (checked at the **call site**)
- `return` from a function with a subrange return type
- storing into a struct field or array element

There is no wrap-around: `++` on a `Lane` holding 6 is a violation, not a jump back to 2.

### How it is checked

| Where | How |
| --- | --- |
| **dampf, at compile time** | Every implicit store must be proven in range, otherwise it is a compile error (section 6). Only explicit conversions with `DAMPF_AS` remain unproven. |
| **Interpreter** (HIR once it exists; until then MIR, whose stores then need to carry the subrange type of their target) | Checks **every** store into a subrange location, including those dampf has proven (as a safeguard against bugs in dampf's own analysis), and every read of a subrange location that was never written. This is the complete check, as in Ada. Running the replays of the game through the interpreter checks every subrange type in the whole program in real game situations. A violation reports the file, line, frame number and the offending value. |
| **PC build (gcc/clang)** | For gcc, a subrange type is just its base type, so gcc checks nothing. Instead, `dampf check` runs next to gcc and gives the compile-time guarantee of section 6 on the PC. At run time, `DAMPF_AS` expands to an assertion on the PC, so every explicit conversion is checked there too. |
| **SNES build** | No run-time checks: dampf relies on the range. Optionally, a **debug ROM** can be built in which dampf emits a check at every `DAMPF_AS`, the only stores it could not prove. It is slower, but catches violations on the console itself. |

A wrong range produces wrong code silently in the normal SNES build, exactly like a wrong hint. That is why the interpreter run over the replays is part of the test suite. It covers the paths the replays and tests reach; a `DAMPF_AS` on a path they never take is checked nowhere but in the debug ROM.

---

## 6. Static checking and conversions

### The rule: implicit means proven, explicit means checked

- **Without a conversion**, a value may only be stored into a location of a subrange type if dampf can **prove** that it fits. Otherwise it is a **compile error**.
- **With an explicit conversion**, the programmer states "I know this fits": `DAMPF_AS(Lane, x)`. This compiles; the value is checked at run time in the interpreter, in the PC build (as an assertion) and, if enabled, in the debug ROM.
- **A condition narrows the range** and therefore counts as a proof.

```c
DAMPF_RANGED(uint8_t, Lane,  2, 6);
DAMPF_RANGED(uint8_t, Lane2, 2, 7);

void move_to(Lane target);

Lane2 x = ...;

move_to(x);                    // error: x is 2..7, the parameter requires 2..6
if (x <= 6) move_to(x);        // ok: inside the if, x is 2..6
move_to(DAMPF_AS(Lane, x));    // ok: explicit, checked at run time
```

The result: every place where something *could* go wrong at run time is visible in the source as a `DAMPF_AS`. Everything else is proven. This is the same philosophy as SPARK.

### Subrange types as subtypes

A subrange type whose range lies completely inside another is a **subtype** of it and converts implicitly:

| From | To | Allowed implicitly? |
| --- | --- | --- |
| `Lane` (2..6) | `Lane2` (2..7) | Yes: 2..6 lies inside 2..7 |
| `Lane2` (2..7) | `Lane` (2..6) | No: 7 is possible. Needs a narrowing condition or `DAMPF_AS`. |
| `uint8_t` with an inferred range of 3..5 | `Lane` (2..6) | Yes: the inferred range is a proof too |
| `uint8_t` without a known range | `Lane` (2..6) | No |
| `Lane` (2..6) | `uint8_t` | Yes: always fits the base type |

The same rules apply to every way a value enters a location: assignment, argument passing, `return`, struct fields and array elements.

### `dampf check`: the checker on the PC

dampf does not need to produce a ROM to check a program. `dampf check` parses the code, computes the value ranges and reports every unproven store, in seconds. It runs:

- as a build step next to gcc in the PC build,
- as a task in the IDE,
- as a pre-commit hook,
- in continuous integration.

For PC developers this makes dampf a **type checker for subrange types**, something gcc cannot provide on its own. "Does it compile with dampf?" becomes the static guarantee that every subrange type holds, long before there is a ROM.

### Error messages

An error names both ranges and offers both ways out:

```
bubble.c:88: error: argument 'x' may be outside the range of parameter 'target'
    'x' has type Lane2 (2..7), 'target' has type Lane (2..6)
    fix: check the value first  (if (x <= 6) ...)
     or: convert explicitly      DAMPF_AS(Lane, x)
```

### The price, and how it shrinks

dampf only proves what its analysis can see, and it is conservative. It will sometimes reject correct code because it cannot find the proof, for example when the game logic keeps a value in range across several functions in a way the analysis does not follow. Then a `DAMPF_AS` is needed.

With a simple interval analysis at the start, this will happen more often. As the analysis gets smarter, many of these conversions can be removed again; dampf can even report conversions it now proves on its own. In the worst case there is one `DAMPF_AS` too many in the code, never one check too few.

Whether a program compiles must not depend on details of the optimizer: otherwise a change to a pass could make code that compiled yesterday fail today. What counts as a proof for the check should therefore be a fixed, documented set of rules (as SPARK and Java's definite assignment have), separate from the ranges the optimizer infers for speed (section 10).

---

## 7. Ranges dampf infers without annotation

Most ranges do not need to be declared. Value range analysis is a standard compiler technique; compiling the whole program at once makes it much stronger, because dampf sees every call site and every store. Sources of ranges:

| Source | Example | Inferred range |
| --- | --- | --- |
| Constants | `x = 5;` | 5..5 |
| **Counting loops** | `for (i = 0; i < n; i++)` | `i` in 0..n-1. If `n` is constant or has a subrange type (`BubbleCount` 0..16), then `i` in 0..15 without any hint. |
| **Array accesses** | `bubbles[i]` with `Bubble bubbles[16]` | After the access, `i` in 0..15 (see the bounds rule below) |
| Conditions | inside `if (x < 10) { ... }` | `x` in 0..9 within the block (for an unsigned `x`) |
| Masks | `x & 7` | 0..7 |
| Modulo | `x % 5` (unsigned) | 0..4 |
| Shifts | `x >> 4` (8-bit unsigned) | 0..15 |
| **Parameters, from all call sites** | every call of `move_to` passes a value in 2..6 | the parameter is 2..6, even if declared `uint8_t` (for optimization; whether it may count as a proof is an open question) |
| Return values | every `return` of a function yields 0..3 | each call's result is 0..3 |
| Enums | `enum State { IDLE, WALK, JUMP, DEAD };` | 0..3, if the subset forbids values outside the enumerators (to be decided, section 10; [subset.md](subset.md), decision 2, already recommends it) |

Inferred ranges are used for optimization exactly like declared ones. They need no run-time checks, because they are derived from code that dampf has fully seen. Which of them also count as proofs for the static check (section 6) is part of the fixed proof rules (section 10).

Inferred ranges need no change to the language, so they came first: the width narrowing in MIR needed them. In `level_solid_px`, `(sy - 16) >> 3` can only be computed in 16 bits once dampf knows from the branch before it (`sy < 16` returns) that `sy - 16` cannot overflow. What is built of this table is in [optimizations.md](optimizations.md), *Value ranges*.

### The array bounds rule

In C, an out-of-bounds array access is undefined behaviour, and compilers draw conclusions from it that surprise programmers. dampf draws one such conclusion too, but makes it an explicit, checked rule of the subset: **every array index must be within the array's bounds**, checked in the PC build (gcc's `-fsanitize=bounds`, part of the `-fsanitize=undefined` the execution tests use) and always in the interpreter. With that rule, dampf may assume after `bubbles[i]` that `i` is in 0..15. Every array access then acts as a checked range statement.

The check covers the paths the tests and replays run; on any other path a violation gives silently wrong SNES code, as a wrong hint does. (Today only gcc's sanitizer checks it, in the execution tests and the random programs.) The rule is about reading and writing elements: `&a[16]`, the address just past the end, is legal C and allowed.

---

## 8. Pointers: from addresses to small indices

Pointers usually defeat range analysis: `while (p != NULL) { ...; p = p->next; }` gives no numeric range for `p`. But the dampf subset gives something better.

### No heap means every pointer has known targets

The subset has no heap. **Every pointer points into an object or array that exists at compile time**, or is `NULL`. dampf can therefore compute, for every pointer, the set of things it may point to (a **points-to analysis**). With the whole program visible, the result is precise.

### Pointer compression

If the analysis shows that `p` only ever points into `bubbles[16]` or is `NULL`, dampf can represent `p` as an **8-bit index** instead of a 16- or 24-bit address:

- `p` is stored as an index 0..15; `NULL` becomes `0xFF`
- a field `next` of pointer type becomes an 8-bit field
- `p->x` becomes `lda bub_x,x` (with the array stored as one array per field)
- `p != NULL` becomes a test of the index register against `0xFF` (or, with `NULL` as `0x80`..`0xFF`, a test of the sign bit)

On the 65816 this is a large gain: full pointers are expensive there, an index in X is cheap.

### Required subset rules

The analysis is only sound if every pointer value comes from taking the address of something dampf knows. The subset therefore adds:

- **No casts between integers and pointers.** Hardware registers would be reached only through intrinsics, never through `(uint8_t *)0x2100`. *Not adopted:* the platform layer (`lib/snes`) uses pointers to `volatile` memory made from numbers, and dampf accepts such casts ([subset.md](subset.md), *Hardware access*). A full points-to analysis would need the rule back.
- **Pointer arithmetic stays within one array.** `p + 1` must point into the same array as `p` (or one past its end), checked in the interpreter. MIR's `ptradd` with a 16-bit offset already relies on this ([memory-map.md](memory-map.md)).

### Loop bounds for linked structures

A list threaded through `bubbles[16]` has at most 16 elements **if it has no cycle**. dampf cannot prove the absence of cycles in general. For the worst-case timing analysis, such a loop therefore still needs `DAMPF_TRIPS(16)`, which the interpreter checks while the replays run.

---

## 9. What uses the ranges

| Consumer | Use |
| --- | --- |
| Width analysis (MIR) | Chooses 8-bit operations and registers where the range fits |
| Instruction selection (LIR) | Indices in X/Y without bounds handling; tests against `0xFF` or the sign bit |
| Data layout | Arrays placed so that indexed reads never cross a page; pointer compression; one array per field |
| Constant folding | Removes comparisons whose result the range decides |
| `switch` lowering | Dense jump tables over the actual range only |
| `DAMPF_TABLE` | Table size equals the size of the input range |
| Timing analysis | Loop bounds from counting loops and subrange types, so fewer `DAMPF_TRIPS` hints are needed |

---

## 10. Open questions

- **Enums:** should the subset forbid values outside the enumerators, so every enum type is automatically a subrange type? (Recommended; checked in the interpreter like subrange types.)
- **Steps and sets:** ranges with a step ("even values only") or arbitrary sets of values are possible in principle (refinement types). Are they worth it, or are plain ranges enough?
- **Debug ROM:** which checks go into it (all unproven stores, or only selected types), and how a violation is reported on the console.
- **PC build:** is it worth adding an optional instrumented PC build (for example via a clang plugin) that checks subrange stores like the interpreter does, or is the interpreter run over the replays enough?
- **Strength of the analysis:** how far the proofs should reach at first (interval analysis inside a function, or across calls from the start), given that a weaker analysis means more `DAMPF_AS` in the code.
- **Reporting:** whether dampf should list, per function, which ranges it inferred, so a human can see what the optimizer assumed.
- **What counts as a proof.** Recommended: a fixed, documented set of rules (constants, declared subrange types, conditions on the path, masks and the like), independent of the optimizer, so the result of `dampf check` does not change when a pass changes.
- **Parameter ranges from call sites as proofs.** Recommended: for optimization only. As proofs they let a new call in one file break the check inside a function in another file, with the error far from its cause; the check uses declared types and local conditions.
- **`DAMPF_AS` and `DAMPF_RANGE`.** `DAMPF_RANGE(i, 0, 15)` ([hints.md](hints.md)) states a range for a value at one point; `DAMPF_AS(Lane, x)` converts a value to a subrange type. Both are checked assertions on the PC. Recommended: keep both, and define `DAMPF_AS` as `DAMPF_RANGE` plus the conversion, so there is one checking mechanism.

## 11. Where it fits into the milestones

Decided 2026-11-17 (ADR [record 23](adr/0023-value-ranges-are-inferred-first-and-subrange-types.md)):
the parts that need no language change come first, and declared subrange types only when a
profile shows a hot value whose range dampf cannot infer. That last part (`DAMPF_RANGED`,
`DAMPF_AS`, the types carried through the front end, the checks of sections 5 and 6, the debug
ROM) is not scheduled ([roadmap.md](roadmap.md)).
