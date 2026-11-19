# Proposal: a splash screen in every game made with superdevkit

Status: proposal, 2026-11-19. Nothing is built yet.

## What

Every game built with the kit starts with a short splash screen, as games made with Unity, Godot
or Unreal do: the kit's logo fades in from black, stays two seconds and fades out, then the game
starts. It is in the library, not in the game, so every game has it without writing a line, on
every target: the SNES, and Rachael on the desktop and the web. It tells whoever plays a game what
it was made with.

The design is the user's picture of 2026-11-19: a SNES pad in pixel art with a cartridge on top,
and beside it the text "DONE WITH" above "SUPERDEVKIT", "DEVKIT" brighter than "SUPER", in grey
and white on black.

## The picture on the SNES

The picture as it is cannot be used directly. It is 1408 x 768 pixels, the SNES shows 256 x 224,
and scaled down by 5.5 its soft edges and the letters of "DONE WITH" (about 50 pixels high, then
9) turn to mush. And it is not pixel art on a grid: the pixels of the pad have uneven sizes and
blurred edges.

So the logo is drawn again at the SNES's own resolution, with the picture as the design: a script
`dev/art/splash.py`, like the examples' graphics, draws it pixel by pixel and `png2snes.py` turns
it into tiles. About 200 x 48 pixels in the middle of the screen, the pad about 56 x 32, the text
in a pixel font of 8 to 16 pixels in height; at most 16 colours on one palette, which the picture
almost is already (black, three greys, white, the lilac of the cartridge and the buttons' four
colours). On a black screen that is about 150 tiles of 4 bits per pixel, under 5 KB of ROM, and the
map of one layer.

## How it runs

In the library's main loop, before `game_init`, on the SNES (`lib/snes/main.c`) and in Rachael's
frontend (`lib/desktop/main.c`) the same way:

1. With the screen off: the tiles, the palette and the map into VRAM and CGRAM, mode 1, one layer
   on.
2. Fade in: the brightness from 0 to 15 over half a second (2 frames a step).
3. Two seconds of the logo.
4. Fade out the same way.
5. The video reset of `sdevkit__start` again (VRAM, CGRAM, OAM and the registers as at power-on),
   so `game_init` finds the console in the state it finds today and no game has to know about the
   splash.

Three seconds in all. The pads are read but do nothing: the splash cannot be skipped, as in the
free versions of the engines named above, and three seconds are short enough for that.

It does not count as ticks: the checks of the examples count `game_tick`s (`example_check.lua`), so
`check.txt` stays as it is, as long as the splash does not go through the hook the check counts
ticks by (the OAM copy at the end of a frame). The replay test does not get it: it starts the
game through `sdevkit__start`, not through `main`.

## Two things to settle first

**"DONE WITH" or "MADE WITH".** The engines say "Made with Unity", "Made with Godot"; "made
with" is the usual English for it, "done with" reads as "finished with". I would write "MADE
WITH SUPERDEVKIT".

**The pad and the cartridge are Nintendo's design.** The shape of the SNES pad, its four coloured
buttons and the cartridge's outline are recognisable as Nintendo's, and the splash is in every
game made with the kit once it is published. That can read as Nintendo endorsing the kit, which a
homebrew tool should avoid. Before publishing, either a pad and a cartridge of our own shape
(rounded like a pad, but not that pad: other button colours, no lilac), or advice on whether the
picture as it is is fine. For the private repository it does not matter yet.

## Open questions

- Can a game switch it off? The user's idea is that it is always there. A build option for
  development only (the checks and the replays would not need it anyway) is enough for impatient
  testing, and a release build always shows it.
- A jingle with it, once the kit has sound effects ([sound-effects-proposal.md](sound-effects-proposal.md))?
  Short, a few KB of BRR, loaded with the effects and played as the logo fades in.
- PAL: two seconds are 100 frames there, not 120; the splash counts time in frames, so it lasts a
  sixth longer on PAL, as the games do (ADR record 36).

## Steps

1. `dev/art/splash.py`: the logo at the SNES's resolution, its tiles and palette as C in the
   library (`lib/splash/` or next to `main.c`), with the answer on "MADE WITH" and on the pad.
2. The splash in `lib/snes/main.c` and `lib/desktop/main.c`, the video reset after it.
3. The examples' checks unchanged and passing; one screenshot of the splash in a check, the SNES
   and Rachael the same pixel for pixel, also in the middle of a fade.
4. An ADR record (every game shows it; whether it can be switched off), a row in `lib/README.md`.
