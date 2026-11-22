# The web's check

`web_check.js` plays a `check.txt` in a game's web build (`superdevkit build web`) in headless
Chromium, as the desktop's program plays it with `--check`:

```bash
node tools/web_check/web_check.js examples/catchme/build/web examples/catchme/check.txt out [--record out/recording.txt]
```

The page comes from the build folder through Playwright's routing, so no server or port is
involved. When the runtime is up, the script writes `check.txt` into the page's file system
(`Module.FS`) and runs the game's `main` with `--check /check/check.txt --out /check`, which the
web's `main` takes like the desktop's (`lib/desktop/main.c`). Then it copies what `main` wrote
(`hashes.txt`, `sound.txt`, the screenshots, the recording) into `out`. What `main` printed goes
to stdout and stderr, and the exit status is `main`'s: 1 when a byte was lost or a recording's
checksum differs, 2 when the page did not start or the file is wrong.

The check runs no window and no frame clock: it is the game, Rachael and the DSP compiled by emcc
to WebAssembly, against the same code from gcc on the desktop and from dampf on the SNES.
`ExamplesTest` runs it for every example and compares the results with the SNES's
([docs/testing.md](../../docs/testing.md)).

Once, to install Playwright (the same version as the BRR Lab's tests, so the browser in
`~/.cache/ms-playwright` serves both):

```bash
cd tools/web_check
npm ci
npx playwright install --only-shell chromium
```

`ExamplesTest` skips the web where Playwright is not installed here, where emcc does not run, or
where raylib was not fetched for the web (a `superdevkit build web` of any example fetches it).
