# 30. One book for superdevkit, with dampf as a part of its own

**Context.** The book in `docs/book` was written for dampf alone: its user guide, reference and
inside pages are all about the compiler. With record 29 dampf became the compiler of a
development kit, superdevkit, and the dev kit brings what the book does not cover yet: the
console API (`snes.h`) and its platforms, the example projects, the tools. The question was
whether the dev kit gets a book of its own next to dampf's, or one book holds both.

**Decision.**

- One book, for superdevkit. Its reader is someone making a SNES game, and what that reader needs
  from dampf (the C subset, the hints, the error messages, the command line, the cycle budgets)
  is part of making the game.
- The three guides stay, each for its reader: the user guide is about making a game with the
  dev kit (getting started from an example project, writing C for dampf, building, debugging in
  Mesen); the reference has the console API, with the table of built and stub per platform, and
  dampf's subset, hints, command line, output files and limits; inside has how dampf works and,
  later, how the library works.
- dampf's pages stay together, in folders of their own within the guides (`reference/compiler/`,
  `inside/compiler/`), and the dev kit's in theirs (`reference/api/`, `inside/library/`), so that
  dampf's part could become a book of its own by moving folders.
- The book keeps its name until the repository is renamed at publishing; then its title becomes
  superdevkit.

**Alternatives.** A book for the dev kit and one for dampf, the dev kit's explaining how to call
the compiler and linking to the other for the rest: right if dampf were released on its own
(the alternative record 29 turned down), but the reader goes back and forth between two sites
for every rule of the subset, and two sites mean two VitePress configurations, two glossaries
that repeat terms both need (bank, direct page, vertical blank) and links between them that
break more easily.

**Consequences.** One site, one glossary, one `/docs-gen` setup, one `BookSourcesTest`. The
restructuring of the book is part of M5, since M5 changes the getting-started page (an example
project) and adds the console API anyway: moving the existing pages into the folders comes
first, then the new pages. `doc-specs/README.md` describes the guides and their folders and
changes with it.
