# ADR-6FHEEVST: Release zips are checked on their own system and published from a draft

**Context.** ADR-6F60Z9QJ decided that a release is one zip per system with a Java runtime and
raylib, built by GitHub Actions on a version tag. Building it raised questions that record leaves
open: how a zip is known to work, above all on Windows, where the kit had not been run from a
package; which parts of Java it holds; and what a user without a C compiler can do with it.

**Decision.**

- `./gradlew releaseZip` writes the zip of the system it runs on, and each zip is built on its
  own system in `.github/workflows/release.yml`, for Linux and Windows on x64.
- Each zip is then checked as a user gets it (`dev/check_release.sh`, `dev/check_release.ps1`):
  unpacked into a folder with a space in its name, with `JAVA_HOME` pointing nowhere, it builds
  `hello` for the SNES and for its desktop with the raylib it holds. A pull request that touches
  the packaging runs this as a trial.
- A version tag also runs `./gradlew build` and checks that the tag is the version in the build
  file. If both zips pass, the workflow makes a **draft** release with the zips and their
  checksums. The user tries a zip and publishes the draft.
- The launchers take the zip's `jre/` before any Java of the machine.
- The runtime holds the modules `jdeps` finds in the jars, so a new dependency on a module cannot
  be forgotten, and has no class data sharing archive: it would add 14 MB to each zip for about
  0.4 s on a build of `hello`.
- For Java's licence (GPL v2 with the Classpath Exception), the zip keeps `jre/legal/`, and
  `NOTICE` names the exact Temurin build and links its release, which holds the matching source
  archive. The workflow pins that build and fails if `NOTICE` names another.
- A target needs only its own tools: the SNES nothing, win64 Visual Studio, linux a C compiler,
  web Emscripten. A missing tool fails that target's build with a message that says what to
  install and that `superdevkit build snes` works without it.

**Alternatives.**

- Publishing directly from the tag: one step less, but a broken zip would be public before anyone
  tried it.
- Cutting both runtimes on Linux from a Windows JDK's modules: one job, but the Windows zip would
  still have to run on Windows to be known to work.
- Attaching Java's source archive to every release: the plainest way to meet section 3 of the
  GPL, but a 121 MB file per release for a 26 MB download. Cryptomator, JabRef and Scene Builder,
  which ship a packaged Java, attach none.
- A written offer in `NOTICE`: no extra file, but a personal promise for at least three years to
  send the source to anyone who asks.
- Falling back to the SNES when a desktop tool is missing: the first command would then succeed,
  but it would build something other than what was asked for, and playing the ROM needs an
  emulator the zip does not hold.

**Consequences.** A zip is about 27 MB. Every release gets one manual step, publishing the draft.
`superdevkit run` without a target still builds for the machine's system, so on Windows it needs
Visual Studio and otherwise stops with the message above. GNU's GPLv2 FAQ is stricter than a link
to another site's copy, since it asks for an arrangement with that site. The project accepts this
for an unmodified Temurin build; if Adoptium removed that release, the source would have to be
hosted here. A new Java version means changing the pin and `NOTICE` together.
