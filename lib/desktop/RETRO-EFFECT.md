# Retro TV effect

When Rachael's 256 x 224 picture is scaled up into the window of the desktop frontend, it goes
through three shaders that make it look like a SNES picture on a consumer CRT television, fed
over composite video. The game never sees the effect: Rachael's picture stays clean, and so do
the screenshots (F12, and those of `--check`, which runs without a window). Taken over from
Blubber Blasen's frontend, shaders and constants unchanged.

`Tab` turns the effect on and off while the game runs. `--no-crt` starts the game with it
off. If any of the shaders fails to compile, the frontend logs `CRT: shader failed, drawing without it`
and draws the plain picture.

## Where the code is

Everything is in [crt.c](crt.c); [main.c](main.c) calls it from `show()`. The work is split into three stages, like a real set:

1. **Signal** (`SIGNAL_SHADER_BODY`): everything that happens to the video signal before
   it reaches the tube, so it depends only on the game picture: SNES colours and the
   composite colour bleed. It is drawn into `signal`, a fixed 1024 x 224 texture. The
   width is 4 samples per game pixel, so the blur stays smooth between pixels; the height
   stays 224, since the signal never mixes lines. Values are stored gamma-encoded, so dark
   tones don't band in 8 bits per channel.
2. **Glow** (`GLOW_SHADER_BODY`): the light the glass scatters, blurred from the signal at
   256 x 224 in two passes (`glow_a` horizontal, `glow_b` vertical).
3. **Screen** (`CRT_SHADER_BODY`): everything finer than a game pixel, drawn at window
   resolution: curved glass, beam and scanlines, phosphor stripes, then the glow added on
   top and the vignette. It reads the signal at the line above, its own line and the line
   below, each colour at its own position (misconvergence) and twice for the sideways
   defocus: 18 reads plus one for the glow per screen pixel, with no colour conversion.

Stages 1 and 2 are fixed size and cost the same in any window. Only stage 3 grows with the
window. `sdevkit__crt_prepare()` runs stages 1 and 2 each frame; `sdevkit__crt_draw()` runs stage 3.
`CRT_MASK_CONSTS` holds the mask values that stages 2 and 3 both need. Desktop (GLSL 330)
and web (GLSL ES 1.0, for the web target of M8) share the sources; only `CRT_FS_HEADER` differs.

