# Proposal: a splash screen in every game made with superdevkit

Status: built, 2026-11-19 (ADR record 37), with two changes by the user: no movement besides the
fades, and the logo stays three seconds; a button pressed while it fades in or stays ends it once
it has faded in. "Movement" below and the open question on switching it off are therefore not
built.

## What

Every game built with the kit starts with a short splash screen, as games made with Unity, Godot
or Unreal do: the kit's logo fades in from black, stays two seconds and fades out, then the game
starts. It is in the library, not in the game, so every game has it without writing a line, on
every target: the SNES, and Rachael on the desktop and the web. It tells whoever plays a game what
it was made with.

The design is the user's picture, `assets/splash/logo_source.png` (239 x 52 pixels, 2026-11-19):
a SNES pad in pixel art with a cartridge on top, and beside it two lines of text, "SUPERDEVKIT"
below with "DEVKIT" brighter than "SUPER", in grey and white on black. The text of the first line
is "MADE WITH", as the engines have it ("Made with Unity"); the picture still says "DONE WITH",
which read as "finished with".

## The picture on the SNES

The picture is close to the SNES's resolution already. Padded to 240 x 56 it is a block of 30 x 7
tiles in the middle of the screen. Mapped to a palette chosen by hand, black, six greys, white,
the cartridge's lilac and the four buttons' colours, it needs 14 of a palette's 16 colours and 175
different tiles, 5.6 KB of ROM and VRAM at 4 bits per pixel, and everything stays legible.

It is not clean pixel art, though: it has 63 colours, the black is about ten different near-black
tones, there are single grey and lilac specks in the dark parts of the pad, and the edges of the
letters have uneven grey pixels left from smoothing. On the SNES that looks restless, and the
specks make almost empty tiles all different.

So the source stays the design, and a script `dev/art/splash.py`, like the examples' graphics,
makes the SNES's picture from it: the fixed palette, everything near black made black, single
specks removed, and the text set again with clean edges, "MADE WITH" in place of "DONE WITH".
`png2snes.py` turns the result into tiles. A colour is then changed in the script, not in an image
editor.

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

## Movement

A still logo is enough; a little movement makes it better, and it can show what the kit can do,
as long as it uses only what Rachael draws today (the rule of the hardware guide: mosaic and
mode 7, the obvious tricks, are not in Rachael yet). Three ideas, each cheap:

- **The cartridge goes in.** The cartridge is a sprite (32 x 16, two sprites of the large size)
  that drops from above into the pad's slot, slows down and settles with a small bounce; then the
  text appears. Once there is sound, the jingle starts on the click.
- **The buttons light up.** The four coloured buttons light up one after the other, by changing
  their four palette entries frame by frame: a few bytes of CGRAM a frame, no tiles.
- **A shine runs over the text.** A narrow bright band moves diagonally across "SUPERDEVKIT": a
  window whose edges HDMA moves line by line, and colour math that brightens what is inside it.
  That shows HDMA, windows and colour math, three things the examples hardly use, and Rachael
  draws all three.

In this order the splash takes about three and a half seconds: the cartridge half a second, the
buttons and the shine together one second, holding a second, fading out half a second. Every frame
of it is the same on the SNES and on Rachael, so a check can take screenshots in the middle of
each movement.

## Nintendo's names and logos stay out

The pad and the cartridge show what the kit is for, as the many SNES-style pads sold without a
licence from Nintendo do: the design protection of the 1990 pad ran out long ago, and the shape
alone is hard to claim as a trademark. What those pads avoid is Nintendo's names and logos, and so
does the splash: our own pixel art of a pad and a cartridge, and no "Super Nintendo", "Super
Famicom" or "SNES" logo, no Nintendo logo, nothing that reads as official or licensed. This is a
judgement, not legal advice.

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
   library (`lib/splash/` or next to `main.c`), from `assets/splash/logo_source.png`.
2. The splash in `lib/snes/main.c` and `lib/desktop/main.c`, the video reset after it; first
   still, then the movements one by one.
3. The examples' checks unchanged and passing; one screenshot of the splash in a check, the SNES
   and Rachael the same pixel for pixel, also in the middle of a fade.
4. An ADR record (every game shows it; whether it can be switched off), a row in `lib/README.md`.
