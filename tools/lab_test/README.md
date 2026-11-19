# The BRR Lab's tests

Playwright tests of [`tools/brr_lab.html`](../brr_lab.html) in headless Chromium: opening a folder
with and without `sfx.json`, loading files and copying them into the open folder, the cards
(adding, renaming, moving and removing effects, their sizes, sorting, choosing one by mouse and
by keyboard), dropping WAV files, every setting of a sample and the rate of all, the errors the
build stops on, playing, the voices and priorities, and saving into the folder or as a download. The last test saves a `sfx.json` from the Lab, runs the installed
`superdevkit sfx` on it and compares the BRR, pitch and frames with the ones the Lab shows and
downloads.

The browser's dialogs are not driven: "Open folder" gets a stand-in for the File System Access API
(`window.__folder`, written files in `window.__written`), "Load files" its files through
Playwright's file chooser, and every sound started is recorded in `window.__played`.

Once, to install Playwright and its browser (into `~/.cache/ms-playwright`):

```bash
cd tools/lab_test
npm ci
npx playwright install --only-shell chromium
```

Then `npx playwright test` here, or `./gradlew build` (`BrrLabTest`, which skips with a message
when Playwright is not installed, and installs the distribution first for the last test).
