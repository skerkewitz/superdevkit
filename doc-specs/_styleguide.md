# Style guide for the book

Rules for every page generated into `docs/book`. Keep to them so generated and hand-written
pages read alike.

## Three guides, three readers

The book has three guides, each for a different reader and each with a sidebar of its own;
[README.md](README.md) says which folder belongs to which. Before writing a page, know which
guide it is in, and write for that guide's reader.

- **The user guide** (`guide/`) is what a user reads first, and in the best case all they need:
  what dampf is, how to set it up, how to write C it accepts, how to compile and debug a game.
  Its readers write C for a game, know the command line and have heard of the 65816, but do not
  know dampf's code. Some know 65816 assembly well, others only C. Keep it simple and to the
  point: the steps, one example each, and a link to the reference for the details. A page of
  the user guide never lists every case.
- **The reference** (`reference/` and the glossary) is where a reader looks something up:
  every command and option, the whole C subset, the memory layout, the files dampf writes, what
  is not built. A reference page is complete for its subject and is read in parts, so each
  section stands on its own.
- **The inside guide** (`inside/`) explains how dampf works under the hood, for a reader who
  wants to know why the generated code looks the way it does, or who wants to contribute. Its
  readers know compilers or the 65816 well. It may name classes and passes, but it is not a
  complete developer guide: the working documents in `docs/` are.

A page links to a page of another guide wherever its reader needs it; the user guide links to
the reference for every detail it leaves out. Each spec may name its readers more closely in
its `audience`.

## Language and tone

Write the way an experienced colleague explains something at a whiteboard:

- English, British spelling with *-ize* as the rest of dampf's documents write it:
  *optimization*, *initialized*, *behaviour*, *colour*. Follow the spelling of `README.md`
  where in doubt.
- Plain and direct. Short sentences, common words. No marketing language, no exclamation
  marks, no humour. No words that tell the reader something is easy: "simply", "just", "easy",
  "obviously", "of course", "clearly".
- A paragraph does not start with code (an option, a name, a value): lead with a word, often
  the verb. "Use `--entry` to name the function the runtime calls", not "`--entry` names ...".
  Lists, tables and entries whose subject is the code itself are exceptions.
- Explanatory sections as flowing prose. Use lists only for things that are genuinely
  enumerable (options, types, steps in a fixed order).
- Connect sentences. Say why something is the way it is and what follows from it.
- Verbs instead of nouns, active instead of passive.
- Concrete instead of abstract: name the type, the value, the instruction; give an example.
- Address the reader as *you*. The compiler is *dampf*, lower case, also at the start of a
  sentence.
- Say why, not only what: when a behaviour might surprise, give the reason in a sentence. Most
  surprises in dampf come from two rules, and a page names the one that applies: the result must
  match gcc on x86-64 bit for bit, and the whole program is compiled at once.
- The problem comes before the solution.
- Present tense, describing what dampf does now. Plans belong on the roadmap page and on the
  [Limits](/reference/limits) page only. Where a page has to mention something not built, it
  says so in one sentence and links to one of those two pages.
- Spell out an abbreviation or explain a technical term the first time a page uses it, in a few
  words, or link its glossary entry: `[direct page](/glossary#direct-page)`. Terms every
  reader knows (C, gcc, ROM, SNES) get no link.
- Get straight to the point. No announcements of what's coming, no closing summary that
  repeats.

## Facts and numbers

- Measurements come from the sources and say what was measured and on what: "1.76 frames per
  tick during play of Blubber Blasen". Where sources give different numbers for the same thing,
  use the newest one in `docs/optimizations.md` and say when it was measured if the source does.
- Large counts take a comma: *3,610 ticks*. Hexadecimal addresses are written as on the 65816,
  with a dollar sign: `$7E`, `$21FB`.
- Messages of dampf are quoted exactly as it prints them.
- Every figure carries its unit (frames per tick, master clocks per tick, CPU cycles), and a
  speed says whether it is FastROM or SlowROM where the sources give both. A page that quotes
  figures from different times or units says where one kind ends.
- A page never mentions *the sources*, the specs or how it was written: "the sources give no
  measurement" becomes "no separate measurement exists", or the sentence goes.

## Depth

A page answers the questions its readers have; it does not retell everything its sources say.
The README, the working documents and the tests are complete; the book is selective.

