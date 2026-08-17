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
| BBE | **modelled from the licensed family**, `AiwaHsJx707Bbe`, gated by `AiwaHsJx707BbeTest` |
| Renderer | **replay EQ, output coupling and BBE are derived**; the rest stays spec-based |

**Coverage, stated plainly:** 212 components are transcribed and **17 of them reach the realtime
audio path** — the two replay equaliser networks and the output coupling capacitor. Everything else
in the list is documentation, not sound. `AiwaHsJx707Dsp` reaches the transcription only through
`AiwaHsJx707ReplayEq`, `AiwaHsJx707OutputStage` and `AiwaHsJx707Bbe`; it has no direct reference to
`AiwaHsJx707Schematic`. Blocks still registered but silent: the TA7688F voltage gain, Dolby's
external network around IC2/IC3, DSL, PLSS, buffer Q26, muting Q5, the Q29 ripple filter and
the motor governor. `DolbyNoiseReductionDsp` is built from Ray Dolby's published topology, not from
this machine's component values, and **BBE is now the same class of evidence** — see below. Neither
adds to the component-derived count, and both are labelled accordingly.

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

## What can still be derived, and what cannot

The gap between "transcribed" and "drives sound" is not all unfinished work. Most of the remaining
components belong to blocks whose behaviour lives inside ICs this manual does not document, and no
amount of reading the drawing will recover them. This table exists so nobody spends another session
discovering that the hard way.

| Block | Parts | Derivable? | Why |
|---|---|---|---|
| Replay EQ | 16 | **done** | passive network, all values printed |
| Output coupling | 1 | **done** | passive, into a named prior load |
| PLSS | 12 | **unresolved** | discrete, so derivable in principle, but see below — the topology has not been pinned down and "it is discrete" turned out not to be enough |
| Buffer Q26 | 5 | yes | discrete emitter follower |
| Supply / Q29 ripple filter | 10 | partly | discrete, but needs a rail voltage the manual does not give |
| Volume network | 8 | yes, but unusable | the app's volume goes to AudioTrack, not into the renderer |
| Muting Q5 | 6 | partly | the series JFET and its C79/R57 are readable, but the mute *timing* is set by a control line from IC7 that would have to be traced across sheets, and timing is the only audible part |
| Main amp gain | 7 | **yes** | *Corrected.* Aiwa's block diagram prints no values, but Toshiba's own TA7688F datasheet does — see below |
| Dolby | 32 | topology no, **behaviour yes** | NJM2065AM is a dedicated Dolby processor and the 32 external parts only trim an undocumented topology — but the JRC datasheet gives the chip's own encode/decode transfer points, which is a calibration target. See below |
| BBE / DSL | 13 | topology no, **behaviour yes** | XRC5484 is a dedicated BBE processor. The manual has no block diagram for it at all, and its external parts are tuning capacitors on unnamed pins — but seven datasheets for other BBE-licensed parts are now in hand, and what they agree on is a property of BBE rather than of one vendor. See below |
| Motor governor | 6 | no | TPIC326ADB, undocumented |

So the realistic ceiling for the component-derived path is roughly **60 of 212 parts**, not 212. The
rest are either inside black boxes or outside the audio path. `DolbyNoiseReductionDsp` following Ray
Dolby's published papers is not a shortcut that should be replaced — given the NJM2065AM is
undocumented, a published-topology model is the *better* evidence, not the worse one.

### The NJM2065A datasheet, and what it does to the Dolby model

Scott found the JRC datasheet page for the NJM2065A (the AM suffix is the DMP16 package, covered by
the same sheet). It does **not** give the internal topology, so it does not make the 32 external
Dolby components derived. What it gives is better for this purpose: the chip's **own encode and
decode transfer points**, with min/typ/max windows. That turns `DolbyNoiseReductionDsp` from
"published topology, unverified" into something checkable against the actual part Aiwa fitted.

