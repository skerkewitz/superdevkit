// The tests of tools/brr_lab.html in a real browser (Chromium, headless): every use of the page,
// from opening a folder to saving sfx.json, and at the end the bytes it plays against the bytes
// `superdevkit sfx` builds. README.md in this folder says how to run them.
//
// The browser's file dialogs are not driven: the folder of "Open folder" is a stand-in for the
// File System Access API (window.__folder in, window.__written out), "Load files" gets its files
// through Playwright's file chooser, and every sound started is recorded in window.__played.
const { test, expect } = require("@playwright/test");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");

const ROOT = path.resolve(__dirname, "../..");
const LAB = "file://" + path.join(ROOT, "tools/brr_lab.html");
const CLI = path.join(ROOT, "build/install/superdevkit/bin/superdevkit");

/* A WAV file of 16-bit PCM: a tone with an envelope, with silence before and after it. */
function wav({ rate = 16000, seconds = 0.2, freq = 440, amp = 0.5, silence = 0, channels = 1 } = {}) {
  const pad = Math.round(silence * rate), n = Math.round(seconds * rate), total = n + 2 * pad;
  const b = Buffer.alloc(44 + total * 2 * channels);
  b.write("RIFF", 0); b.writeUInt32LE(36 + total * 2 * channels, 4); b.write("WAVE", 8);
  b.write("fmt ", 12); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(channels, 22);
  b.writeUInt32LE(rate, 24); b.writeUInt32LE(rate * 2 * channels, 28); b.writeUInt16LE(2 * channels, 32);
  b.writeUInt16LE(16, 34); b.write("data", 36); b.writeUInt32LE(total * 2 * channels, 40);
  for (let i = 0; i < n; i++) {
    const env = Math.min(1, i / 200, (n - i) / 200);
    const v = Math.round(32767 * amp * env * Math.sin(2 * Math.PI * freq * i / rate));
    for (let c = 0; c < channels; c++) b.writeInt16LE(v, 44 + ((pad + i) * channels + c) * 2);
  }
  return b;
}

const json = o => Buffer.from(JSON.stringify(o, null, 2));
const b64map = files => Object.fromEntries(Object.entries(files).map(([n, d]) => [n, Buffer.from(d).toString("base64")]));

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.__played = [];
    const start = AudioBufferSourceNode.prototype.start;
    AudioBufferSourceNode.prototype.start = function (when) {
      window.__played.push({ rate: this.buffer.sampleRate, length: this.buffer.length, when: when || 0 });
      return start.apply(this, arguments);
    };
    window.__written = {};
    const bytes = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
    window.showDirectoryPicker = async () => {
      const f = window.__folder;
      if (!f) throw new DOMException("The user aborted a request.", "AbortError");
      const file = name => ({
        kind: "file", name,
        getFile: async () => new File([bytes(f.files[name])], name),
        createWritable: async () => {
          const parts = [];
          return {
            write: async d => { parts.push(d); },
            close: async () => {
              const b = new Uint8Array(await new Blob(parts).arrayBuffer());
              let bin = "";
              for (const x of b) bin += String.fromCharCode(x);
              f.files[name] = btoa(bin);
              window.__written[name] = name.endsWith(".json") ? new TextDecoder().decode(b) : b.length;
            },
          };
        },
      });
      return {
        kind: "directory", name: f.name,
        entries: async function* () {
          for (const n of Object.keys(f.files)) yield [n, file(n)];
          yield ["sub", { kind: "directory", name: "sub" }];
        },
        getFileHandle: async (name, o) => {
          if (!(name in f.files) && !(o && o.create)) throw new DOMException("not found", "NotFoundError");
          return file(name);
        },
      };
    };
  });
  await page.goto(LAB);
});

async function openFolder(page, files, name = "sfx") {
  await page.evaluate(f => { window.__folder = f; }, { name, files: b64map(files) });
  await page.click("#open");
  await expect(page.locator("#folder")).toHaveText(new RegExp("^" + name + "( |$)"));
}

async function loadFiles(page, files) {
  const [chooser] = await Promise.all([page.waitForEvent("filechooser"), page.click("#load")]);
  await chooser.setFiles(Object.entries(files).map(([name, d]) => ({
    name, mimeType: name.endsWith(".json") ? "application/json" : "audio/wav", buffer: Buffer.from(d),
  })));
  await expect(page.locator("#folder")).toContainText(Object.keys(files).length + " files loaded");
}

