# ADR-6FEWEE95: A project can define macros per target for its own files

**Context.** The kit passes a game no defines of its own, so a game that wants code only in some
builds has to key it on the compiler (`__DAMPF__`, `__EMSCRIPTEN__`). Blubber Blasen does that for
its debug code. `lib/README.md` says game code holds no platform `#ifdef`s and asks for a feature
instead of a platform, and a game has no way to name such a feature for one target.

**Decision.** `superdevkit.toml` takes the tables `[snes]`, `[desktop]` (`linux` and `win64`) and
`[web]`, each with `defines`, a list of `NAME` or `NAME=VALUE` without spaces. They go to the
game's own files on that target (also into `build/snes/<name>.args`), and not to the kit's library
or frontend.

**Alternatives.**

- A table per target name: `linux` and `win64` are the same game on the desktop, and one more place
  to keep equal.
- One list for every target: a define that is the same everywhere belongs in a header.
- Defines for the kit's files too: a game could then change how Rachael or `lib/snes` work, and the
  checks between the SNES and the desktop would no longer compare the kit's code.

**Consequences.** A game can name a feature for a target (`DEV_TOOLS` on the desktop) instead of
testing the compiler. A define that makes the game compute differently on two targets also makes
their checksums differ, which the checks then report.
