---
layout: home

hero:
  name: superdevkit
  text: A development kit for Super Nintendo games in C
  tagline: dampf compiles the game for the SNES; the same code runs on Linux, Windows and in the browser.
  actions:
    - theme: brand
      text: What is dampf?
      link: /guide/introduction
    - theme: alt
      text: Getting started
      link: /guide/getting-started
    - theme: alt
      text: Reference
      link: /reference/superdevkit

features:
  - title: One game, four targets
    details: superdevkit build snes, linux, win64 or web. On the PC the game runs on Rachael, the SNES rebuilt in C, with the same picture and the same checksum every tick as the ROM.
  - title: One target for dampf
    details: The 65816 and the SNES, nothing else. dampf makes assumptions a general compiler cannot, such as compiling the whole program at once.
  - title: Same result as the PC
    details: The same source builds with gcc on the PC and computes the same values bit for bit on the SNES. Every test program is compared with gcc.
  - title: Readable output
    details: The generated assembly carries the C line, the processor state and the cycles of each instruction, and Mesen steps through the C source.
---

superdevkit is an independent project. It is not affiliated with, endorsed by or connected to Nintendo, and it is not an official development kit. Nintendo, Super Nintendo, Super Nintendo Entertainment System and SNES are trademarks of Nintendo; they are used here only to say which console the kit is for. superdevkit contains no code or data of Nintendo's, and the games made with it are their authors' own.