async function dropWavs(page, files) {
  const before = await page.locator("#samples .sample").count();
  await page.evaluate(files => {
    const dt = new DataTransfer();
    for (const [n, s] of Object.entries(files)) dt.items.add(new File([Uint8Array.from(atob(s), c => c.charCodeAt(0))], n));
    document.getElementById("drop").dispatchEvent(new DragEvent("drop", { dataTransfer: dt, bubbles: true, cancelable: true }));
  }, b64map(files));
  await expect(page.locator("#samples .sample")).toHaveCount(before + Object.keys(files).length);
}

const ids = page => page.locator("#effects li.fx .fid").evaluateAll(els => els.map(e => e.value));
const nums = async page => (await page.locator("#effects li.fx .num").allTextContents()).map(Number);
const effect = (page, i) => page.locator("#effects li.fx").nth(i);
const bytesOf = async (page, i) => parseInt((await effect(page, i).locator(".brrsize").textContent()).replace(/,/g, ""));
async function setCardField(page, i, cls, value) {
  const input = effect(page, i).locator(cls);
  await input.fill(String(value));
  await input.dispatchEvent("change");
}
const saved = async page => JSON.parse(await savedToFolder(page));
const card = (page, i) => page.locator("#samples .sample").nth(i);
const errors = page => page.locator("#errors li");

async function setRange(page, i, label, value) {
  const input = card(page, i).locator(".row", { has: page.locator("label", { hasText: new RegExp("^" + label + "$") }) })
    .locator("input[type=range]");
  await input.evaluate((el, v) => {
    el.value = v;
    el.dispatchEvent(new Event("input"));
    el.dispatchEvent(new Event("change"));
  }, String(value));
}

async function savedToFolder(page) {
  await page.evaluate(() => { delete window.__written["sfx.json"]; });
  await page.click("#save");
  await expect.poll(() => page.evaluate(() => window.__written["sfx.json"] || null)).not.toBeNull();
  return page.evaluate(() => window.__written["sfx.json"]);
}

async function savedAsDownload(page) {
  const [d] = await Promise.all([page.waitForEvent("download"), page.click("#save")]);
  expect(d.suggestedFilename()).toBe("sfx.json");
  return fs.readFileSync(await d.path(), "utf8");
}

const BANK = {
  voices: 3,
  sounds: [
    { id: "jump", priority: 1, samples: [
      { file: "a.wav", active: true, rate: 16000, treble: 6, gain: 0, trim: true, volume: 100 },
      { file: "b.wav", active: false, rate: 11000, treble: 9, gain: 2, trim: true, volume: 110 }] },
    { id: "hurry", priority: 2, samples: [] },
  ],
};

/* --- Opening ------------------------------------------------------------------------------- */

