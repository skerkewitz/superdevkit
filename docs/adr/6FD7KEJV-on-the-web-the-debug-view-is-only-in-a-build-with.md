# ADR-6FD7KEJV: On the web the debug view is only in a build with --debug

**Context.** ADR-6FCVAA30 gave the web every feature of the desktop's window, the debug view
(F1) and its run keys (F5 pause, F6 a tick, F7 fast, F8 slow motion) included. A desktop build is
for the developer. A web page is also what a game puts online for its players, and on such a page
F1 would show them the view: pause and slow motion, the video memory, and whatever the game
registered there. For Blubber Blasen that includes invincibility (finding 3 of
[game-findings.md](../game-findings.md)). The page also kept F1 and F5 to F8 from the browser, so
F5 did not reload a public page.

**Decision.** On the web the debug view and its run keys are only in a build with `--debug`
(`superdevkit build web --debug`, `superdevkit run web --debug`). That build is also compiled
with `-O0 -g`, as before. It defines `SDEVKIT_WEB_DEBUG` for every file, the game's too, so a
game can tie its own debug code to the same build. Without `--debug`, `lib/desktop/main.c` does
not read F1 or F5 to F8 on the web (`DEBUG_VIEW`), and the page leaves those keys to the browser.
The desktop keeps the view in every build.

The frontend now reads every key it acts on before `EndDrawing()`. On the web the browser
delivers key events while the loop waits for the next frame, and `EndDrawing()` marks them as
seen, so a key read after it was never "pressed" there: F1, Tab and F12 never worked on the web
before this record.

**Alternatives.**

- A switch in `superdevkit.toml` (say `[web] debug = false`): a public page would be one setting
  away from a page for debugging, and the setting is easy to forget either way. `--debug` is
  already the build for debugging.
- The view in every web build, and the game keeps what it registers out of public builds: the
  kit's own part (pause, slow motion, the video memory) would still reach the players.
- No debug view on the web at all: the browser is where a bug that only shows there is found, and
  the view is the kit's debugger.

**Consequences.** A page built without `--debug` is ready to put online: F1 does nothing, F5
reloads the page. F11 (the recording) and F12 (a screenshot) stay in every build as downloads. A
game whose debug code depends only on its compiler (as Blubber Blasen's does, on "not dampf")
still compiles it into a public page, unreachable through the view; it can test
`SDEVKIT_WEB_DEBUG` on the web. On the desktop nothing changes that a player sees: `EndDrawing()`
polls a key at the end of one frame, and the next frame acts on it, before and after this record.
F12 now saves the picture of that next frame.
