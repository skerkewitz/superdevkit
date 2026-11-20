# Proposal: a spec of the game, and the AI builds the game from it

Status: proposal, 2026-11-20. Nothing is built or scheduled; superdevkit itself comes first.

## The idea

A game of superdevkit need not start as C code. Its maker writes a spec of the game, alone or
with an AI, and an AI builds the game from it: the code, the data, the flow. The maker reads the
spec, plays the game, and asks for changes in the spec's terms instead of the code's. The spec is
the source of truth; the code follows it and is not changed by hand.

Today it goes the other way. The user asked for "a game like Boulder Dash", an AI wrote
Steinschlag, and the game has the AI's ideas in it and none of the user's. To change it, the user
has to describe a change to code they have never read.

## What the kit gives, and what it does not

The kit gives no format for the spec. A format good for a jump and run is a poor fit for a chess
game or a racer, and nobody knows what its users will want to make. A format would limit them
more than it helps. The book can describe the way of working and show an example.

What the kit can give is what any spec needs, whatever its shape:

- The content tools, so the levels, pictures and sounds the spec talks about come from the
  maker's own files ([studio-editor-proposal.md](studio-editor-proposal.md)).
- A way to check behaviour: set up a state, play some input, compare the state after. A spec
  with such examples is one an AI can test its own code against. It is a command of
  `superdevkit`, the checks of the examples grown into something a game can use.

## Trial

Writing a spec of Steinschlag from its code as it is today shows how much design is hidden in
the code: a boulder on a round top rolls left before it tries right, the caves come from a random
seed, the cave moves every 8 frames. Each is a decision nobody took. The user reads the spec,
looks for the fault that makes the game unplayable now without being told where it is, changes
the spec, and an agent that sees only the spec builds the game anew.