test.describe("opening", () => {
  test("the page starts empty, with nothing to save", async ({ page }) => {
    await expect(page.locator("#folder")).toContainText("No folder open");
    await expect(page.locator("#save")).toBeDisabled();
    await expect(page.locator("#effects li")).toHaveCount(0);
    await expect(page.locator("#editor")).toBeHidden();
    await expect(page.locator("#total")).toHaveText("0 of 64,448 bytes (0.0 %)");
  });

  test("cancelling the folder dialog changes nothing", async ({ page }) => {
    await page.click("#open");
    await expect(page.locator("#folder")).toContainText("No folder open");
  });

  test("a folder with sfx.json shows its effects as the file has them", async ({ page }) => {
    await openFolder(page, { "sfx.json": json(BANK), "a.wav": wav(), "b.wav": wav({ freq: 880 }) });
    expect(await ids(page)).toEqual(["jump", "hurry"]);
    await expect(effect(page, 0).locator(".file")).toHaveText("a.wav · 2 samples");
    await expect(effect(page, 0).locator(".fprio")).toHaveValue("1");
    await expect(effect(page, 0).locator(".macro")).toHaveText("SFX_JUMP");
    await expect(effect(page, 0).locator(".brrsize")).toHaveText(/^[\d,]+ B$/);
    await expect(effect(page, 1).locator(".file")).toHaveText("disabled: no active sample");
    await expect(effect(page, 1).locator(".nums")).toBeEmpty();
    await expect(page.locator("#voices")).toHaveValue("3");
    await expect(page.locator("#save")).toBeDisabled();
    await expect(errors(page)).toHaveCount(0);
    await expect(page.locator("#unused")).toBeEmpty();
    await expect(page.locator("#pads button", { hasText: "hurry" })).toBeDisabled();
    await expect(page.locator("#fxTitle")).toHaveText("0  jump  (SFX_JUMP, priority 1)");
    await expect(page.locator("#save")).toHaveText("Save sfx.json to the folder");
  });

  test("a folder without sfx.json starts with one effect per WAV file, ready to save", async ({ page }) => {
    await openFolder(page, { "jump.wav": wav(), "UhHelpMe.wav": wav(), "1up.wav": wav(), "notes.txt": "x" });
    await expect(page.locator("#folder")).toContainText("no sfx.json yet");
    expect(await ids(page)).toEqual(["sfx_1up", "uhhelpme", "jump"]);
    await expect(errors(page)).toHaveCount(0);
    await expect(page.locator("#save")).toBeEnabled();
    const text = await savedToFolder(page);
    expect(JSON.parse(text).sounds.map(s => s.samples[0].file)).toEqual(["1up.wav", "UhHelpMe.wav", "jump.wav"]);
    await expect(page.locator("#save")).toBeDisabled();
  });

  test("Load files without sfx.json starts with one effect per WAV file and saves as a download", async ({ page }) => {
    await loadFiles(page, { "jump.wav": wav(), "shoot.wav": wav({ freq: 1200 }) });
    await expect(page.locator("#folder")).toContainText("one effect per WAV file");
    expect(await ids(page)).toEqual(["jump", "shoot"]);
    await expect(page.locator("#save")).toHaveText("Download sfx.json");
    const text = await savedAsDownload(page);
    expect(text).toBe(
      "{\n  \"voices\": 2,\n  \"sounds\": [\n"
      + "    { \"id\": \"jump\", \"priority\": 0, \"samples\": [\n"
      + "        { \"file\": \"jump.wav\", \"active\": true,\n"
      + "          \"rate\": 16000, \"treble\": 6, \"gain\": 0, \"trim\": true, \"volume\": 100 } ] },\n"
      + "    { \"id\": \"shoot\", \"priority\": 0, \"samples\": [\n"
      + "        { \"file\": \"shoot.wav\", \"active\": true,\n"
      + "          \"rate\": 16000, \"treble\": 6, \"gain\": 0, \"trim\": true, \"volume\": 100 } ] }\n"
      + "  ]\n}\n");
  });

  test("Load files with sfx.json reads it", async ({ page }) => {
    await loadFiles(page, { "sfx.json": json(BANK), "a.wav": wav(), "b.wav": wav() });
    expect(await ids(page)).toEqual(["jump", "hurry"]);
    await expect(page.locator("#voices")).toHaveValue("3");
  });

  test("WAV files loaded later join the open file as files without an effect", async ({ page }) => {
    await loadFiles(page, { "sfx.json": json(BANK), "a.wav": wav(), "b.wav": wav() });
    await loadFiles(page, { "c.wav": wav() });
    expect(await ids(page)).toEqual(["jump", "hurry"]);
    await expect(page.locator("#unused li")).toHaveCount(1);
    await expect(page.locator("#unused li span")).toHaveText("c.wav");
  });
});

/* --- Effects ------------------------------------------------------------------------------- */

