---
layout: home

hero:
  name: dampf
  text: A C compiler for the Super Nintendo
  tagline: Fast 65816 code from a subset of C, with the same results as gcc on the PC.
  actions:
    - theme: brand
      text: What is dampf?
      link: /guide/introduction
    - theme: alt
      text: Getting started
      link: /guide/getting-started
    - theme: alt
      text: Reference
      link: /reference/command-line

features:
  - title: One target
    details: The 65816 and the SNES, nothing else. dampf makes assumptions a general compiler cannot, such as compiling the whole program at once.
  - title: Same result as the PC
    details: The same source builds with gcc on the PC and computes the same values bit for bit on the SNES. Every test program is compared with gcc.
  - title: Readable output
    details: The generated assembly carries the C line, the processor state and the cycles of each instruction, and Mesen steps through the C source.
---