Test points, V+ = 3.0 V, 0 dB = Dolby level (note 1 on the sheet defines it):

| | f | level | min | typ | max |
|---|---|---|---|---|---|
| B encode | 5 kHz | 0 dB | -1.2 | 0.3 | 1.8 |
| B encode | 1.4 kHz | -15 dB | 0.8 | 2.3 | 3.8 |
| B encode | 1 kHz | -25 dB | 4.2 | 5.7 | 7.2 |
| B encode | 5 kHz | -30 dB | 6.7 | 8.2 | 9.7 |
| B encode | 5 kHz | -40 dB | 9.8 | 10.3 | 11.8 |
| C encode | 5 kHz | 0 dB | -4.3 | -2.3 | -0.3 |
| C encode | 1 kHz | -20 dB | 3.9 | 5.9 | 7.9 |
| C encode | 500 Hz | -30 dB | 9.8 | 11.8 | 13.8 |
| C encode | 700 Hz | -40 dB | 14.5 | 16.5 | 18.5 |
| C encode | 5 kHz | -60 dB | 19.4 | 20.4 | 22.4 |
| B decode | 5 kHz | -30 dB | | -8.2 | |
| C decode | **1 kHz** | -40 dB | | -16.5 | |

Watch the C decode row: it is **1 kHz**, not 5 kHz. It was misread as 5 kHz off a low-resolution
copy first time round. Other corrections from the clean scan: S/N is measured with Rg = 5.6k, and the
REC/PLAY control voltage is across a 10k resistor on pin 20.

**Measured: the shipped model against those points.** Seven of twelve fall outside the datasheet's
own window, and the misses are systematic rather than scattered:

| Point | datasheet typ | model | delta |
|---|---|---|---|
| B 5 kHz 0 dB | +0.3 | +0.00 | -0.30 in window |
| B 1.4 kHz -15 dB | +2.3 | +0.00 | **-2.30 out** |
| B 1 kHz -25 dB | +5.7 | +1.31 | **-4.39 out** |
| B 5 kHz -30 dB | +8.2 | +9.07 | +0.87 in window |
| B 5 kHz -40 dB | +10.3 | +9.55 | **-0.75 out** |
| C 5 kHz 0 dB | -2.3 | -2.49 | -0.19 in window |
| C 1 kHz -20 dB | +5.9 | +6.58 | +0.68 in window |
| C 500 Hz -30 dB | +11.8 | +7.66 | **-4.14 out** |
| C 700 Hz -40 dB | +16.5 | +9.24 | **-7.26 out** |
| C 5 kHz -60 dB | +20.4 | +17.46 | **-2.94 out** |
| B decode 5 kHz -30 dB | -8.2 | -9.55 | -1.35 |
| C decode 1 kHz -40 dB | -16.5 | -18.37 | **-1.87 out** |

The model tracks the real part at 5 kHz and at high levels, and **under-boosts the midrange at low
levels**, worst case 7.3 dB. That is the audible direction: too little Dolby action in the midrange
is what makes a decoded tape sound dull.

**Cause, and why re-tuning constants is not a complete fix.** `DynamicStage` slides a one-pole
high-pass corner as `corner = base * (1 + range * n^p)` with `n` the envelope over Dolby level, and
applies a *fixed* side gain. A parameter search over base, range, exponent and side gain (replicated
in Python and validated to reproduce the Java to 0.00 dB before searching) shows:

- all five B points *can* be brought inside the datasheet window — best found is base 1100 Hz,
  range 15, exponent 1.25, against the shipped 1700 / 255 / 2.0;
- but the best achievable worst-case margin is **0.00 dB**: the model sits exactly on two window
  edges;
- raising the side gain does not help, and neither do sub-unity exponents.

