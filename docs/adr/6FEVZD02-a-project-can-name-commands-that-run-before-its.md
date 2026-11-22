# ADR-6FEVZD02: A project can name commands that run before its build

**Context.** A game with converters of its own (levels, its own graphics formats) has to run them
before `superdevkit build`, into a folder it lists in `sources`. Blubber Blasen wraps the command
in a script for that reason, so `superdevkit build` alone is not the whole build of such a game.

**Decision.** `superdevkit.toml` takes a table `[build]` with `before`, a list of commands. Every
build and run runs them first, in the project's folder, each through the system's shell (`sh -c`,
on Windows `cmd /c`), for every target. A command that ends with another status than 0 stops the
build with its output. The commands run before the build's plan is made, because they may write
files that `sources` lists; a source that is still missing afterwards is an error. `--plan` and
`--dry-run` show them and run nothing, so they do not report as missing what the commands would
write.

**Alternatives.**

- Commands per target: no game needs it yet, and a converter's output is the same for every target.
- A plain argument list without a shell: a converter's call is usually a line with redirections or
  `&&`, and a project's file is the developer's own code either way.
- Declaring what each command writes, to skip commands that are up to date: the kit's build has no
  dependency tracking anywhere else; a converter can skip its own work.

**Consequences.** `superdevkit build <target>` can be the whole build of a game with its own tools.
A project file can now run commands on the machine that builds it, so building a game from someone
else is running their code, as with a Makefile. A dry run checks less for such a project: what the
commands may write is not checked.
