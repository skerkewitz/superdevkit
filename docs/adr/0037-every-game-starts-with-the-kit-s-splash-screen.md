# 37. Every game starts with the kit's splash screen, and a button shortens it

**Context.** Games made with Unity, Godot or Unreal start with the engine's logo, and so tell
whoever plays them what they were made with. The user wants the same for every game made with
superdevkit, without a line of the game's code, on every target
([splash-screen-proposal.md](../archive/splash-screen-proposal.md)). A splash in the library runs before
`game_init`, so it must leave the console as the game expects it, and it must not disturb the
checks of the examples, which count ticks by the copy of the sprite table and compare the SNES
with the desktop picture by picture.

**Decision.** The library's main loop shows the splash before `game_init`, on the SNES
(`lib/snes/main.c`) and on the desktop (`lib/desktop/main.c`), from one description of its course
(`lib/splash/splash.h`): the logo (`lib/splash/splash_logo.h`, made by `dev/art/splash.py` from the
user's design) fades in over half a second, stays three seconds, and fades out over half a second;
nothing else moves. A button pressed while it fades in or stays ends it as soon as it has faded in,
without the fade out. It cannot be switched off. Afterwards the PPU is reset to power-on again,
without touching the sprite table, so `game_init` finds what it found before, and no tick of a
check moves.

**Alternatives.**

- The cartridge going in, the buttons lighting up, a shine across the text (the proposal's
  movements): left out by the user's choice; fading is enough.
- A splash that cannot be skipped, as in the free versions of the engines: three and a half
  seconds at every start are long while a game is developed; a button shortens it to the fade in.
- A build option to leave it out: not needed, since a button shortens it and the checks and
  replays do not depend on its length.

**Consequences.** Every ROM and every desktop build is about four seconds longer to start, half a
second with a button pressed. The checks of the examples are unchanged; a check can take pictures
of the splash (`splash N` in `check.txt`), and hello's does. The replay test does not show it: it
starts a game through `sdevkit__start`, not through `main`. Blubber Blasen's ROM built with the kit
shows it too, and its ROM check, which counts frames, starts four seconds later.