test.describe("effects", () => {
  test.beforeEach(async ({ page }) => {
    await openFolder(page, { "a.wav": wav(), "b.wav": wav({ freq: 660 }), "c.wav": wav({ freq: 880 }) });
  });

  test("Add, then a new id and priority", async ({ page }) => {
    await page.click("#addFx");
    expect(await ids(page)).toEqual(["a", "b", "c", "effect4"]);
    await expect(effect(page, 3)).toHaveClass(/sel/);
    await expect(page.locator("#samples")).toContainText("No sample: the effect is disabled");
    await setCardField(page, 3, ".fid", "boom");
    await setCardField(page, 3, ".fprio", 5);
    await expect(effect(page, 3).locator(".macro")).toHaveText("SFX_BOOM");
    await expect(effect(page, 3).locator(".num")).toHaveText("3");
    await expect(effect(page, 3).locator(".fprio")).toHaveValue("5");
    await expect(page.locator("#fxTitle")).toHaveText("3  boom  (SFX_BOOM, priority 5)");
    expect((await saved(page)).sounds[3]).toEqual({ id: "boom", priority: 5, samples: [] });
  });

  test("Up and Down change the numbers", async ({ page }) => {
    await expect(effect(page, 0).locator(".up")).toBeDisabled();
    await expect(effect(page, 2).locator(".down")).toBeDisabled();
    await effect(page, 2).locator(".up").click();
    expect(await ids(page)).toEqual(["a", "c", "b"]);
    await expect(effect(page, 1)).toHaveClass(/sel/);
    await effect(page, 1).locator(".up").click();
    expect(await ids(page)).toEqual(["c", "a", "b"]);
    await effect(page, 0).locator(".down").click();
    expect(await ids(page)).toEqual(["a", "c", "b"]);
    expect(await nums(page)).toEqual([0, 1, 2]);
  });

  test("Remove asks first", async ({ page }) => {
    page.once("dialog", d => { expect(d.message()).toContain("Remove the effect \"b\""); d.dismiss(); });
    await effect(page, 1).locator(".del").click();
    expect(await ids(page)).toEqual(["a", "b", "c"]);
    page.once("dialog", d => d.accept());
    await effect(page, 1).locator(".del").click();
    expect(await ids(page)).toEqual(["a", "c"]);
    await expect(page.locator("#unused li span")).toHaveText("b.wav");
  });

  test("ids the build would refuse are reported", async ({ page }) => {
    await setCardField(page, 0, ".fid", "Boom!");
    await expect(errors(page)).toContainText(["the id \"Boom!\" is not a name"]);
    await setCardField(page, 0, ".fid", "b");
    await expect(errors(page)).toHaveText(["two effects have the id \"b\""]);
  });

  test("WAV files dropped on an effect become its samples, the first one active", async ({ page }) => {
    await page.click("#addFx");
    await dropWavs(page, { "d.wav": wav(), "e.wav": wav({ freq: 300 }) });
    await expect(card(page, 0)).toHaveClass(/active/);
    await expect(card(page, 1)).not.toHaveClass(/active/);
    await expect(effect(page, 3).locator(".file")).toHaveText("d.wav · 2 samples");
  });

  test("files without an effect are listed and added one by one or all at once", async ({ page }) => {
    for (let i = 0; i < 3; i++) { page.once("dialog", d => d.accept()); await effect(page, 0).locator(".del").click(); }
    expect(await ids(page)).toEqual([]);
    await expect(page.locator("#unused")).toContainText("3 WAV files without an effect");
    await page.locator("#unused li", { hasText: "b.wav" }).locator("button").click();
    expect(await ids(page)).toEqual(["b"]);
    await expect(page.locator("#unused li")).toHaveCount(2);
    await page.click("#addAll");
    expect(await ids(page)).toEqual(["b", "a", "c"]);
    await expect(page.locator("#unused")).toBeEmpty();
  });

  test("Voices is kept between 1 and 8", async ({ page }) => {
    for (const [typed, kept] of [["12", "8"], ["0", "1"], ["3", "3"]]) {
      await page.fill("#voices", typed);
      await page.locator("#voices").dispatchEvent("change");
      await expect(page.locator("#voices")).toHaveValue(kept);
      await expect(page.locator("#voicesView span")).toHaveCount(+kept);
    }
    expect((await saved(page)).voices).toBe(3);
  });
});

/* --- Samples ------------------------------------------------------------------------------- */

