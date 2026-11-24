# ADR-6FKTVDE8: A hardware feature is usable only when the SNES and Rachael both draw it

**Context.** The SNES hardware guide (2026-11-19, `lib/SNES_HARDWARE.md`) left open whether a
feature that Rachael does not draw, but reports when a game uses it, is enough to let a game use
that feature. Since then Rachael counts every register write she does not draw (a mode other
than 1 and 7, mosaic, interlace, high resolution, EXTBG, direct colour, the ports of the video
memories), and the desktop check prints `undrawn N` and fails on it. In practice the question
was answered "no", but it was still listed as open in the roadmap.

**Decision.** A game may use a feature of the picture only when `lib/snes` and Rachael both draw
it. A write Rachael only reports fails the desktop check, as a lost byte does. A feature a game
needs is built on both platforms in the same change, and its row in the guide's table says so
(`SnesHardwareGuideTest`).

**Alternatives.** Let a reported feature be usable, with a warning instead of a failure: a game
gets the feature sooner, but the desktop then shows another picture than the SNES, and every
check of that game on the desktop and the web says nothing about its picture.

**Consequences.** A new feature of the picture costs its implementation in Rachael too, and
waits until it has one. The rule is about the picture: music, which plays on the desktop and is
a stub on the SNES, stays as ADR-6F3CK03Z has it.
