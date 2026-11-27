---
title: Runtime and memory
sources:
  - docs/ADR.md
  - docs/adr/*.md
  - docs/memory-map.md
  - compiler/src/main/resources/dampf/snes/runtime.s
  - compiler/src/main/java/dampf/snes/RomBuilder.java
  - compiler/src/main/java/dampf/backend/FrameLayout.java
  - compiler/src/main/java/dampf/backend/VariableBanks.java
  - README.md
  - compiler/src/main/java/dampf/mir/PointerOffsets.java
  - compiler/src/main/java/dampf/mir/PointerParams.java
  - docs/optimizations.md
  - lib/snes/README.md
  - docs/sound.md
audience: Users who need to know where their code and data end up, and what runs before their entry function
---

## Purpose

What a ROM built by dampf contains besides the compiled C, and where code, constant data,
variables, save RAM and local frames are placed: what a reader needs to mix dampf's code with
their own assembly or to read memory in Mesen.

## Sections

1. **Start-up and the entry function** – what the runtime does before calling the entry
   function, the processor state the entry function finds, and the write to `$21FB` when it
   returns; what it leaves to the kit's library on the SNES (switching interrupts on, the sound
   driver's upload).
2. **RAM** – the variables in bank `$7E`, `DBR = $7E`, the regions as the memory map lists them;
   when they do not fit, the largest go to bank `$7F` (chosen by dampf, invisible to the
   program), what that costs, which variables stay in `$7E`, and the error when both banks are
   full.
3. **The direct page** – which values dampf places there (frames of functions), and why.
4. **Pointers** – how wide a pointer is today, and when dampf makes one narrower.
5. **ROM** – the layout of the ROM, banks, how code and constant data are spread over banks,
   that no object crosses a bank boundary, calls across banks; FastROM as the default, the
   header, and save RAM at `$70:0000` when the build asks for it.
6. **I/O registers** – how C code reaches them.
7. **Interrupt handlers** – the runtime's entry stub per handler (what it saves and sets, its
   cycles), each interrupt context's direct page and frames and where they lie, and how much
   room that leaves the main code's frames.

## Pay attention to

- `docs/memory-map.md` may describe plans next to what is built; describe only what the code
  does, and say in one line where something is planned.
- Addresses with a dollar sign, as on the 65816.

## Out of scope

- The SNES platform layer of the playable ROM (`lib/snes`): only that it is the code that
  touches the I/O registers, switches the IRQ and the kit's NMI on, and uploads the sound driver.
- How a game writes its IRQ and publishes its data: [The console API](/reference/console-api).
- How frames are laid out in detail: [Optimizations](/inside/optimizations).
