# ADR-6F60ZB4N: The book is brought in step before a release, not with every change

**Context.** Since the book was generated from specs, `BookSourcesTest` was part of
`./gradlew build` and failed as soon as the spec or a source of a generated page had changed
since the page was written. Each change had to be followed by `/docs-gen` for the pages it left
behind, or by a stamp without regenerating. The sources are wide: ten specs list
`docs/adr/*.md`, most guide pages list `README.md`, a page has about 50 source files. A new
record or a sentence in the README made a dozen pages stale, and regenerating one means reading
all of its sources, rewriting it, checking its examples with dampf and gcc, and building the
site. Stamping itself takes seconds. Regenerating took most of the time a small change cost, and
mostly for pages that say nothing about the change.

**Decision.**

- The book is for the users of the kit, and it describes a release. While the kit is developed,
  the documents in `docs/` (and the READMEs) and the code are what the developers go by; they
  are kept current with every change, as before.
- Whether the generated pages are current is no longer checked by `./gradlew build`. The check
  is a task of its own, `./gradlew checkBook`, which lists the stale pages with the files that
  changed and fails if there are any.
- Before a release, that is before the version in `build.gradle.kts` is raised and tagged
  (ADR-6F3CJT1P), `/docs-gen stale` brings the stale pages in step and `checkBook` passes. The
  release build on GitHub Actions (ADR-6F60Z9QJ) runs `checkBook` after `./gradlew build`.
- Between releases the book may lag behind the code. A change regenerates no page and stamps
  none; the book is updated once, at the end of the release cycle.
- What stays in `build`: the lock names only pages that have a spec, every source pattern of a
  spec matches a file, every spec has its page with the generation marker. These are cheap and
  do not ask for a page to be rewritten.

**Alternatives.** Narrower sources, each spec naming the records and documents it takes facts
from instead of `docs/adr/*.md` and `README.md`: fewer pages go stale per change, but every
change still costs a regeneration somewhere, and a spec whose sources are too narrow lets the
page go wrong unnoticed. It remains worth doing for the release itself. A warning instead of a
failure in `build`: nobody reads warnings of a passing build, and the release would still need
a gate.

**Consequences.** A change no longer needs `/docs-gen` or `stampBook` to pass the build. The
book in the repository can describe an older state than the code between releases; the book on
GitHub Pages is published from a release, so its readers see a book that matches the version
they download. The work of a release grows by one `/docs-gen stale` over everything that changed
since the last one. `doc-specs/README.md`, the `/docs-gen` skill and CLAUDE.md say so.
