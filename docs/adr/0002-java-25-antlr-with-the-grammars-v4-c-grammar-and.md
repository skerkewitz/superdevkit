# 2. Java 25, ANTLR with the grammars-v4 C grammar, and Gradle

**Context.** A compiler with several IR levels is mostly data types for the IR and passes that
take them apart case by case. Compile speed does not matter: a game the size of Blubber Blasen
compiles in seconds either way.

**Decision.**

- Java, compiled for the current LTS release, 25 (`options.release = 25`): records, sealed
  interfaces and pattern matching suit an IR and compiler passes, and strong types catch many
  errors early.
- ANTLR 4 for the parser, with the C grammar from grammars-v4. The grammar files under
  `src/main/antlr` stay as vendored; changes go into the support classes and are listed in
  `src/main/antlr/dampf/parser/README.md`.
- Gradle with the Kotlin DSL and the wrapper as the build tool.

**Consequences.** Using dampf needs a JDK 25 or newer; gcc is still needed for preprocessing
(record 7). The grammar accepts more than the subset, which the checker relies on (record 3). An
update of the grammar is a copy of the new files, without merging local changes.
