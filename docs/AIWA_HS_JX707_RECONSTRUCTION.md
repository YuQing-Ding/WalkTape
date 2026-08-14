# Aiwa HS-JX707 component-level reconstruction

Working notes for rebuilding the HS-JX707 renderer from its service manual, the way the TPS-L2 was
rebuilt. This file exists so the work can be picked up cold: it records where the sources are, how
to read them, what has been established, and what has already been tried and rejected.

## Status

| | |
|---|---|
| Sources secured | yes, Aiwa's own service manual, now as the original PDF |
| Component transcription | done — `AiwaHsJx707Schematic`, gated by `AiwaHsJx707SchematicTest` |
| Replay EQ netlist | **settled** — read off the manual PDF, validated to 1.2 dB |
| Offline reference model | `AiwaHsJx707ReplayEq`, gated by `AiwaHsJx707ReplayEqTest` |
| Renderer | **replay EQ is component-derived**; the rest stays spec-based |

`AiwaHsJx707Dsp` now takes its replay equalisation from the traced netlist rather than from a fitted
shelf, so the transcription finally changes how the machine sounds. Everything else in that renderer
— bandwidth endpoints, transport, hiss, saturation — is still modelled against Aiwa's 1992 service
limits, and is still labelled as such.

## Sources

The manual covers HS-JX707 and HS-JX707D together. Scans, all reachable with `curl` and a normal
browser user agent (WebFetch gets 403 from these hosts):

```
https://www.petervis.com/manuals/hs-jx707/hs-jx707.html                 index
https://www.petervis.com/manuals/hs-jx707/component-parts-list.html     3 pages
https://www.petervis.com/manuals/hs-jx707/main-circuit-diagram.html     1 page
https://www.petervis.com/manuals/hs-jx707/audio-board.html              2 pages
https://www.petervis.com/manuals/hs-jx707/ic-block-diagrams.html        6 ICs
```

Images live under `manual/` and `ic-block-diagrams/` on that host and serve a `-2560` suffixed
variant at full resolution, which is what the text needs to be legible:

```sh
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0"
B="https://www.petervis.com/manuals/hs-jx707"
curl -s -A "$UA" -L "$B/manual/hs-jx707-main-audio-circuit-diagram-2560.webp" -o main.webp
```

The same document is also on elektrotanya (`aiwa_hs-jx707_hs-jx707d.pdf`, 32 pages, 40.5 MB) and
walkman.land. **Prefer the PDF over the petervis images.** walkman.land's viewer page exposes the
file directly, no interactive download needed:

```sh
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0"
curl -s -A "$UA" -e https://walkman.land/ -L \
  https://doc.walkman.land/aiwa/Aiwa_HS-JX707-JX707D_-_Service_Manual.pdf -o jx707.pdf
```

32 pages, 42.5 MB, one JPEG per page, extractable with `pypdf` (`reader.pages[i].images[0].image`).
The fold-out pages are 6000-6350 px wide, and the main audio schematic is **1.8x the resolution of
the petervis scan** — enough to resolve junction dots that the petervis copy cannot. Page index, by
`pypdf` page number:

| Page | Content | Native size |
|---|---|---|
| 2-4 | electrical main parts list | 3350x4670 |
| 6 | WIRING-1, main C.B, both sides | 6329x4682 |
| **7** | **SCHEMATIC DIAGRAM-1, main audio** | **4654x2336** |
| 8, 11 | WIRING-2/3, tuner | 6001x4676 |
| 10, 12 | SCHEMATIC-2/3, tuner | 6325x4676 |
| 17 | ADJUSTMENT-3, main C.B | 3198x4644 |
| 18 | practical service figure, specs | 3308x4650 |
| 23, 24 | IC block diagrams incl. TA8155FN | 3390x4649 |

Aiwa's own overall spec, from page 18: frequency response 63 Hz - 8 kHz +/-3.5 dB (NORMAL),
63 Hz - 12.5 kHz +/-4.5 dB (CrO2, METAL). Note that it starts at 63 Hz, which matters when judging
the fit below.

## Reading the scans

The full circuit diagram is 2560x1721 and its component values are too small to read reliably at
that size. Crop and upscale with Pillow, then read the crop:

```python
from PIL import Image
im = Image.open(src).convert("L")
c = im.crop((x0, y0, x1, y1))
c = c.resize((int(c.width * scale), int(c.height * scale)), Image.LANCZOS)
c.save(out)
```