test.describe("samples", () => {
  test("a rate preset sets the pitch and the size", async ({ page }) => {
    await openFolder(page, { "a.wav": wav({ seconds: 0.5 }) });
    const bytes16 = await bytesOf(page, 0);
    await card(page, 0).locator(".presets button", { hasText: "8 kHz" }).click();
    await expect(card(page, 0).locator(".presets button.on")).toHaveText("8 kHz");
    await expect(card(page, 0).locator(".stats")).toContainText("pitch $0400");
    expect(await bytesOf(page, 0)).toBeLessThan(bytes16);
    expect((await saved(page)).sounds[0].samples[0].rate).toBe(8000);
  });

  test("the sliders set rate, treble, gain and volume", async ({ page }) => {
    await openFolder(page, { "a.wav": wav() });
    await setRange(page, 0, "Rate", 12500);
    await setRange(page, 0, "Treble", 9.5);
    await setRange(page, 0, "Gain", -3);
    await setRange(page, 0, "Volume", 77);
    await expect(card(page, 0).locator(".row output").nth(1)).toHaveText("+9.5 dB");
    await expect(card(page, 0).locator(".row output").nth(2)).toHaveText("-3.0 dB");
    const x = (await saved(page)).sounds[0].samples[0];
    expect(x).toEqual({ file: "a.wav", active: true, rate: 12500, treble: 9.5, gain: -3, trim: true, volume: 77 });
  });

  test("too much gain is reported as clipping", async ({ page }) => {
    await openFolder(page, { "a.wav": wav({ amp: 0.9 }) });
    await expect(card(page, 0).locator(".stats .warn")).toHaveCount(0);
    await setRange(page, 0, "Gain", 12);
    await expect(card(page, 0).locator(".stats .warn")).toContainText("samples clipped by the gain");
  });

  test("Trim cuts the silence at both ends", async ({ page }) => {
    await openFolder(page, { "a.wav": wav({ silence: 0.3 }) });
    const trimmed = await bytesOf(page, 0);
    await card(page, 0).locator(".row input[type=checkbox]").uncheck();
    await expect(effect(page, 0).locator(".brrsize")).not.toHaveText(trimmed.toLocaleString("en") + " B");
    const whole = await bytesOf(page, 0);
    expect(whole).toBeGreaterThan(trimmed + 5000);        // 0.6 s of silence at 16 kHz: 5,400 bytes
    expect((await saved(page)).sounds[0].samples[0].trim).toBe(false);
  });

  test("one sample is active at a time; none disables the effect", async ({ page }) => {
    await openFolder(page, { "sfx.json": json(BANK), "a.wav": wav(), "b.wav": wav({ freq: 880 }) });
    await card(page, 1).locator(".head input[type=checkbox]").check();
    await expect(card(page, 0)).not.toHaveClass(/active/);
    await expect(card(page, 1)).toHaveClass(/active/);
    await expect(effect(page, 0).locator(".file")).toContainText("b.wav");
    await card(page, 1).locator(".head input[type=checkbox]").uncheck();
    await expect(effect(page, 0).locator(".file")).toContainText("disabled: no active sample");
    await expect(page.locator("#pads button", { hasText: "jump" })).toBeDisabled();
    const s = (await saved(page)).sounds[0].samples;
    expect(s.map(x => x.active)).toEqual([false, false]);
    expect(s[1]).toMatchObject({ rate: 11000, treble: 9, gain: 2, volume: 110 });
  });

  test("a sample's file can be changed, and a sample removed", async ({ page }) => {
    await openFolder(page, { "sfx.json": json(BANK), "a.wav": wav(), "b.wav": wav({ freq: 880 }) });
    await card(page, 0).locator("select").selectOption("b.wav");
    await expect(effect(page, 0).locator(".file")).toHaveText("b.wav · 2 samples");
    await card(page, 1).locator("button", { hasText: "Remove" }).click();
    await card(page, 0).locator("button", { hasText: "Remove" }).click();
    await expect(page.locator("#samples")).toContainText("No sample: the effect is disabled");
    expect((await saved(page)).sounds[0].samples).toEqual([]);
  });
});

/* --- What the build would stop on ---------------------------------------------------------- */

test.describe("errors", () => {
  test("a missing WAV file", async ({ page }) => {
    await openFolder(page, { "sfx.json": json({ voices: 1, sounds: [{ id: "a", samples: [{ file: "gone.wav", active: true }] }] }) });
    await expect(errors(page)).toHaveText(["effect \"a\": cannot read gone.wav (no such file in the folder)"]);
    await expect(card(page, 0).locator(".stats .warn")).toContainText("cannot read gone.wav");
    await expect(card(page, 0).locator("button", { hasText: "SNES" })).toBeDisabled();
  });

  test("a file that is no WAV", async ({ page }) => {
    await openFolder(page, { "broken.wav": Buffer.from("RIFF\0\0\0\0junk, not a wave file") });
    await expect(errors(page)).toContainText(["effect \"broken\": broken.wav: "]);
  });

  test("two active samples in one effect", async ({ page }) => {
    await openFolder(page, { "sfx.json": json({ voices: 1, sounds: [{ id: "a", samples: [
      { file: "a.wav", active: true }, { file: "a.wav", active: true }] }] }), "a.wav": wav() });
    await expect(errors(page)).toContainText(["effect \"a\" has 2 active samples; at most one is active"]);
  });

  test("a sample larger than 32 KB", async ({ page }) => {
    await openFolder(page, { "sfx.json": json({ voices: 1, sounds: [{ id: "long", samples: [
      { file: "long.wav", active: true, rate: 32000, trim: false }] }] }), "long.wav": wav({ seconds: 2.5 }) });
    await expect(errors(page)).toContainText(["a sample has at most 32768"]);
  });

  test("more than the audio RAM", async ({ page }) => {
    const sample = f => ({ file: f, active: true, rate: 32000, trim: false });
    await openFolder(page, {
      "sfx.json": json({ voices: 1, sounds: ["a", "b", "c"].map(id => ({ id, samples: [sample(id + ".wav")] })) }),
      "a.wav": wav({ seconds: 1.5 }), "b.wav": wav({ seconds: 1.5 }), "c.wav": wav({ seconds: 1.5 }),
    });
    await expect(errors(page)).toContainText(["the audio RAM has 64448 next to the driver"]);
    await expect(page.locator("#meter")).toHaveClass(/over/);
  });
});

