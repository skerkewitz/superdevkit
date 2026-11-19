# Proposal: Er-Tüp, a side-scrolling shooter as an example of superdevkit

Status: built (2026-11-19): the checks compare a checksum per tick (ADR record 38), and all three
stages play with their bosses, the third in mode 7, and end the game (`examples/ertuep`, its
README says what is in it). Open: sound, which waits for the kit's (sound-effects-proposal.md).

What the building changed against the text below: positions are 12.4 fixed point, not 8.8 (the
screen is 256 pixels wide, and objects live beside it); the frame is guarded by a measured budget
in `check.txt`, not by `DAMPF_BUDGET`, whose static worst case of the pair loop is a thousand
times what play takes (roadmap, M11); the title has a demo that flies the busiest waves, which is
the benchmark; the kit got `sdevkit_dma_copy_vram_column`, `tiles2` and `mode7` in `png2snes.py`,
a profile per C line, and dampf pointers to arrays and compound assignments to an lvalue with side
effects. Stage 3 is seen from above and flown as a loop through the tent, the floor turning at the
corners; the boss turns with its picture as planned. The densest waves of stage 2 had never been
measured: they took two frames a tick, and a wave of twelve flies broke a hint (`DAMPF_RANGE`) and
stopped the desktop build there. The pair loop now sorts the shots into buckets by x, the
densest waves have eight flies, and the demos cover them.

## Why this game

The examples so far are a chess game, a cave with falling boulders, a maze and a sprite that
moves. None of them has the code that most SNES games are made of and that dampf has only met in
Blubber Blasen: many objects in struct tables updated through pointers, movement in 8.8 fixed
point, a pair loop for collisions, particles, enemies as state machines, and a frame so full
that it decides whether the game runs at 60 frames a second. The roadmap ("Publishing") asks for
exactly that before Blubber Blasen's snapshot can leave the repository. A side-scrolling shooter
has all of it, and its busiest wave is a benchmark of its own.

## What it is

Er-Tüp is a side-scrolling shooter that makes fun of its genre and of the R-Type games of the
SNES in particular, with the means of a German pub: the companion that docks to the ship is a
crate of beer, the charged shot a bottle of sparkling wine, the slowdown the genre is known for a
power-up nobody wants. It is a parody, not a copy. Its name is a pun of its own.

What it takes from the genre, as everybody may: scrolling from left to right, waves of enemies,
a companion that docks at the front or the back and can be launched, a shot that charges while
the button is held, a stronger mode that overheats, huge bosses, checkpoints.

What it does not take: R-Type's name, ship, creatures, the Force under any name or look, its
stages, bosses, story, graphics and music. Everything of Er-Tüp is drawn and designed for it.

## The player

- **The ship.** A small flying craft (its look is open; a flying beer tap, *Zapfhahn*, fits the
  world), eight directions, a hit box smaller than its picture.
- **The crate (*Bierkasten*), the companion.** It is picked up, docks at the front or the back of
  the ship, can be launched forward with a button and comes back. Docked or flying it blocks
  enemy shots and hurts what it touches. It grows: a six-pack fires single bottles, half a crate
  pairs, a full crate a fan. When it is empty it fires bottle caps (*Kronkorken*): weak, but not
  useless.
- **The deposit (*Pfand*).** Hit enemies sometimes drop empty bottles, which tumble down under
  fixed-point gravity; collected, they are the score, counted in cents and shown as
  "Pfand: 12,75 €". Few players outside Germany get it; that is part of the joke.
- **The sparkling wine (*Sektflasche*), the charged shot.** While the button is held, the bottle
  shakes harder and harder and foam rises in its neck, the charge meter. Released: a cork that
  goes through everything in a line; at full charge it bounces off walls. Held too long, the
  bottle foams over: instead of the cork a wide shower of foam that does little damage. That is
  the parody of the overheating stronger mode: greed is punished, but funnily.
- **The stutter power-up (*Ruckel-Power-up*).** It looks almost like a real one, one colour off.
  Collected, it makes the picture stutter for a few seconds, the logic running on: the picture
  is updated only every fourth frame, every other sprite flickers, the status line says
  "AUTHENTIC SLOWDOWN™", and the joypad answers six ticks late. Then everything is as before;
  it gave nothing. Real slowdown would make dodging easier; this does the opposite.