Useful regions of `hs-jx707-main-audio-circuit-diagram-2560.webp`, as `(x0, y0, x1, y1, scale)`:

| Region | Crop |
|---|---|
| IC1 pre/rec amp, pinout and voltages | `240, 130, 900, 560, 3` |
| Replay EQ network and Q1/Q2 | `440, 330, 700, 580, 6` |
| Replay EQ junctions | `455, 370, 620, 575, 10` |
| IC1 input network | `285, 315, 490, 545, 10` |
| Dolby, left | `800, 120, 1250, 420, 3.6` |
| Dolby, right | `800, 400, 1250, 640, 3.6` |
| BBE/DSL around IC4 | `1210, 130, 1700, 620, 3.4` |
| Main amp, muting, volume | `1680, 20, 2300, 420, 3.2` |

Equivalent regions of the **PDF page 7** image, which is what the netlist below was actually read
from. These are the crops that resolve individual junction dots:

| Region | Crop |
|---|---|
| IC1 pre/rec amp, whole stage | `1020, 280, 1660, 850, 3` |
| Pins 6-10 and their drop lines | `1262, 470, 1420, 620, 11` |
| Replay EQ, left channel | `1265, 520, 1460, 775, 8` |
| Replay EQ, right channel | `1440, 515, 1625, 775, 8` |
| C17 node, pin-7 rail vs V REF rail | `1272, 583, 1362, 678, 17` |
| IC1 input network, pins 1-6 | `1105, 483, 1300, 775, 9` |

Read capacitor values from the **parts list**, not the drawing. The list is typeset and covers C1
to C148 without gaps; the drawing's values are small and rotated. C35/C36 is 6800 pF and reads
convincingly as 4680 pF at any sensible magnification. Read resistor values from the **drawing**,
because the parts list carries only the few resistors Aiwa stocked as spares. Read both channels of
every stereo network separately rather than mirroring one; that is what caught R34 as 1.1k when the
left channel's R33 had first been read as 5.1k.

## What is established

Integrated circuits, from the parts list, with functions from the drawing's block captions:

| Ref | Device | Function |
|---|---|---|
| IC1 | TA8155FN | pre/rec amp |
| IC2, IC3 | NJM2065AM | Dolby amp R, L |
| IC4 | XRC5484 | BBE/DSL amp |
| IC5 | TA7688F(S) | main amp |
| IC6 | CXA1405AM | 2.0 remote comparator |
| IC7 | TB2003-003FN | mecha com |
| IC8 | TPIC326ADB | motor governor |

The TA8155FN block diagram settles its playback stage: the PB amplifiers carry **no internal
feedback resistor**, PB IN is the non-inverting input, PB NF the inverting one, PB OUT the output.
So the closed loop is `1 + Zf/Zg` with everything external. The block diagram on page 24 names the
channels A and B, but the schematic labels the same pins L and R; the schematic's naming is the one
to quote. Full playback pinout, with the drawing's DC voltages:

| Pin | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|
| Name | PB IN L | PB IN R | V REF | PB NF L | PB OUT L | PB OUT R | PB NF R |
| V | 0.9 | 0.9 | 0.9 | 0.8 | 0.6 | 0.6 | 0.8 |

V REF is the stage's AC ground: the bottom rail of both EQ networks returns to pin 6, and C103 22u
takes pin 6 to chassis ground.

Tape type is switched by two 2SK880(Y) FETs, Q1 and Q2, marked EQ SW, which shunt part of the
feedback network for the metal position. The BBE and DSL switches are IC4 pins 19 and 18, and the
drawing gives both their on and off pin voltages.

## The replay equaliser netlist

Settled. Read off PDF page 7 at 8x to 17x, junction dot by junction dot, both channels separately.
Left channel; the right channel is identical with the even designators and is mirrored left-to-right
on the drawing, so read it from its own crop rather than assuming.

```
  pin 7  PB NF L   inverting input       pin 8  PB OUT L   output      pin 6  V REF
  (right channel: pin 10 PB NF R, pin 9 PB OUT R)

  R7  330k    pin 7 -- pin 8                         (R8)
  R9  15k     pin 8 -- X                             (R10)
  C17 0.01u   pin 7 -- X                             (C18)
  R11 22k     X -- Q1 drain ;  Q1 source -- pin 8    (R12, Q2)
  R17 18k     pin 7 -- V REF                         (R18)
  R13 560 || R15 560, in series with C19 22u, pin 7 -- V REF   (R14, R16, C20)
```

So `C17 + R9` in series bridges `R7`, and Q1 conducting puts `R11` across `R9`:

```
  Zf = R7 || (Rs + 1/jwC17),  Rs = R9 normally, R9||R11 = 8.92k for metal
  Zg = R17 || ((R13||R15) + 1/jwC19) = 18k || (280 + 1/jwC19)
  closed loop = 1 + Zf/Zg
```

`Zf` is a shelf with a **pole at `(R7+Rs)·C17`** and a **zero at `Rs·C17`**:

| | realised | IEC | error |
|---|---|---|---|
| bass turnover, normal | 3450 us | 3180 us | +8% |
| bass turnover, metal | 3389 us | 3180 us | +7% |
| treble, normal | 150 us | 120 us | +25% |
| treble, metal | 89.2 us | 70 us | +27% |

Both standard time constants fall out of one three-component network, which is what makes this a
finding rather than a fit. `C19`'s own corner is `(R13||R15)·C19` = 6160 us, i.e. 25.8 Hz, well below
the band Aiwa specifies; it sets the bass limit, not the EQ.

### What had been rejected, and why that was wrong

The reading above is **exactly** the one previously recorded as falsified. It was not wrong. What was
wrong was the target it had been checked against — see the reference model below. Two other readings
were tried and remain genuinely rejected:

**C15 bridging R7.** `330k × 390 pF` is 128.7 us, temptingly near 120 us. C15 and C16 sit on solid
junction dots on the reference rail: they shunt the input. The near-miss is a coincidence, and the
higher-resolution scan confirms it.

**C17 in the gain-setting leg instead of the feedback path.** Flattens the response almost entirely,
because 15k in parallel with 280 ohms barely moves the leg impedance.

### What the board scans can and cannot do

The copper was *not* what settled this, and it is worth recording why so the next pass does not
repeat the attempt. On `hs-jx707-audio-board-2560.webp` R7, R9 and R11 sit together around grid
L-M / 11-13 as chip parts on the foil side, directly opposite Q1 and C17 on the component side, so
the network is split across both sides and needs the vias too. At 2560 px the traces are 3-4 px
wide. De-halftoning and labelling the copper as connected components, then sweeping the threshold,
gives no stable answer: below ~210 the traces break into fragments, above ~218 R7, R9, R11 and the
unrelated R33 all flood into one region. The PDF's own copy of the board (page 6) is *lower*
resolution than petervis's and renders copper as a hatch fill that floods the same way. Any netlist
read off these board images would be a guess dressed up as evidence.

**Rule for this work:** a set of components that reproduces 120 us can always be found by trying
combinations, and finding one that way would prove nothing about this machine. Get a better scan
before trusting a marginal one.

## Offline reference model

No longer a prototype: it is `AiwaHsJx707ReplayEq` in the main source, gated by
`AiwaHsJx707ReplayEqTest`. Component values are pulled from `AiwaHsJx707Schematic` rather than
repeated, so the netlist and the transcription cannot drift apart, and both channels are solved from
their own designators rather than one being mirrored onto the other.

The head and IC1's input network are deliberately **not** modelled any more. The earlier prototype
divided the head's `210 ohm + 88 mH` against `C15 ∥ ((R5 ∥ C13) + C11)`, but both head figures are
engineering priors, and letting a prior sit in the loop lets it absorb an error in the trace. The
equaliser is checked on manual values alone. The priors come back when the renderer needs absolute
level rather than shape.

Both impedances collapse to the same first-order rational form, which is why the whole equaliser is
a one-pole one-zero shelf:

```
  R || (Rs + 1/jwC)  =  R * (1 + jw*Rs*C) / (1 + jw*(R+Rs)*C)

  Zf = R7  || (Rs + 1/jwC17),  Rs = R9, or R9||R11 with Q1 conducting
  Zg = R17 || ((R13||R15) + 1/jwC19)
  closed loop = 1 + Zf/Zg

iecTarget(f, tau) = sqrt(1 + 1/(w*3180us)^2) / sqrt(1 + (w*tau)^2)   // tau = 120us or 70us

// The amplifier target is NOT iecTarget. iecTarget is the standard *flux* on the tape; the head
// turns flux into EMF and differentiates it on the way, so for a flat output the amplifier must
// realise 1/(w * iecTarget). Comparing closedLoopGain directly against iecTarget drops one factor
// of w and inverts the shape of the answer, which is what made a correct trace look falsified.
ampTarget(f, tau) = 1 / (w * iecTarget(f, tau))
```