The reason is structural. Between -30 dB and -40 dB the real part still climbs, 8.2 to 10.3 dB,
while the model is already saturated near its 10 dB ceiling at both. A sliding corner with fixed side
gain cannot produce that spread; the real side chain varies its *gain* with level as well as its
corner. Re-tuning the constants would move B from 2.9 dB outside the window to just inside it with no
margin, and would not touch C's 7.3 dB miss, which is the larger error.

**The `DynamicStage` change was made, and it works — but the retune is not shipped. Read this before
trying again.** `DynamicStage` now takes its cutoff range, cutoff exponent, side gain and a side-gain
knee as parameters, so the side-chain gain can fall as level rises. With `NO_KNEE` it is bit-identical
to the old fixed-gain behaviour, which is what ships today. Searching the new parameter space against
the five B points found `base 600 Hz, range 22, exponent 1.5, side gain 3.5, knee 0.05, q 0.7`, which
puts **all five inside the datasheet window with 0.89 dB of margin** — the fixed-gain topology could
only manage 0.00 dB. Verified in Java, matching the Python search exactly.

That retune was then **reverted**, because it fails
`DolbyNoiseReductionDspTest.lowLevelCurvesFollowThePublishedBAndCTargets`: it gives 10.26 dB at
600 Hz where Ray Dolby's paper curve wants 3.0. Fitting five sparse points got the points right and
the curve *between* them wrong.

The important part: **the papers and the datasheet do not disagree.** Where they overlap they match —
the paper wants 6.0 dB at 1.2 kHz, the datasheet 5.7 dB at 1 kHz / -25 dB. The mistake was treating
the datasheet's five points as the whole constraint set when the papers supply four more. A correct
retune has to satisfy **all nine B constraints at once**, and the same for C.

C is harder still and was not solved. Its two stages currently share one parameter set, and with a
single corner law they cannot both boost 700 Hz at -40 dB and stay silent at 5 kHz at 0 dB. The best
found improved C from three points out (worst 5.3 dB) to two out (worst 3.1 dB). Dolby C's two stages
are staggered in band as well as in level, so the real fix is to give the low-level stage its own
turnover and knee, then search the pair jointly.

### BBE: seven datasheets instead of one

The XRC5484 is still undocumented and that has not changed. What changed is the evidence available
*around* it: seven datasheets for other BBE-licensed parts. Every one is built under licence to the
same BBE Sound patents, so what they agree on is a property of BBE rather than of any one vendor's
silicon. Same class of evidence as `DolbyNoiseReductionDsp` — published behaviour, not a traced
topology — and it is labelled that way rather than counted as component-derived.

Shipped as `AiwaHsJx707Bbe` (reference model) plus a `BbeProcessor` stage inside the renderer,
gated by `AiwaHsJx707BbeTest`. **Default is off**, which is what this renderer has always shipped.

Aiwa's S5 slide switch is the **BBE badge on the cassette shell**, which used to be decoration and
is now the control: tap it and it lights amber the same way the DOLBY badge does. State runs
`WalkTapeView` → `MainActivity` (persisted as `bbe_enabled`) → `PlaybackController` →
`AiwaHsJx707Dsp.setBbeEnabled`, and flipping it flushes the render-ahead buffer back to the audible
playhead so one press is heard immediately instead of several seconds later — the same treatment
the tone switch gets. `TapeMachineDsp.setBbeEnabled` is a default no-op, so the other machines do
not grow a switch they never had; `onlyTheAiwaCarriesABbeSwitch` pins that.

It sits where IC4 sits: after the replay chain and after the head and tape losses, before the
volume control and the main amplifier. So it enhances a signal that has *already* been band-limited
to 8 or 12.5 kHz, which is the whole reason a cassette machine carries one — and it is why the
stage goes after the bandwidth filter in `process()` rather than at the head.

**Which part the XRC5484 most resembles: ROHM's BD3860K.** Four independent reasons:

1. It is the only **BBE II** part of the seven — the generation contemporary with this 1992 machine.
2. Its process path is built round a capacitor pair, 0.047u from VCA to MIX and a small cap from MIX
   to BBOUT. **Both values appear in Aiwa's BBE kit**: 0.047u and 820p against ROHM's 0.047u and
   470p.
