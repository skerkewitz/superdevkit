// Playwright's settings for the tests of tools/brr_lab.html (README.md in this folder).
const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
  testDir: ".",
  testMatch: "*.spec.js",
  timeout: 60000,
  reporter: [["list"]],
  use: { browserName: "chromium", headless: true, acceptDownloads: true },
});