The earlier note here claimed the opposite — that the head's differentiation "must not appear on
both sides". That was the bug. The curve *undoes* the head's differentiation, so the `jw` has to
appear in the amplifier's target. Against `iecTarget` the traced network looks inverted: it rolls off
between 46 Hz and 1 kHz and goes flat above, while `iecTarget` is flat there and falls above 1.3 kHz.
Against `ampTarget` the same network fits, and the two corner frequencies land on the standard's two
corners.

Fit of the settled netlist against `ampTarget`, normalised at 1 kHz:

| Band | normal (120 us) | metal (70 us) |
|---|---|---|
| 63 Hz - 16 kHz | within **1.2 dB** | within **1.9 dB** |
| 31.5 Hz | -2.6 dB | -2.3 dB |
| 20 Hz | -4.5 dB | -4.3 dB |

The residual is all below 63 Hz, where `C19` (25.8 Hz) and `R17` deliberately roll the bass off, and
Aiwa's own spec starts at 63 Hz. Above that the network *is* the IEC characteristic to about a
decibel. The netlist is trustworthy enough to derive a realtime reduction from, so `AiwaHsJx707Dsp`
is now unblocked.

The response is *not* monotonic all the way down: C19 and R17 turn the bass boost over at about
31.5 Hz, so it peaks there and falls again towards 20 Hz. That turnover is asserted rather than
sidestepped, so a future change cannot quietly flatten it.

`AiwaHsJx707ReplayEqTest` carries a deliberate regression guard, `theFluxCurveIsNotTheAmplifierTarget`.
It pins that the two curves differ by exactly the head's 6 dB/octave and that mistaking one for the
other costs more than 16 dB, so if anyone ever "simplifies" the target back to the flux curve the
test fails instead of the netlist getting blamed again. Perturbing R7, R9 or C17 away from the
manual's values fails four to five of the seven tests, so the gate has teeth.

## How the renderer uses it

`AiwaHsJx707Dsp` does not apply the replay curve directly. The record pre-emphasis and its exact
inverse either side of the tape stage already carry the standard characteristic, so applying the
solved curve on top of that would equalise the programme twice. What the renderer adds is the
machine's **departure** from a perfect deck:

```
  ReplayErrorEq(s) = closedLoop(s) / idealReplay(s),   normalised to unity at 1 kHz
  idealReplay(s)   = (1 + s*tau) / (1 + s*3180us)
```

`1 + Zf/Zg` has two poles and two zeros. The poles are just the two denominators — adding one to a
ratio cannot move its poles — but the zeros have to be solved, because adding one does move them.
`AiwaHsJx707ReplayEq.zeroTimeConstantsSeconds` solves that quadratic in the numerically stable form,
since the two roots differ by four orders of magnitude and the naive formula loses the short one:

| | poles | zeros |
|---|---|---|
| normal | 3450 us, 6160 us | 0.3818 s, 152.7 us |
| metal | 3389 us, 6160 us | 0.3818 s, 91.9 us |

Dividing by the ideal shelf leaves three first-order sections, paired nearest-neighbour so no single
section swings by 36 dB. The factored form reproduces the direct evaluation to 8e-15 dB.

The audible result, measured through the whole renderer at 48 kHz:

| | 20 Hz | 63 Hz | 1 kHz | 4 kHz | 8 kHz | 12.5 kHz |
|---|---|---|---|---|---|---|
| Normal | -4.5 | -0.9 | 0 | +0.8 | -1.9 | -9.9 |
| Cr/Metal | -4.2 | -0.5 | 0 | +1.4 | +1.4 | -1.2 |

A spec-derived 63 Hz Butterworth high-pass used to shape the bass end; it has been removed, because
the network's own C19/R17 turnover now supplies that rolloff and keeping both would count it twice.
The head's long-wavelength contour and the 8 kHz / 12.5 kHz endpoints stay spec-derived priors:
those are head and tape losses, not amplifier behaviour, and the schematic cannot speak to them.

Two renderer tests gate this. Below 40 Hz the head contour has almost no say, so
`theBassTurnoverIsTheTracedNetworksRatherThanASpecShelf` asserts the rendered response lands on the
solved network's own error curve to 0.35 dB — a 63 Hz Butterworth would be about 20 dB down at
20 Hz instead of 4.5. `theMidbandCarriesOnlyTheDerivedReplayError` asserts the pre-emphasis pair
still complements to within 1.2 dB from 63 Hz to 2 kHz. Changing C19 from 22 uF to 4.7 uF fails both,
plus the pre-existing bandwidth envelope test.
