# 20. Mesen is the debugger, and the `.dbg` file means what ld65's means

**Context.** Stepping through C on the console was the biggest pain in an earlier attempt, so it
was checked first, in M1. Mesen 2 reads the debug files ld65 writes and shows the C source from
them. There is no written specification of the format, but both ends are open source: ld65
writes it in `src/ld65/dbgfile.c` (zlib licence) and Mesen reads it in
`UI/Debugger/Integration/DbgImporter.cs` (GPLv3, read to learn the format, never copied).

**Decision.**

- dampf has no debugger of its own; it uses Mesen 2.
- The assembly carries source line directives (`.dbg line`), and dampf writes the `.dbg` file in
  ld65's format, with every record type ld65 writes, also those Mesen ignores (cc65's `dbginfo`
  library needs `version` and `info`).
- The file means what ld65's means: which source line covers which ROM addresses, which symbol
  is at which address, which C functions and variables exist. Its ids and the order of its
  records may differ. Should Mesen turn out to rely on the order, dampf matches ld65 in that one
  point.

**Alternatives.** Writing the text byte for byte as ld65 does: the ids and the order follow
ld65's internals and help neither Mesen nor a user.

**Consequences.** The test compares what the two files say, not their text. Mesen shows the
source and the names of globals, not locals, C types or struct fields; better C debugging needs
more than the format carries and Mesen reads, and comes after M5
([roadmap.md](../roadmap.md#m9-timing-and-debugging-090)).