3. Its lo contour is fixed internally and "cannot be controlled externally" — matching a machine
   whose only BBE control is an on/off slide switch.
4. It is level-dependent, with a detector and a VCA on the treble. The static JRC parts have no use
   for the per-channel electrolytics Aiwa fits here; a detector does.

BD3860K alone is not enough, because it never states its band-split frequencies as numbers. So the
model takes what each sheet is best at:

| Quantity | Value | Source |
|---|---|---|
| Band edges | 20 / 150 / 2.4k / 20k Hz | BD3860K p21 and BH3868BFS p26, word for word the same |
| Split corners | 224 Hz, 2.24 kHz | `fc = 1/(2π·21.5k·C)`, C = 33n and 3.3n. BH3868BFS prints the formula; NJM2155, NJW1146, NJW1147 and NJW1164 all fit the same two caps against the same 21.5k, so **four parts converge** |
| Fixed upper corner | 60.3 kHz | JRC parts, `1/(2π·56.2k·47p)`. Out of band, so not modelled — the test pins it |
| Phase | mid −180°, treble −360° vs bass | BD3860K p21, BH3868BFS p26; NJM2155's phase plot sweeps ≈340° and corroborates |
| Lo contour | 5 dB @ 100 Hz | BD3860K typ, window 3–7 dB, internally fixed |
| Process corner | 4.64 kHz | **fitted** — the only fitted number, see below |
| Process boost | 6 dB @ 10 kHz | `P-BBE-PROCESS-DB`, an engineering prior. The one free choice |
| Level law | threshold −40 dBV, full by −20 dBV | BD3860K's text for the threshold, its Fig 16 for where the curves flatten |
| Detector | attack 20 ms, release 1 s | BD3860K Fig 15 internal 20k, against its own Fig 16 test values C = 1u, R2 = 1M |

**Magnitude and phase are separate networks, and that was a finding rather than a convenience.** The
obvious build — split into three bands, apply the published per-band phase offsets, sum — does not
work. The bands cancel at their own crossovers: **−36 dB at 224 Hz with every band gain at unity.**
Real BBE plainly does not null its own crossover, so the 180°/360° figures describe a delay network
in the common path, not differential all-passes across a summing crossover. Modelling them apart
also makes each half independently checkable.

**Filter order is load-bearing.** The magnitude network is unity plus a first-order low boost and a
first-order high boost in parallel. Second-order paths invert in their stopbands and subtract,
digging the midband to −1.9 dB — a hole no published BBE plot has, and the dip lands at 1164 Hz
instead of the 500–700 Hz every sheet shows. First-order paths meet the through path in quadrature
instead, so the sum approaches 0 dB between the bands but cannot dive under it. There is nothing in
between: sweeping Q from 0.44 to 0.80 moves the midband only from −1.45 to −1.97 dB, because it is
the *order* that flips the phase, not the damping.

**What is fitted, and why it is not circular.** Only the process corner. NJM2155 is the one sheet of
the seven that publishes a min/typ/max window at a *midband* frequency as well as at both band
edges, so it is the only complete constraint set: 2.5 dB @ 20 Hz, 0.6 dB @ 1 kHz, 6.0 dB @ 20 kHz
with both switches low. The low corner is pinned at the family's 224 Hz rather than fitted, leaving
exactly three unknowns for three constraints and nothing free.

The check is that the corner pair fitted on the **low** settings then *predicts* the **high** ones.
Refitting only the two band gains to 5.5 dB @ 20 Hz and 9.0 dB @ 20 kHz puts 1 kHz at **+1.17 dB**,
still inside NJM2155's own 0.0–1.2 dB window, which no part of the fit was told about. That is
`theFittedCornersPredictASettingTheyWereNotFittedOn`, and it is allowed to fail.

Shipped response, BBE on, detector fully open:

| | 20 Hz | 50 Hz | 100 Hz | 315 Hz | 630 Hz | 1 kHz | 2 kHz | 5 kHz | 10 kHz | 20 kHz |
|---|---|---|---|---|---|---|---|---|---|---|
| dB | +5.6 | +5.4 | +5.0 | +2.6 | +1.1 | +0.8 | +1.8 | +4.7 | +6.0 | +6.5 |

**Aiwa's own capacitors are reported but not used**, and the test pins that. Under the family's
21.5k, C59 0.047u would put the bass/mid split at 157 Hz — tantalisingly near the stated 150 Hz —
while C53 820p would put the mid/treble split at 9.0 kHz, nowhere near the stated 2.4 kHz. One half
is suggestive and the other says the assumption does not transfer, so carrying JRC's pin impedance
onto a different vendor's part would manufacture evidence rather than find it.

**Two things done per channel rather than summed**, because BD3860K has separate DET1 and DET2 pins:
detection and the VCA. Faithful, but worth knowing it is a possible source of image movement on
hard-panned material if that ever needs investigating.

Perturbation check, so the gate's teeth are on record:

| Change | Tests failed |
|---|---|
| process corner → the 2243 Hz band split (the tempting wrong choice) | 3 of 12, including the non-circularity test |
| split capacitor 33n → 47n | 1 |
| lo contour 5 → 10 dB | 2 |
| level threshold pushed below any signal, i.e. made static | 2 |

### PLSS: why "it is discrete" was not enough

PLSS was written up here as the one substantial derivable block left, on the strength of being
discrete rather than IC-bound. That was asserted before the circuit had actually been read, and
reading it does not support the claim. Recorded so the same optimism is not repeated.

The parts are all printed: Q24 HN1C01F(GR), Q25 HN1C03F(B) as the ALC, R41/R45/R46 6.8k, R43 3.3k,
R47/R48 330, R49/R50 22k, C73/C74 3900p, C75/C76 0.027u, D3 1SS300. What is *not* resolved is the
topology:

- Q24's emitter sits on R45 to ground with R47 330 and C75 0.027u forming a loop back to a node that
  couples through C73 3900p to its own base, which reads as a bootstrapped Sallen-Key style shaping
  network around an emitter follower. Plausible, but not confirmed.
- Where the audio actually enters that stage could not be established. Signal and bias share buses
  here, and at the resolution available the junction between the R41/R43 divider output and the
  C75/R49/collector bus cannot be told apart from a supply rail with confidence.
- The ALC is a nonlinear loop: Q25 with D3 1SS300 as the detector and R51/R52 100k. Modelling it
  needs the loop resolved, not just the part values.

Building it from what is currently readable would mean guessing the topology and calling the result
derived.

**Three ways to settle it were tried. Two failed; the second was abandoned in error — see below.**

1. *Find a better scan.* There isn't one to hand. Note that in the walkman.land PDF the schematic
   foldout is the **worst**-scanned page in the document: 4654 px across three pages is about
   1550 px per page, against 3165 px per page for the WIRING sheets. Elektrotanya has the same
   document but gates it behind an interactive download.
2. *Use line weight to tell a supply rail from a signal net.* **This was written off as a dead end
   and that was wrong — the method works, my calibration did not.** The first attempt compared the
   PLSS bus against the V REF rail and the pin 7/8 drops over in the replay-EQ region and got 2-3 px
   for everything, so the idea was dropped. Measuring *within* the same small area instead gives a
   clean split:

   | line | thickness |
   |---|---|
   | R41 divider output (signal) | 1-2 px |
   | line dropping from B (signal) | 2 px |
   | C75/R49/collector bus | **3 px** |
   | A-C horizontal | **3 px** |
   | right-hand vertical rail | **3 px** |

   Compare locally, never across regions of the sheet. With that, the junction at the top-left of
   the PLSS block resolves: the thick set {C75 node, the A-C horizontal, the right rail} is one
   network, and the thin line from B crosses it **without a dot** and runs on down the sheet at
   x=3027 from y=310 to about y=723. So Q24 reads as an emitter follower — base biased from the rail
   through R49 22k, emitter on R45 6.8k to ground, C75 to AC ground — with the PLSS signal arriving
   on that separate thin net. **PLSS is probably tractable after all; it was abandoned too early.**
