# 41. The release is a zip per system with a Java runtime and raylib, and the repository looks like it

**Context.** Once public, superdevkit is to be downloaded as a release from GitHub. ADR record 31
said the kit brings only dampf and the user provides the compilers, and left open what a user
installs besides; the roadmap had the language of the tools as an open question. To use the kit
today a user installs a JDK 25, gcc (dampf preprocesses with `gcc -E`; on Windows that means
MinGW for this alone), Python 3 (the graphics converter), and for the desktop a C compiler.
The repository's top level mixed what a user gets with what only the kit's developers need.

**Decision.**

- A release is one zip per system (`superdevkit-<version>-linux-x64.zip`, `-windows-x64.zip`),
  built by GitHub Actions on a version tag after `./gradlew build`:

  ```
  bin/       superdevkit, dampf (and .bat): the launchers
  java/      the jars of dampf and the CLI
  jre/       a Java runtime cut down with jlink: no Java to install
  include/   dampf's own headers (stdint.h, string.h, dampf.h)
  lib/       the kit's library, as in the repository
  raylib/    the pinned raylib build for this system (zlib licence)
  tools/     the converter, brr_lab.html, sdevkit_rename
  examples/  the examples, also the templates of `superdevkit init`
  README.md, LICENSE, NOTICE, VERSION
  ```

- Before the first public release, dampf gets its own preprocessor (roadmap M10) and the
  graphics converter becomes Java in the CLI, so building for the SNES needs nothing but the
  download; gcc stays in the tests as the oracle. A C compiler stays needed for the desktop
  (record 31, 39).
- The repository's top level shows what the release holds, plus the folders for developing the
  kit: `tools/` holds only the tools for game developers (the desktop's build scripts are in
  `dev/` until the CLI replaces them, the tools' tests in `src/test/python`), the book's Node
  files are in `docs/book`, the logo in `docs/assets`, the splash's source picture in
  `dev/art/splash`. With the CLI (M7), `src/` becomes `compiler/` and the CLI `cli/`, two Gradle
  subprojects (record 29).

**Alternatives.**

- A JDK as a prerequisite: 40 MB less per zip, one more installation for every user.
- raylib fetched on the first desktop build: smaller, but needs the network then and could drift
  from the pinned version.
- gcc and Python as prerequisites of the first release: no work before it, but on Windows two
  installations for a kit that is meant to be simple.

**Consequences.** The first public release waits for the preprocessor and the converter in
Java. The zip is about 40 MB larger for the runtime. The packaging task is mostly a copy of
folders of the repository, since the layouts match. Record 31 is amended: the kit brings a Java
runtime and raylib besides dampf; record 29 is amended by the layout above.
