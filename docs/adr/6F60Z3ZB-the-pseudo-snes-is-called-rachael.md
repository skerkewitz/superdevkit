# ADR-6F60Z3ZB: The pseudo SNES is called Rachael

**Context.** The kit's implementation of the console API on the PC was called "the pseudo SNES"
(`lib/pseudo`), the name it had in Blubber Blasen's `src/snes`. It is the SNES rebuilt in portable
C: the memories, the registers, DMA and HDMA, and a renderer that draws line by line as the PPU
does. It runs no 65816 code. The desktop and, from M8, the web share it; only their frontends
differ. Writing about it was awkward: "pseudo SNES" sounds like a rough imitation, it is easily
taken for an emulator, and "the desktop" or "the PC build" were used for it as well, although
they name a platform and a build, not this part of the kit. The game's own copy keeps the old
name.

**Decision.** The pseudo SNES of the kit is called Rachael: the folder `lib/rachael`, its
internal header `rachael.h`, its state `sdevkit__rachael` (type `sdevkit__rachael_state`). The
documents say "Rachael" for it, "on Rachael" for a game running on it, and keep "desktop" and
"web" for the platforms and their frontends (`lib/desktop`, `lib/web`). What it is, and the name,
is explained once, in [lib/README.md](../../lib/README.md#rachael), with an entry in the book's
glossary; everything else links there. The name is the replicant of Blade Runner who is so close
to the real thing that it takes a very long Voight-Kampff test to tell; Rachael's promise is the
same picture as the SNES, pixel for pixel.

**Alternatives.**

- Twin: clear, but a common word, unique only together with "SNES".
- Deckard: the same film, but Deckard Cain of Diablo, and Valve's use of the name as a code
  name, are better known among the people the kit is for.
- Pris: short, but her story is not about passing for the real thing, and the word is an
  ordinary one in French and the Scandinavian languages, which makes it hard to search for.
- Replica, Doppelgänger, Stand-in, Kulisse: Replica sounds like a hardware clone, Doppelgänger
  has an umlaut, Stand-in a hyphen, and Kulisse means nothing to an English reader.

**Consequences.** A rename of one folder, one header and one internal variable; no name a game
uses changes (ADR-6F3CK2AR), so no game and no example changes. ADR records written before keep
"pseudo SNES". The game's own `src/snes` is still its pseudo SNES until it uses the kit's desktop
build.
