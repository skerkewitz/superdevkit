#!/usr/bin/env python3
"""Builds the project's website for GitHub Pages (ADR-6FHKAC1X).

    ./gradlew installDist && (cd docs/book && npm ci)
    dev/build_site.py build/site

Writes the start page, the book in book/ and the example games in example/, each game as its web
build with a screenshot from its own check. Needs emcc, a C compiler and Node.js. Every link in
the pages is relative except the book's, whose address is BASE + "book/".
"""
import html
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KIT = ROOT / "build/install/superdevkit/bin/superdevkit"
SITE = ROOT / "dev/site"
BASE = "/superdevkit/"
SOURCE = "https://github.com/skerkewitz/superdevkit/tree/main/examples/"

# name, tick of the screenshot (a "shot" line of its check.txt), title, what it is, its keys.
# Er-Tuep is not listed: its name is a pun on a trademark (docs/archive/er-tuep-proposal.md).
GAMES = [
    ("catchme", 700, "CatchMe",
     "A mouse eats the crumbs of a maze while four cats chase it. A chili turns the chase around for a while.",
     "Enter starts. The arrows move the mouse."),
    ("steinschlag", 400, "Steinschlag",
     "A miner digs through a cave for gems, while boulders fall, roll and crush whatever they land on.",
     "Enter starts. The arrows move. Hold Z to dig or take a gem without moving."),
    ("schach", 457, "Schach",
     "Chess against the console, on three levels. You play white.",
     "Left and right choose the level, Enter starts. The arrows move the cursor, X picks up and moves, Z puts back."),
    ("mandelbrot", 11700, "Mandelbrot",
     "The Mandelbrot set, drawn a tile at a time in four views. When a view is done, its colours flow and it turns.",
     "X goes to the next view, Z to the one before."),
    ("mode7", 270, "Mode 7",
     "The SNES's rotating and zooming background in its smallest form: a checkerboard and a sprite.",
     "X changes what lies outside the map, Z mirrors the screen."),
    ("hello", 300, "hello",
     "The smallest game of the kit: about a hundred lines of C, some text and a ball you can move.",
     "The arrows move the ball."),
]


def run(cmd, cwd):
    subprocess.run([str(c) for c in cmd], cwd=cwd, check=True, stdout=subprocess.DEVNULL)


def build_book(out):
    run(["npx", "vitepress", "build", ".", "--base", BASE + "book/", "--outDir", out / "book"], ROOT / "docs/book")


def build_game(name, tick, out):
    example = ROOT / "examples" / name
    run([KIT, "build", "web", example], ROOT)
    target = out / "example" / name
    shutil.copytree(example / "build/web", target, ignore=shutil.ignore_patterns("obj", "music"))
    run([KIT, "build", "linux", example], ROOT)
    with tempfile.TemporaryDirectory() as check:
        run([example / "build/linux" / name, "--check", "check.txt", "--out", check], example)
        shutil.copy(Path(check) / f"example_shot_{tick}.png", target / "shot.png")


def card(name, title, text, keys):
    n, t, d, k = (html.escape(s) for s in (name, title, text, keys))
    return f"""    <article class="game">
      <a href="{n}/"><img src="{n}/shot.png" alt="A screenshot of {t}" loading="lazy"></a>
      <h2>{t}</h2>
      <p>{d}</p>
      <p class="keys">{k}</p>
      <p class="links"><a class="play" href="{n}/">Play</a> <a href="{SOURCE}{n}">Source</a></p>
    </article>"""


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: dev/build_site.py <folder>")
    out = Path(sys.argv[1]).resolve()
    if out.exists():
        shutil.rmtree(out)
    (out / "example").mkdir(parents=True)
    shutil.copy(SITE / "index.html", out / "index.html")
    shutil.copy(SITE / "style.css", out / "style.css")
    build_book(out)
    for name, tick, *_ in GAMES:
        build_game(name, tick, out)
    cards = "\n".join(card(name, title, text, keys) for name, _, title, text, keys in GAMES)
    page = (SITE / "example.html").read_text(encoding="utf-8").replace("@GAMES@", cards)
    (out / "example/index.html").write_text(page, encoding="utf-8")
    (out / ".nojekyll").touch()  # GitHub Pages would drop the book's folders that start with _


if __name__ == "__main__":
    main()