The draw code passes `scale`, the factor the picture is enlarged by, for example 4 in a
1024 x 896 window. The window can have any size, so it need not be a whole number (Blubber
Blasen's frontend enlarges by whole numbers only); the shaders work with any.

The shaders are C strings, so every change needs a rebuild. Check both builds after a change:
GLSL ES 1.0 rejects some things that desktop GLSL accepts, and the error only shows up when
the shader is compiled at runtime.

## The effects

The order follows a real set: the signal is smeared first (colour bleed), then the beam
draws its lines, then it lights the phosphor stripes, and last the glass spreads a little
light (glow). Blurring after the stripes would wash them out.

### Where the softness comes from

There is no single blur. Several effects make the picture soft, each in its own direction
and for its own reason:

| Effect | Direction | Where it is strongest | Knobs |
| --- | --- | --- | --- |
| Composite blur (3) | Horizontal, along each line | Everywhere the same | `LUMA_SHARP`, `CHROMA_SHARP` |
| Beam spot (4) | Vertical, into the neighbouring lines | Bright lines | `BEAM_DARK`, `BEAM_BRIGHT` |
| Defocus at the edges (5) | Both | Edges and corners | `DEFOCUS`, `DEFOCUS_H` |
| Glow (7) | Both, wide and faint | Around bright things on dark | `GLOW`, `GLOW_SIZE` |
| Viewing distance (9) | Both | Everywhere; off by default | `DISTANCE` |

The first four happen inside a real TV. The last one happened in the viewer's eye.

### 1. Curved glass

A CRT tube had a curved front, so the picture bulged outward: the middle of each edge
reached further out than the corners. The term is barrel distortion. (Pincushion
distortion is the opposite, with edges bent inward.)

The screen stage bends the texture coordinate before it does anything else, so the
scanlines, the colour bleed, the glow and the vignette all follow the curve, and so do the
phosphor stripes from 6x up (see effect 6). The middle of each picture edge stays exactly on the edge of the window, and towards
the corners the edges curve inward, with black outside. No game pixel is ever cut off. That
matters because the HUD uses the top row and the corners.

| Constant | Now | Effect |
| --- | --- | --- |
| `CURVE` | `0.12` | Amount of bulge. 0 is flat. Around 0.25 looks like a cheap 14" set. At 0.12 the corners sit about 3.5 game pixels in from the window corners. |

A real TV also had rounded corners, because the tube's opening cut off the corners of the
picture (overscan). Games of that time kept important things away from the edges for that
reason. This game puts the HUD right into the corners, so rounding them would hide it.
Rounded corners would only work with a black margin around the picture, which makes the
picture smaller.

### 2. SNES colours (15 bit)

The SNES stored colours with 5 bits per channel, which gives 32 levels each. The shader
rounds every colour to those levels (`floor(c * 31.0 + 0.5) / 31.0` in `fetch()` of the signal stage). It makes
little difference with art that already uses SNES colours, but it catches smooth gradients
that the real hardware could not show.

To turn it off, delete that line. To use 6 bits, change both `31.0` to `63.0`.

### 3. Composite blur and colour bleed

Composite video sends brightness (luma) and colour (chroma) mixed in one signal, and the
colour part has much less bandwidth. On a TV, colours smeared sideways along the line much
more than brightness did. That is why red text on a dark background looked fringed.

The signal stage converts each pixel to YIQ, the NTSC colour space, where Y is brightness and I
and Q are colour. It then blurs horizontally over 5 pixels with two different widths, and
converts back to RGB. The blur is horizontal only because the signal is a stream along each
line; neighbouring lines are not mixed. Colours are blended in linear light (gamma 2.2), so
bright and dark pixels mix the way light does.

| Value | Now | Effect |
| --- | --- | --- |
| `LUMA_SHARP` | `1.4` | Brightness blur. Higher is sharper; 2.2 was the earlier, crisper value. |
| `CHROMA_SHARP` | `0.45` | Colour blur. Lower bleeds wider. |
| loop `-2 .. 2` | 5 pixels | How far the blur reaches. With a much lower colour value, widen the loop too, or the bleed gets cut off. |

### 4. Beam spot and scanlines

The electron beam drew the picture one line at a time. A SNES sends a "240p" signal: it
draws the same lines every frame and never fills the space between them, so thin dark gaps
can appear between the lines. TV broadcasts were interlaced and filled the gaps on
alternate frames, which is why scanlines were hard to see on normal television. Even with a
console, a small TV seen from the sofa showed them only faintly. They look stronger on a
modern monitor, where each of the 224 lines is many pixels tall.

The beam spot was round and soft, and larger when it was bright. The shader models it as a
bell curve in each of three lines: the line the pixel sits in, plus the one above and the
one below, because their soft edges reach into the gap. Each line's bell gets wider with
its brightness, so bright lines fill the gaps and dark lines leave them visible. The bell
keeps its total light (`spot()` divides by its area), so a wider beam spreads the same light
over more space instead of getting brighter. That is why there is no brightness boost.

| Constant | Now | Effect |
| --- | --- | --- |
| `BEAM_DARK` | `0.22` | Beam width for dark pixels, in game lines. Smaller means darker gaps. |
| `BEAM_BRIGHT` | `0.36` | Beam width for bright pixels. At about 0.42 bright lines merge and the scanlines vanish on light colours. |
| `SCANLINES` | `0.85` | Overall strength. 0 gives flat lines without gaps. |

This part may still change: whether we want scanlines this strong is an open question.

### 5. Edges: defocus and misconvergence

A real tube was sharpest in the middle. Towards the edges the electron beam travelled
further and hit the glass at an angle, so its spot got larger and the picture softer. A
colour tube also had three beams, one per colour, and they never met perfectly. Towards
the edges red and blue drifted away from green, which put thin colour fringes on edges:
white text with a reddish edge on one side and a bluish one on the other. Cheap TVs had
more of both.

The screen stage draws each colour channel with its own beam. Red is read slightly further
out from the centre, blue slightly further in, and green stays put; the offset grows from 0
in the middle to `CONVERGE` game pixels at the middle of each edge. The beam's bell curve
gets wider towards the edges (`DEFOCUS`), and each read is spread sideways over
`DEFOCUS_H` game pixels. The middle of the picture, where you look most, stays as sharp as
before. Both effects grow with the square of the distance from the centre.

| Constant | Now | Effect |
| --- | --- | --- |
| `DEFOCUS` | `0.5` | How much wider the beam is in the corners: 0.5 means 50 % wider there, and 25 % at the middle of each edge. |
| `DEFOCUS_H` | `0.4` | Sideways softening in the corners, in game pixels; half of it at the middle of each edge. |
| `CONVERGE` | `0.3` | Red and blue offset at the edge midpoints, in game pixels. Above about 0.5 the fringes look broken rather than old. |

Implementation note: an earlier version called one function three times, once per colour.
The NVIDIA driver (610.88, RTX 4070) returned wrong values from the second and third call,
which turned the picture red. The browser renderer got it right. The current code reads the
three colours inside `row3()` instead. If you restructure this part, compare a
screenshot of the window with all three constants at 0 against one from before the change:
they must be identical.

### 6. Phosphor stripes (aperture grille)

A colour CRT has no pixels. Behind the glass are thin vertical stripes of red, green and
blue phosphor with dark gaps between them, and each stripe glows only in its own colour.
Up close you see a fine RGB grid; from the sofa it adds up to the picture.

The shader lays the grid out in one of two ways, depending on the scale:

- **From 6x up** (`MASK_ON_TUBE_FROM`; a 1440p or 4K screen in full screen): the stripes
  follow the curved picture. They use the bent game coordinate, so they bend with the
  picture and stay the same size relative to it: each triplet (red, green, blue) is
  `1 / TRIADS` game pixels wide. For each screen pixel the shader works out how much of
  each stripe it covers, so stripes that are 2 or 2.67 screen pixels wide come out evenly.
- **Below 6x** (the default 768 x 672 window, the web build, a 1080p screen in full
  screen): the stripes are straight, one screen pixel each, three pixels per triplet.

Why two layouts: bent stripes never line up with the straight pixel grid. The curve
stretches the lines by up to 3 % towards the top and bottom, so there the stripes drift
against the pixels. At 1-pixel stripes one pixel shows a pure red stripe and a few dozen
pixels further on a half red, half green mix; the eye sees that slow drift as curved
colour bands (moire) across large even areas like the yellow of the title logo. Straight
1-pixel stripes can't drift.

This is a deliberate look decision (2026-11-15): from 6x up the grid follows the curve
because we like it that way, and at that size the drift only makes some stripes look a
little uneven in width, which we accept. Physically it is the other way round. On a real
tube the phosphor stripes sit fixed on the glass and look straight from the front; what
bent was the picture, because the beam was steered by magnetic fields. Straight stripes at
every scale would be the more faithful choice and would also remove the uneven stripes at
6x and above (set `MASK_ON_TUBE_FROM` to a value above any real scale, say 100, to get
that). Don't "fix" the bending without knowing this.

| Constant | Now | Effect |
| --- | --- | --- |
| `TRIADS` | `1.0` | Triplets per game pixel, from 6x up. Below 6x a triplet is always 3 screen pixels. |
| `MASK_ON_TUBE_FROM` | `6.0` | Scale from which the stripes follow the curve. Lower it to 5 and 5x windows show the uneven stripes at their worst (1.67 pixels per stripe). |
| `MASK_FILL` | `0.75` | Part of each stripe that glows; the rest is the dark gap. |
| `MASK_DARK` | `0.1` | How much of the other colours leaks into a stripe. 0 is pure R, G and B; 1 turns the mask off. 0.45 is a soft tint. |

A real 14-20 inch TV had about 2 triplets per game pixel. That needs about 3 screen pixels
per stripe to show cleanly, so `TRIADS = 2.0` only works from about 18x up, which is wider
than a 4K monitor. At 9x (a 4K monitor) `TRIADS = 1.0` gives 3 screen pixels per stripe.
Raising `TRIADS` on a smaller screen gives moire, not detail.

The darker the gaps, the less light the mask lets through, so the shader brightens the
stripes to keep the average brightness the same. Bright colours then hit the monitor's
maximum and get a little paler and flatter. A real CRT got around this with very bright
phosphor spots; a normal monitor can't. Part of the clipped light comes back as glow
(effect 7). If bright areas look washed out, raise `MASK_DARK`. Below 6x this is a little
stronger: each 1-pixel stripe carries its full brightness, so the title screen comes out
about 5 % darker than with the blended stripes of the curved layout.

The glow shader needs the same mask values to work out how much light gets clipped, which
is why they sit in `CRT_MASK_CONSTS` and not in the main shader alone.

### 7. Glow (halation)

On a real tube some light scattered inside the thick front glass and came out next to where
it was made, so bright things had a faint halo and the picture looked soft rather than
clinical. The screen stage takes a blurred copy of the signal and adds it on top, after the mask,
so the halo has no stripes.

The blurred copy is made at game resolution (256 x 224) in two passes, which is cheap. The
first pass turns each pixel into glow light: a small share of its colour (`GLOW`), plus a
part (`OVERFLOW`) of the light the mask clips off bright colours. That second term puts some
of the light the monitor can't show back into the picture, as a halo around bright areas.
It fades with the mask at small scales (see below).

These constants are in `GLOW_SHADER_BODY`:

| Constant | Now | Effect |
| --- | --- | --- |
| `GLOW` | `0.03` | Halo from every pixel. |
| `OVERFLOW` | `0.1` | Share of the clipped light that becomes halo. 1.0 would return all of it and makes the picture look smeared. |
| `GLOW_SIZE` | `2.0` | Halo radius in game pixels. The loop reaches 6 pixels, so going above about 3 cuts the halo off. |

Small values matter here. Glow is added in linear light, and the gamma step at the end makes
faint light over black very visible: a linear 0.05 shows as about 25 % grey. With `GLOW`
at 0.06 and `OVERFLOW` at 0.3 the dark shading under the platforms already turned pink.

### 8. Vignette

The picture gets a little darker towards the corners, as the edges of a tube were dimmer
than its centre. `rgb *= (1.0 - 0.35 * dot(c, c))` darkens the corners by about 17 %.
Change `0.35` to make it stronger or weaker; 0 turns it off.

### 9. Viewing distance (off by default)

From a sofa two or three metres away, the eye could hardly tell single game pixels apart on
a 14" TV, and the phosphor stripes and most of the scanlines were too fine to see at all.
At a desk in front of a large monitor you see every one of them. `DISTANCE` imitates the
sofa: it widens the beam, softens every read sideways, and fades out the scanlines and the
phosphor stripes.

| Constant | Now | Effect |
| --- | --- | --- |
| `DISTANCE` | `0.0` | 0 is the desk, 1 the sofa. 1 removes stripes and scanlines completely; 0.3 to 0.5 keeps a hint of them. |

This simulates the eye, not the TV, so it is a matter of taste. Sitting two metres back from
the monitor shows the same thing for free.

## Small windows

Scanlines and the phosphor stripes need several screen pixels per game pixel. At 1x or 2x
they would only produce moire patterns, so they fade with the scale. The overflow part of
the glow fades with them, since there is no clipped light without the mask:

```
fx = clamp((scale - 1.5) / 1.5, 0.0, 1.0)
```

At 1x `fx` is 0 (off), at 2x it is a third, and from 3x up it is 1 (full strength). The
curvature, colours, colour bleed, the `GLOW` part of the glow and the vignette work at
every size.

## Checking a change

F12 and `--check` save Rachael's picture, without the effect. To see what a change to a
constant did, take a screenshot of the window at a fixed size before and after it, on the same
frame: the splash, or a title screen, is the same every time. On Linux, with xdotool and
ImageMagick:

```
W=$(xdotool search --name ertuep | head -1)
xdotool windowsize $W 1024 896 && import -window $W before.png
```

Use a scale of 3 or more (a window of at least 768 x 672), or the scanlines and phosphor
stripes are faded out (see "Small windows"). For a crop that shows single phosphor stripes, a
scale like 9 matches a 4K monitor.