3. *Read the copper.* Same wall as the replay equaliser: thresholding the board floods this dense
   region into one blob, and the two board views cannot be registered accurately enough to locate
   Q24's collector pad on the foil side.

What the board *did* settle, without any copper tracing: Q24, Q25 and Q26 have their pads labelled
**E2/C2, B2/B1, C1/E1** in the silkscreen, which independently confirms the transistor orientation
read off the schematic. That was one of the open uncertainties; it is now closed.

What would actually unblock the rest: finish the local line-weight trace started above. A better
scan or a physical machine would still help for the ALC loop, but they are no longer the only way in.

**A useful discovery while reading it.** Aiwa prints DC operating voltages at nodes throughout the
drawing — Q24's emitter reads 1.1 V and its base node 1.7 V, a sane 0.6 V VBE. That is enough to pin
a discrete stage's operating point without inventing it: with R45 6.8k from a 1.1 V emitter, the
emitter current is 162 uA and re is about 161 ohm. Any future discrete-stage work should use those
printed voltages rather than assuming a bias point. It is the same class of evidence as the
component values, and it is already on the drawing.

## The headphone output network

Solved by `AiwaHsJx707OutputStage`, gated by `AiwaHsJx707OutputStageTest`. Read off page 7 at 3.4x
around the jack. Each channel leaves IC5 and reaches the socket through:

```
  IC5 OUT --+-- R68 4.7 -- C84 0.22u -- ground        Zobel damping
            |
            +-- C86 220u --- L 3.3uH --- jack         series coupling, then a chip coil
```

Only one of the three lands in the audio band, and the model says so with numbers rather than by
omission:

| Element | Corner | Modelled? |
|---|---|---|
| C86 220u into the rated 32 ohm load | **22.6 Hz** | yes, a first-order high-pass |
| R68 4.7 with C84 0.22u | 154 kHz | no, and the test pins why |
| chip coil 3.3 uH against the load | 1.54 MHz | no, and the test pins why |

The load is `P-HP-LOAD`, an engineering prior, so the corner moves with the headphones actually
plugged in. That is true of the real machine, which is why the prior is named separately rather
than folded into a constant.

**Amplifier gain: previously recorded as underivable, now unblocked.** Aiwa's block diagram on page
24 prints no values on the TA7688F's internal NF resistors, and this file used to conclude from that
that the closed-loop gain could not be derived. That was wrong — it only meant *Aiwa* did not publish
them. Toshiba's own TA7688F datasheet (1997-07-07, 11 pages, "stereo headphone amplifier, 3V use")
gives them outright:

```
  G_V = 20 log((R1 + R2) / R2) = 32 dB      R1 = 33k, R2 = 820 ohm, both internal
  actual 30.5 dB "because of influence of the other circuit"
```

and the electrical characteristics confirm 28.5 / 30.5 / 32.5 dB min/typ/max at 1 kHz into 32 ohm.
The same sheet gives output power 27 mW typ into 32 ohm at 10% THD, THD 0.12% at 10 mW, ripple
rejection, and a full external parts table. Its page 9 f-response curves plot C8 = 100/220/470 uF
into 32 ohm, which independently corroborates the 22.6 Hz coupling corner derived above.

**Both are now derived and shipped.** `AiwaHsJx707OutputStage` carries Toshiba's internal network
and power ratings; the renderer's `outputStage()` no longer invents its constants:

| | derived | was |
|---|---|---|
| internal gain | 30.8 dB (Toshiba quotes 30.5) | not modelled |
| with Aiwa's R65 47k across the internal 33k | 26.3 dB | not modelled |
| THD still at its floor, 10 mW into 32 ohm | **0.533** of swing | clean up to 0.86 |
| 10% THD, 27 mW into 32 ohm | **0.876** of swing | invented knee 0.86 |
| hard ceiling | 1.0, the rail | 1.04, *above* the rail |

The knee is Toshiba's THD floor and the soft curve's asymptote is the rail itself, which fixes the
compression coefficient at `1/(1-knee)` with **no free constant left to choose**. The old invented
knee of 0.86 turned out to sit almost exactly on the real 10% THD point, but the model was perfectly
clean below it whereas the part starts distorting from 0.533 — that difference is audible on loud
programme. Clipping is expressed as a *fraction of available swing* rather than in volts, because
the JX707 feeds this part from two cells through Q29's ripple filter, not from Toshiba's 3 V bench
supply; the proportion transfers, the absolute voltage does not.

One thing is reported as read rather than smoothed over: R65 47k parallels the internal 33k, which
puts the stage at 26.3 dB, and Toshiba's application note says the part "is not available at
Gv < 30 dB" because of high-frequency phase delay. Either Aiwa ran it outside that recommendation or
R65's far end is not on OUT. The drawing reads as OUT at 9x magnification, so the number stands as
measured. Note the datasheet's own
warning before using it: the part is **not stable below G_V = 30 dB**, and the app circuit uses an
external R_NF for gain adjustment. Aiwa's R65 47k also has to be reconciled with Toshiba's internal
R1 33k — read Aiwa's IC5 pins again with the datasheet's pin names in hand (1 IN2, 2 NF2, 3 VB2,
4 BYPASS2, 5 MUTE, 6 BYPASS1, 7 OUT2, 8 GND, 9 VCC, 10 OUT1, 11 VRF, 12 BASE, 13 PW ON/OFF,
14 VB1, 15 NF1, 16 IN1) before modelling it, because Aiwa's own pin captions do not all match.

The volume control is a related dead end worth recording. VR1 20k(A) with R59/R60, R63/R64, R125,
C127 and C81/C82 forms a genuinely audible treble loss that varies with the pot position, but the
app's volume goes to the AudioTrack rather than into the renderer, so the wiper position is not
available to model it. That needs new plumbing before it is worth tracing further.

### Two discrepancies found while reading this region

Both are recorded rather than quietly fixed.

**R466 vs R66.** The right channel's main-amp feedback resistor reads clearly as **R466 47K** at 7x,
not R66 as the transcription has it. The value is not in doubt and matches R65. What makes this worth
flagging is that it breaks Aiwa's otherwise consistent consecutive pairing (R7/R8, R59/R60, R63/R64),
so it deserves a parts-list confirmation that has not been completed. The transcription still says
R66; the value used by the renderer is unaffected either way.

**R68 has no recorded partner.** Only the right channel's Zobel resistor designator was legible. The
left channel plainly carries the same network beside C83, but its reference has not been read, so
only R68 is in the transcription. Its partner was **not** invented.

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

The audible result, measured through the whole renderer at 48 kHz, with the output coupling in
series below:

| | 20 Hz | 31.5 Hz | 63 Hz | 1 kHz | 4 kHz | 8 kHz | 12.5 kHz |
|---|---|---|---|---|---|---|---|
| Normal | -8.1 | -4.3 | -1.4 | 0 | +0.8 | -1.9 | -9.9 |
| Cr/Metal | -7.8 | -4.0 | -1.1 | 0 | +1.4 | +1.4 | -1.2 |

The subsonic end is now two derived first-order rolloffs in series — the replay equaliser's own
C19/R17 turnover and C86 into the load — and they add. That is what the real machine does, and
`theSubsonicEndIsTheReplayTurnoverPlusTheOutputCoupling` checks both are present and neither is
counted twice.

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