/* --- The cards: sizes, sorting, the rate of all samples ---------------------------------- */

test.describe("cards", () => {
  const files = {
    "big.wav": wav({ seconds: 0.8 }),                              // 25,644 bytes
    "alpha.wav": wav({ seconds: 0.2 }),                            //  6,444 bytes
    "mid.wav": wav({ rate: 44100, seconds: 0.1, channels: 2 }),    // 17,684 bytes, the smallest BRR
  };
  test.beforeEach(async ({ page }) => { await openFolder(page, files); });

  test("a card shows the WAV's size, the BRR's, their ratio and the length", async ({ page }) => {
    expect(await ids(page)).toEqual(["alpha", "big", "mid"]);
    for (let i = 0; i < 3; i++) {
      const size = files[(await ids(page))[i] + ".wav"].length;
      await expect(effect(page, i).locator(".wavsize")).toHaveText(size.toLocaleString("en") + " B");
      const brr = await bytesOf(page, i);
      expect(brr % 9).toBe(0);
      await expect(effect(page, i).locator(".ratio")).toHaveText(Math.round(brr * 100 / size) + " %");
    }
    const ms = parseInt(await effect(page, 1).locator(".ms").textContent());
    expect(ms).toBeGreaterThan(770);
    expect(ms).toBeLessThanOrEqual(802);
  });

  test("sorting changes the view, not the numbers, until Use this order", async ({ page }) => {
    await expect(page.locator("#useOrder")).toBeHidden();
    await page.selectOption("#sortBy", "wav");
    expect(await ids(page)).toEqual(["alpha", "mid", "big"]);
    expect(await nums(page)).toEqual([0, 2, 1]);
    await expect(page.locator("#effects .up:enabled, #effects .down:enabled")).toHaveCount(0);
    await page.click("#sortDir");
    await expect(page.locator("#sortDir")).toHaveText("descending");
    expect(await ids(page)).toEqual(["big", "mid", "alpha"]);
    await page.selectOption("#sortBy", "brr");
    expect(await ids(page)).toEqual(["big", "alpha", "mid"]);
    await page.click("#sortDir");
    expect(await ids(page)).toEqual(["mid", "alpha", "big"]);
    await setCardField(page, 1, ".fid", "zeta");
    expect(await ids(page)).toEqual(["mid", "zeta", "big"]);
    await page.selectOption("#sortBy", "id");
    expect(await ids(page)).toEqual(["big", "mid", "zeta"]);
    await page.selectOption("#sortBy", "file");
    expect(await ids(page)).toEqual(["zeta", "big", "mid"]);
    await page.selectOption("#sortBy", "order");
    await page.click("#sortDir");
    expect(await ids(page)).toEqual(["mid", "big", "zeta"]);
    await page.click("#sortDir");
    expect(await ids(page)).toEqual(["zeta", "big", "mid"]);

    await page.selectOption("#sortBy", "brr");
    await page.click("#useOrder");
    await expect(page.locator("#sortBy")).toHaveValue("order");
    await expect(page.locator("#useOrder")).toBeHidden();
    expect(await ids(page)).toEqual(["mid", "zeta", "big"]);
    expect(await nums(page)).toEqual([0, 1, 2]);
    expect((await saved(page)).sounds.map(x => x.id)).toEqual(["mid", "zeta", "big"]);
  });

  test("one button sets the rate of all samples, after asking", async ({ page }) => {
    await effect(page, 0).click();
    await dropWavs(page, { "extra.wav": wav() });
    const rates = async () => (await saved(page)).sounds.flatMap(x => x.samples.map(y => y.rate));
    page.once("dialog", d => { expect(d.message()).toBe("Set the rate of all 4 samples to 11 kHz?"); d.dismiss(); });
    await page.locator("#allRates button", { hasText: "11 kHz" }).click();
    expect(await rates()).toEqual([16000, 16000, 16000, 16000]);
    page.once("dialog", d => d.accept());
    await page.locator("#allRates button", { hasText: "11 kHz" }).click();
    await expect(card(page, 1).locator(".presets button.on")).toHaveText("11 kHz");
    expect(await rates()).toEqual([11000, 11000, 11000, 11000]);
  });

  test("a click anywhere on a card chooses the effect, also on its priority's arrows", async ({ page }) => {
    await effect(page, 1).click();
    await expect(page.locator("#fxTitle")).toContainText("big");
    await effect(page, 2).locator(".fprio").click();
    await expect(effect(page, 2)).toHaveClass(/sel/);
    await page.keyboard.press("ArrowUp");
    await page.keyboard.press("Enter");                                 // the change rebuilds the list
    await expect(effect(page, 2).locator(".fprio")).toBeFocused();
    await expect(page.locator("#fxTitle")).toHaveText("2  mid  (SFX_MID, priority 1)");
    await expect(effect(page, 2).locator(".fprio")).toHaveValue("1");
    await effect(page, 1).locator(".fid").focus();                      // as Tab does
    await expect(effect(page, 1)).toHaveClass(/sel/);
    await expect(page.locator("#fxTitle")).toContainText("big");
  });

  test("clicking into a card's id chooses the effect and keeps the cursor there", async ({ page }) => {
    await effect(page, 2).locator(".fid").click();
    await expect(effect(page, 2)).toHaveClass(/sel/);
    await expect(page.locator("#fxTitle")).toContainText("mid");
    await expect(effect(page, 2).locator(".fid")).toBeFocused();
    await page.keyboard.press("End");
    await page.keyboard.type("_x");
    await page.keyboard.press("Enter");
    expect(await ids(page)).toEqual(["alpha", "big", "mid_x"]);
    await expect(effect(page, 2).locator(".fid")).toBeFocused();
  });
});