- Give the rule and the cases a reader is likely to meet. Leave out what only matters to
  someone reading the code. Where such a list exists, say that it does and where.
- Length follows from the content. No padding, no retelling of what another page owns.
- Describe commands and options only on [Command line](/reference/command-line), and link there
  from elsewhere.
- One new idea per example.
- Where a page simplifies, say so and link to the full account.

## Examples

- C code goes in ```` ```c ```` fences, shell commands in ```` ```sh ````, 65816 assembly as
  dampf writes it (ca65 syntax) in ```` ```asm ````. Output of dampf goes in a fence without a
  language tag.
- A code block that is the content of a file names it: ```` ```c [compiler/src/test/resources/exec/calls.c] ````.
  The book shows it as a title above the block. A fragment that belongs to no file has no title.
  A block with a file's name that is shortened or changed (other paths, lines left out) says so
  in the sentence before it; a fragment cut from a file names the file there.
- The user guide uses the files of the repository where they show the point:
  `examples/hello` for a first game, `compiler/src/test/resources/exec/calls.c` (an execution test) for a single file without the kit,
  `examples/ertuep` (Er-Tüp) for a whole game. Other examples are as small as the point needs,
  and show their point: an example of a call that changes the picture draws something the change
  can be seen on.
  Names say what the code does (`player_x`, `add3`), not `foo` or `x`.
- Every example C program is valid in the subset and compiles with dampf; every command shown
  runs as shown, from the top of the repository or, for a game, in its folder, with `<home>` for
  the [superdevkit home](/glossary#superdevkit-home); every message shown is what dampf prints for
  exactly the code shown. `/docs-gen` checks them by running dampf.
- Assembly shown as dampf's output is copied from what dampf writes for the C shown, not written
  by hand. It may be cut to the lines that matter, with `; ...` where lines are left out.

## Formatting

- One H1 per page: the spec's `title`. The spec's sections become H2, in its order. Use H3
  sparingly.
- Headings in sentence case.
- Lines are wrapped at about 96 characters, outside code blocks and tables. Never break inside
  inline code.
- Lists of points that each need a sentence or two start with a bold lead-in.
- Diagrams are Mermaid (```` ```mermaid ````), only where the spec asks for one.
- A table cell holds a phrase or a short sentence; the text below the table says the rest.
- Notes use VitePress containers with a short title: `::: info Same result as gcc`,
  `::: warning Not built yet`. At most two or three per page.
- Links to other pages of the book are absolute and without extension:
  `[The C subset](/reference/c-subset)`.
- Inline code for anything typed or printed: file names, options, type and function names,
  keywords, instructions, addresses.

## Inside pages

Every page of the inside guide ends with an H2 *Why it works this way*, after the sections of
its spec; the roadmap does not. The section takes the two or three choices of the page that a
reader is most likely to wonder about, each as a bold lead-in naming the choice, then in a few
sentences: what was chosen, the alternative that was turned down, and what the choice costs.
Each ends with the document and section of `docs/` that records it, in plain text, such as
"(`docs/architecture.md`, *Deliberately not more than three*)"; the book is published without
the repository, so these are not links. A choice that no source explains stays off the list: a
reason made up for the page is worse than none.

## Terminology

Use the terms of the [glossary](../docs/book/glossary.md) and spell them as it does:

- *the subset* (dampf's C subset), *whole program*, *entry function*, *runtime*.
- *naive code generator* and *code generator from MIR*; the option values are `naive` and
  `mir`.
- *MIR*, *SSA*, *pass*; *direct page*, *bank*, *DBR*; *8-bit* and *16-bit* mode, *`M` flag*,
  *`X` flag*.
- *tick* and *frame* as Blubber Blasen uses them: a tick is one step of the game logic, a frame
  one picture on the screen (60 per second).
- *gcc on the PC* or *the PC build* for the reference dampf is compared with.
- *Mesen* (MesenCE, the continuation of Mesen 2), *ca65*, *ld65*, *Blubber Blasen*, *Calypsi*, *PVSnesLib* as written here.
- The game's own words (*play*, *the CPU partner*, a function name such as `separate`, the
  pair loop) are explained at first use or linked to the glossary; a page that needs no more
  of them than a figure uses the figure without them.

## Never include

- Hostnames, user names, paths of a developer's machine (`/mnt/d/...`, `/home/...`).
- Credentials, tokens or keys.
- Statements about how Blubber Blasen's own repository is organized.
