// A game's web build (superdevkit build web) plays a check.txt in headless Chromium, as the
// desktop's program plays it with --check: README.md in this folder.
//
//   node web_check.js <build/web> <check.txt> <out> [--record recording.txt]
//
// The page comes from the folder through Playwright's routing (no server, no port). Once the
// runtime is up, check.txt goes into the page's file system and main runs with
// --check /check/check.txt --out /check; then every file main wrote there (hashes.txt, sound.txt,
// the screenshots, the recording) is copied into <out>. What main printed goes to stdout and
// stderr; the exit status is main's.
const fs = require("fs");
const path = require("path");
const { chromium } = require("@playwright/test");

const TYPES = {
  ".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm",
  ".data": "application/octet-stream",
};
const ORIGIN = "http://game.test/";

async function main() {
  const args = process.argv.slice(2);
  const record = args.indexOf("--record");
  const recording = record >= 0 ? args.splice(record, 2)[1] : null;
  if (args.length !== 3) {
    console.error("usage: node web_check.js <build/web> <check.txt> <out> [--record recording.txt]");
    return 2;
  }
  const [web, checkFile, out] = args.map(a => path.resolve(a));
  const check = fs.readFileSync(checkFile);
  fs.mkdirSync(out, { recursive: true });

  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on("console", m => (m.type() === "log" ? process.stdout : process.stderr).write(m.text() + "\n"));
    page.on("pageerror", e => errors.push(e.message));
    await page.route(ORIGIN + "**", route => {
      const file = path.join(web, decodeURIComponent(new URL(route.request().url()).pathname));
      if (!file.startsWith(web + path.sep) || !fs.existsSync(file)) {
        return route.fulfill({ status: 404 });
      }
      return route.fulfill({ body: fs.readFileSync(file), contentType: TYPES[path.extname(file)] || "application/octet-stream" });
    });
    await page.goto(ORIGIN + "index.html");
    await page.waitForSelector("#start.ready", { timeout: 60000 }).catch(() => {
      throw new Error("the page did not start: " + (errors.join("; ") || "no runtime after 60 s"));
    });
    const result = await page.evaluate(({ check, recording }) => {
      const FS = Module.FS;
      FS.mkdir("/check");
      FS.writeFile("/check/check.txt", new Uint8Array(check));
      const argv = ["--check", "/check/check.txt", "--out", "/check"];
      if (recording) {
        argv.push("--record", "/check/" + recording);
      }
      const status = Module.callMain(argv);
      const files = {};
      for (const name of FS.readdir("/check")) {
        if (name !== "." && name !== ".." && name !== "check.txt") {
          files[name] = Array.from(FS.readFile("/check/" + name));
        }
      }
      return { status, files };
    }, { check: Array.from(check), recording: recording && path.basename(recording) });
    for (const [name, bytes] of Object.entries(result.files)) {
      const to = recording && name === path.basename(recording) ? path.resolve(recording) : path.join(out, name);
      fs.writeFileSync(to, Buffer.from(bytes));
    }
    if (errors.length > 0) {
      console.error(errors.join("\n"));
      return 2;
    }
    return typeof result.status === "number" ? result.status : 2;
  } finally {
    await browser.close();
  }
}

main().then(status => process.exit(status), e => {
  console.error(e.stack || String(e));
  process.exit(2);
});