/* --- The folder ---------------------------------------------------------------------------- */

test.describe("the folder", () => {
  test("Load files with a folder open copies the WAV files into it, and Save writes there", async ({ page }) => {
    const c = wav({ freq: 300 });
    await openFolder(page, { "sfx.json": json(BANK), "a.wav": wav(), "b.wav": wav() });
    await loadFiles(page, { "c.wav": c });
    await expect(page.locator("#folder")).toHaveText("sfx: 1 files loaded, the WAV files copied into the folder");
    expect(await page.evaluate(() => window.__written["c.wav"])).toBe(c.length);
    await page.locator("#unused li button").click();
    await expect(page.locator("#save")).toHaveText("Save sfx.json to the folder");
    expect((await saved(page)).sounds.map(x => x.id)).toEqual(["jump", "hurry", "c"]);
    await expect(page.locator("#folder")).toContainText("sfx: sfx.json saved at");
  });

  test("a WAV file dropped on an effect is copied into the folder", async ({ page }) => {
    const d = wav({ freq: 700 });
    await openFolder(page, { "a.wav": wav() });
    await dropWavs(page, { "d.wav": d });
    expect(await page.evaluate(() => window.__written["d.wav"])).toBe(d.length);
  });

  test("a different file of the same name replaces the folder's only when the user says so", async ({ page }) => {
    const old = wav(), other = wav({ seconds: 0.5 });
    await openFolder(page, { "a.wav": old });
    page.once("dialog", d => { expect(d.message()).toBe("a.wav is in the folder already, with other content. Replace it?"); d.dismiss(); });
    await loadFiles(page, { "a.wav": other });
    await expect(effect(page, 0).locator(".wavsize")).toHaveText(old.length.toLocaleString("en") + " B");
    expect(await page.evaluate(() => window.__written["a.wav"])).toBeUndefined();
    page.once("dialog", d => d.accept());
    await loadFiles(page, { "a.wav": other });
    await expect(effect(page, 0).locator(".wavsize")).toHaveText(other.length.toLocaleString("en") + " B");
    expect(await page.evaluate(() => window.__written["a.wav"])).toBe(other.length);
    let asked = false;
    page.once("dialog", d => { asked = true; d.dismiss(); });
    await loadFiles(page, { "a.wav": other });
    expect(asked).toBe(false);
  });

  test("a browser without folder access says so, loads files and downloads", async ({ page }) => {
    await page.addInitScript(() => { window.showDirectoryPicker = undefined; });
    await page.goto(LAB);
    await expect(page.locator("#folder")).toContainText("This browser cannot write into a folder");
    const [chooser] = await Promise.all([page.waitForEvent("filechooser"), page.click("#open")]);
    await chooser.setFiles([{ name: "a.wav", mimeType: "audio/wav", buffer: wav() }]);
    expect(await ids(page)).toEqual(["a"]);
    await expect(page.locator("#save")).toHaveText("Download sfx.json");
    expect(JSON.parse(await savedAsDownload(page)).sounds[0].samples[0].file).toBe("a.wav");
  });
});

/* --- Playing ------------------------------------------------------------------------------- */