## The stages

Two stages that are as good as they can be; a third only when the first two are.

1. **Die Kneipe (the pub).** A long side-scroll through bar room, cellar and kitchen. It teaches
   everything: the six-pack, the crate growing, the first bottle of sparkling wine, the first
   stutter power-up. Mid-boss: a slot machine whose reels spin and pay out shots. Boss: *Der
   Pfandautomat*, a bottle-return machine as large as the screen, on a background layer; it
   swallows what is shot into it, spits bottles back ("Flasche wird nicht angenommen") and breaks
   open in phases.
2. **Das Sudhaus (the brewhouse).** Malt and minced pork (*Mett*) grow through the pipes; the
   walls are soft, pulse (animation by tiles) and kill; the screen sways (HDMA). Narrow passages
   where the cork that bounces off walls pays. The densest waves and the most debris: the stage
   of the benchmark. Boss: the brew kettle with a creature of Mett growing out of it, over two
   layers, its tentacles of sprites.
3. **Das Festzelt (the beer tent), if 1 and 2 are done.** A chain carousel in mode 7, a boss that
   turns with it, and the more bottles the player has collected, the more the screen sways.

Each stage has checkpoints (the complaint about the genre's SNES games was having to replay a
whole stage), a mid-boss and a boss with several phases. The effort goes into the bosses.

## On the screen

Mode 1 for stages 1 and 2: BG1 the stage in 16 x 16 tiles, scrolled; BG2 the boss, moved by its
own scroll registers and animated by changing tiles, so it costs no sprites; BG3 the status line.
Sprites for the ship, the crate, shots, enemies, debris: up to 128, at most 32 on a line, which
the desktop check counts (`sprites N`). Stage 3 in mode 7. No sound: the effects come with the
kit's sound (docs/sound-effects-proposal.md), Er-Tüp as its third user after Schach and
Steinschlag.

## What it exercises in dampf

| Code | Where in Er-Tüp |
| --- | --- |
| Object tables of structs, reached through pointers | shots, enemies, debris, pick-ups |
| 8.8 fixed point, signed shifts | every movement, gravity of the empties, paths of the waves |
| A pair loop | shots against enemies, enemies and their shots against the ship and the crate |
| Particles | foam, debris, empties |
| State machines (`switch`) | the crate, each kind of enemy, each phase of a boss |
| Tables in ROM | the waves (when, where, which kind, which path), the stage maps |
| Text and tiles | the status line, the boss layer |

Its code follows `lib/C_STYLE.md` and `lib/SNES_HARDWARE.md`; where a rule turns out wrong for a
game of this size, the rule changes, as the loop counter rule did after Schach.

## How it is measured and checked

- **Frames per tick in the busiest wave** of stage 2, in Mesen, against a target of 1.0; with
  `DAMPF_BUDGET` on the update functions, so that a change that makes the frame too slow fails
  the build.
- **The pictures:** `check.txt` plays the stages with the joypad, and `dev/compare_example.sh`
  compares the SNES and the desktop pixel for pixel, as for the other examples.
- **A replay with checksums:** after each tick a checksum of the game's state, on the PC and on
  the SNES, the same; the examples' counterpart of Blubber Blasen's replay test. Built for all
  examples in step 1.

## Steps

1. The replay check with checksums for the examples (Schach and Steinschlag first, which have
   long games in their `check.txt`).
2. Stage 1 without its boss: the ship, the crate, bottles and caps, waves from a table, the
   empties and the deposit, the sparkling wine, the stutter power-up, checkpoints. The first
   measurement of frames per tick.
3. The bosses of stage 1, on the background layer.
4. Stage 2, its walls and its boss; the benchmark.
5. Only then, and if both stages are as good as they should be: stage 3 in mode 7.

The graphics are drawn by a script in `dev/art/`, as for the other examples.

## Open questions

- **The name.** "Er-Tüp" is a pun on a trademark. In the private repository that is a joke; when
  the kit goes public (roadmap, "Publishing"), a name further from it may be wiser. Changing it
  touches the title screen and the folder name.
- **Beer in an example of a public kit.** It is a parody for grown-ups; whether it stays when the
  kit goes public is decided then.
- **The ship's look**: a beer tap is a suggestion.
