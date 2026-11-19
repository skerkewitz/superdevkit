# Mandelbrot

The Mandelbrot set, drawn on the SNES a tile at a time, in four views: the whole set, seahorse
valley, elephant valley, and the small copy of the set on its needle. When a view is done its
colours flow and it turns twice, then the next one is drawn over it. A on the joypad goes to the
next view at once, B to the one before.

```bash
superdevkit build snes examples/mandelbrot     # the SNES: examples/mandelbrot/build/snes/mandelbrot.sfc
superdevkit run examples/mandelbrot            # the desktop: builds build/linux/mandelbrot (win64 on Windows) and plays it
```

It is the dev kit's example of code that computes. Its inner loop is `z = z * z + c` in fixed
point and nothing else, so its time is the time of the arithmetic, where the other examples
compute on the side: it is one of the benchmarks in `dev/bench_examples.sh`.

## How it is made

Everything is in `src/mandelbrot.c`; there are no assets, the colours are computed too.

- **Fixed point.** Numbers are 4.12 in `int16_t` (4096 is 1.0, from -8 to 8), the products
  `int32_t`. z stays small enough: a point is out once |z| > 2, and then |z² + c| <= 6.
- **Mode 7 as a bitmap.** In mode 7 a pixel is a byte, an index into the 256 colours. The picture
  is 128 x 120 pixels in tiles 0 to 239; tile 240 is empty, and the map puts it everywhere around
  the picture. The matrix shows it at twice the size, and turns it while it is shown.
- **A tile at a time.** The picture is computed tile by tile, and a finished tile, 64 bytes, is
  copied to VRAM in the next vertical blank: one copy, where a line of the picture would cross
  sixteen tiles.
- **A fixed number of steps a tick.** A tick runs seven iterations at most, so every tick fits
  into a frame however deep the view. A view takes as many ticks on any compiler; a faster one
  shows as fewer cycles a tick, which is what the benchmark measures.
- **Inside without iterating.** Before its iterations, a point is tested for the main cardioid
  and the circle of period 2 left of it: a point there is inside, and would otherwise run all its
  iterations. The test costs four products.

## What it shows about dampf

A view takes about three minutes, and almost all of it is multiplication: dampf multiplies two
`int16_t` into an `int32_t` with its runtime's 32-bit routine, about 1,500 CPU cycles a product
(`dampf.emu.ExampleProfile`: `dampf_mul32` is about 80 % of the work of a tick). A multiplication
of 16 by 16 bits into 32, or the SNES's own multiplier, could make the picture several times
faster; this example measures it when it comes.