test.describe("playing", () => {
  const played = page => page.evaluate(() => window.__played);

  test("Original plays the WAV, SNES what the DSP plays", async ({ page }) => {
    await openFolder(page, { "a.wav": wav({ rate: 22050, seconds: 0.3 }) });
    await card(page, 0).locator("button", { hasText: "Original" }).click();
    await card(page, 0).locator("button", { hasText: "SNES" }).click();
    const p = await played(page);
    expect(p.map(x => x.rate)).toEqual([22050, 32000]);
    expect(p[0].length).toBe(Math.round(0.3 * 22050));
    const expected = await page.evaluate(() => {
      const x = bank.sounds[0].samples[0], r = conversion(x);
      return voicePlay(r.decoded, r.pitch, x.volume).length;
    });
    expect(p[1].length).toBe(expected);
  });

  test("the variants play one after the other", async ({ page }) => {
    await openFolder(page, { "sfx.json": json(BANK), "a.wav": wav(), "b.wav": wav({ freq: 880 }) });
    await page.click("#playVariants");
    const p = await played(page);
    expect(p).toHaveLength(2);
    expect(p[1].when).toBeGreaterThan(p[0].when + 0.4);
  });

  test("the pads choose voices by priority, as the library does", async ({ page }) => {
    const s = (id, priority) => ({ id, priority, samples: [{ file: id + ".wav", active: true }] });
    await openFolder(page, {
      "sfx.json": json({ voices: 1, sounds: [s("jump", 0), s("hurry", 2)] }),
      "jump.wav": wav({ seconds: 1 }), "hurry.wav": wav({ seconds: 1, freq: 300 }),
    });
    const view = page.locator("#voicesView");
    await page.click("#pads button:has-text('jump (0)')");
    await expect(view.locator("span").first()).toHaveText("voice 0: jump");
    await page.click("#pads button:has-text('hurry (2)')");
    await expect(view.locator("span").first()).toHaveText("voice 0: hurry");
    await page.click("#pads button:has-text('jump (0)')");
    await expect(view.locator("span.warn")).toHaveText("dropped jump");
    await expect(view.locator("span").first()).toHaveText("voice 0: hurry");
  });
});

/* --- The Lab against the build ------------------------------------------------------------- */

test("the Lab's bytes, pitch and frames are what superdevkit sfx builds", async ({ page }) => {
  test.skip(!fs.existsSync(CLI), "no " + CLI + ": ./gradlew installDist");
  const files = {
    "a.wav": wav({ rate: 44100, seconds: 0.4, freq: 523, channels: 2 }),
    "b.wav": wav({ rate: 11025, seconds: 0.3, freq: 200, silence: 0.1 }),
  };
  await openFolder(page, files);
  await setRange(page, 0, "Treble", 9);
  await setRange(page, 0, "Gain", -2.5);
  await card(page, 0).locator(".presets button", { hasText: "11 kHz" }).click();
  await effect(page, 1).click();
  await card(page, 0).locator(".row input[type=checkbox]").uncheck();
  await setRange(page, 0, "Rate", 21500);
  const text = await savedToFolder(page);

  const lab = [];
  for (const i of [0, 1]) {
    await effect(page, i).click();
    const [d] = await Promise.all([page.waitForEvent("download"), card(page, 0).locator("button", { hasText: ".brr" }).click()]);
    const stats = await card(page, 0).locator(".stats").textContent();
    lab.push({
      brr: fs.readFileSync(await d.path()),
      pitch: parseInt(stats.match(/pitch \$([0-9A-F]{4})/)[1], 16),
      frames: parseInt(stats.match(/(\d+) frames/)[1]),
    });
  }

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "brr-lab-"));
  try {
    for (const [n, d] of Object.entries(files)) fs.writeFileSync(path.join(dir, n), d);
    fs.writeFileSync(path.join(dir, "sfx.json"), text);
    execFileSync(CLI, ["sfx", path.join(dir, "sfx.json"), "sfx", path.join(dir, "gen")]);
    const c = fs.readFileSync(path.join(dir, "gen/sfx.c"), "utf8");
    const array = name => c.match(new RegExp(name + "\\[\\d+\\] = \\{([^}]*)\\}"))[1].split(",").map(s => s.trim()).filter(Boolean).map(Number);
    const pitch = array("sfx_pitch"), frames = array("sfx_frames");
    for (const i of [0, 1]) {
      expect(Buffer.from(array("sfx_brr_" + i)).equals(lab[i].brr), "the BRR of effect " + i).toBe(true);
      expect(lab[i].pitch).toBe(pitch[i]);
      expect(lab[i].frames).toBe(frames[i]);
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
