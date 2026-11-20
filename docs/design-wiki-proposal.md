# Proposal: the game's design as a wiki, and the AI builds the game from it

Status: proposal, 2026-11-20. Nothing is built, and nothing is scheduled: superdevkit itself
comes first, until it does everything a game needs without the shell scripts around it. This
file keeps the idea until then.

## The idea

Today a game of superdevkit is its C code. Nobody writes that code by hand any more: the user
says "a game like Boulder Dash", an AI writes Steinschlag, and the game that comes out has the
AI's ideas in it and none of the user's. To change it, the user has to describe a change to code
they have never read.

Instead, the game is described in a wiki of its design: its objects, how they behave, its
levels, its screens, its numbers. The wiki is the only source of truth. The AI builds the game
from it (the C code, the data tables, the levels) and keeps the game in step when a page
changes. The user reads the wiki, plays the game, and says what to change in the wiki's terms:
"FELS-1: pushing should work on the first push", "cave 2 has one beetle too many".

That is the way the book works already: `doc-specs/` describes the pages, `/docs-gen` writes
them, `checkBook` lists the stale ones. The wiki does the same one level higher.

It is for people who want to make games and are not programmers. They have to keep an
overview, so the wiki has a fixed structure and every page of a kind looks the same.

## The wiki

A folder `spec/` in the game's folder, Markdown with `[[links]]`. Obsidian opens such a folder
as a wiki, with the links and a graph of them, so a user needs no editor of the kit.

```
spec/
  game.md            the start page: the idea in a few sentences, the overviews, links to all
  flow.md            the screens and how one leads to the next
  controls.md        button and what it does
  terms.md           tick, cell, neighbour, ...
  objects/           one page per game object: miner.md, boulder.md, beetle.md, ...
  properties/        one page per property: falls.md, rolls.md, round.md, deadly.md, ...
  levels/            one page per level
```

**Objects.** Every page has the same sections: a sentence of what it is, its picture, its
properties, the behaviour that is its own, and what happens when it meets each other object.

**Properties** work like keywords in card games ("flying"). A behaviour that several objects
share is written once, on the property's page, and the objects only list it. A boulder and a gem
both *fall*, *roll* and are *round*; the rule for falling is on `falls.md`, and changing it
changes both.

**Rules** are numbered within their page (`FALLS-1`, `BOULDER-1`), one "when ... then" sentence
each, so the user can point at one. A rule that needs loops or counters to say is too
technical: it belongs to the code.

**Levels** have their goal, their time, the numbers that differ from the standard, and their
map. For a game on a grid the map is a block of characters with a legend that links to the
objects; a larger or more graphical game links a Tiled file instead.

**Numbers** that the user will tune (the length of a tick, the time of a level, the speed of a
beetle) are in tables, one home each, so the build can turn them into a header and the desktop
build can let them be changed while playing and write them back.

**Scenarios** make the wiki checkable. A scenario is a small map before, the input, and the map
after some ticks:

```
before   1 tick   2 ticks
 O        .        .
 .        O        x      the miner is dead
 M        M        x
```

The kit runs it in `dampf.emu` and compares. Where the prose is ambiguous, the scenario decides,
and the AI checks its own code against all of them.

**Overviews** on the start page: a table of all objects and their properties, and a matrix of
what happens when one object meets another. The whole game fits on one page there.

What the player never notices (VRAM, the frame budget, how the physics is spread over frames)
is not in the wiki; the AI decides it. What the player does notice must be in it, or marked as
open. Writing Steinschlag's rules down shows how much of that is hidden in code today: a boulder
on a round top rolls left before it tries right, the caves are made from a random seed, the cave
moves every 8 frames. Each is a design decision nobody took.

## Who does what

The user writes and changes the wiki and plays the game; the ideas, rules, levels, feel,
pictures and music stay theirs. The AI builds the game from the wiki and finds what does not fit:
two pages that contradict each other, an object without a rule for meeting another, a level that
cannot be won, code older than the page it comes from.

Two rules keep the two in step. The code is not changed by hand: a fault is fixed in the wiki,
and the code follows. And the numbers belong to the wiki: a value tuned while playing is written
back to its table.

## A first trial on Steinschlag

1. Write Steinschlag's wiki from its code as it is today, with the caves turned into fixed maps.
2. The user reads it and looks for the fault that makes the game unplayable now, without being
   told where it is. If it shows in the wiki as a rule that reads wrong, the format works; if it
   does not, the format misses something.
3. The user changes the wiki to their liking.
4. An agent that sees only the wiki, not the code, builds the game anew. Playing and the
   scenarios show what the wiki left unsaid.

A few days of work. If it works, `superdevkit init` writes an empty wiki with the page templates
next to `src/`, and the book gets a chapter on it.

## What it needs from the kit

- Running a scenario: a game's state set from a map, input for some ticks, the state compared
  with a map. A command of `superdevkit`, not one more script.
- Building the game's data from the wiki: the numbers as a header, the maps as tables, as the
  build does for `music.h` and the sound effects today.
- Knowing which code is older than its page, as `checkBook` knows for the book.

## Open questions

- The language of the wiki: the user's own (German for Steinschlag) or English like the rest of
  the repository.
- How strict the format is: conventions that the AI reads, or a checker that rejects a page with
  a missing section.
- How a scenario names a state that is not on the map (the score, the time, a beetle's direction).
- Games that are not on a grid: how a level and a scenario are written for them.
- Where it is recorded which page a piece of code comes from, so that a changed page finds the
  code to build again.
- Whether this becomes the kit's way to make a game, which would be an ADR record.
