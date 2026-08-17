# WalkTape magnetic-media model

WalkTape treats the player and the cassette as two physical systems. Production playback runs:

```text
decoded stereo float PCM
  → record EQ
  → magnetic coating (hysteresis, MOL/SOL, level-dependent HF loss)
  → replay EQ
  → particle/modulation noise and coating wander
  → selected machine (head, transport, response, electronics, mechanics)
  → AudioTrack
```

The media stage is allocation-free on the audio thread. Changing stock rebuilds the renderer at the audible playhead and discards already-rendered lookahead, so two formulations are never mixed in the output queue.

## Reference stocks

| Stock | Position | Replay EQ | Low-frequency headroom | 10 kHz headroom | Noise reference | Evidence |
|---|---:|---:|---:|---:|---:|---|
| Sony CHF (1978) | IEC I | 120 µs | MOL400 3% +2.1 dB | SOL10k −1.4 dB | −50.7 dBA | measured |
| Sony EF (1985) | IEC I | 120 µs | MOL400 3% +3.2 dB | SOL10k −1.6 dB | −50.2 dBA | **placed** |
| Sony Super EF (1990) | IEC I | 120 µs | MOL400 3% +3.0 dB | SOL10k −1.1 dB | −50.7 dBA | **placed + Sony's own delta** |
| Sony EF-X (1995) | IEC I | 120 µs | MOL400 3% +4.0 dB | SOL10k −0.9 dB | −51.8 dBA | **placed** |
| TDK SA (1988) | IEC II | 70 µs | MOL315 +4.5 dB | measured SOL10k −3.6 dB | package bias noise −60.5 dB | measured |
| TDK MA-X (1990 reference family) | IEC IV | 70 µs | MOL315 +6.0 dB | SOL10k +0.5 dB | bias noise −58.0 dB | measured |

The public figures are stored separately from renderer calibration constants. Noise readings from different laboratories are not directly interchangeable because their reference flux, weighting, deck, track width, and bandwidth differ; the renderer therefore uses a documented full-band perceptual target instead of pretending those numbers are identical PCM RMS values.

## The Sony EF line: placed, not measured

**No laboratory published figures for any EF generation.** The survey that supplied CHF's numbers — a Nakamichi BX-300E against a Maxell UR (1994) reference, at Dolby level — covers CHF, HF (eight years), BHF, FN, HD-F, FXI and FX, and its own index confirms it has no EF entry. Nothing else measured one either. So unlike every other stock here, the EF figures are **placements**, and the table above says so. This is a weaker class of evidence and is labelled rather than blended in.

What is documented about EF:

- Type I ferric, introduced around 1983–85, Sony's **budget** tape for markets outside the US, where HF was the mainstay. It took over the volume slot CHF had held.
- The line runs **EF → Super EF → EF-X**, with Super EF running until about 1995–96.
- Sony's own Super EF packaging (1990–92) prints: *"Bias noise reduced by 0.5dB and the Dynamic Range expanded, compared with conventional SONY EF"*, with the reported detail being **+0.3 dB at mid** and **+0.5 dB at high** frequencies.
- Early ones were made in Japan with the green leader Sony used across its range; later ones in China.

### How each figure was placed

EF is bracketed by two tapes measured **on that same deck against that same reference**: CHF 1978 below (the tape it replaced) and HF 1985 above (the tier it sat under). Every EF number is an interpolation inside that bracket, and `SonyEfStockTest.efSitsInsideTheMeasuredBracketItWasPlacedIn` fails if a later edit pushes it past either end.

The bracket is usable at all because of one identity that is *not* a judgement call: **dynamic range = MOL − A-weighted bias noise**. That reproduces the survey's own dynamic-range column exactly for CHF — 2.1 − (−50.7) = 52.8 dB against its printed 52.8 dB — on figures nobody here chose. `theDynamicRangeIdentityReproducesTheLabsOwnColumn` is the load-bearing test, because without it, positioning EF against that column would be circular.

| | CHF 1978 | **EF 1985** | **Super EF 1990** | **EF-X 1995** | HF 1985 | HF 1990 |
|---|---:|---:|---:|---:|---:|---:|
| dynamic range | 52.8 | **53.4** | **53.7** | **55.8** | 54.5 | 57.1 |

**Super EF is the least invented of the three.** Applying Sony's printed comparison to EF fixes three of its four headline figures outright — noise −0.5 dB, dynamic range +0.3 dB, SOL10k +0.5 dB — and the fourth then follows by arithmetic: a floor 0.5 dB quieter with only 0.3 dB more range means maximum output level went **down 0.2 dB**. That trade is carried rather than smoothed away, because it is what Sony's own numbers say, and it is asserted so nobody later "fixes" it into a monotone ladder.

### Renderer constants

Solved, not chosen, by the same procedure the other stocks used: `recordTrebleGainDb` is fixed at the 5.7 dB Type I record pre-emphasis CHF already uses, then knee and drive are solved together against published THD and MOL, `maximumDynamicLoss` against SOL10k, and hiss in closed form against A-weighted bias noise. All four land inside the calibration gate's tolerances by a wide margin:

| | THD (±0.10) | MOL (±0.60) | SOL10k (±0.80) | noise (±0.80) |
|---|---:|---:|---:|---:|
| EF | 1.048 vs 1.05 | +3.23 vs +3.2 | −1.60 vs −1.6 | −50.20 vs −50.2 |
| Super EF | 0.850 vs 0.85 | +2.99 vs +3.0 | −1.10 vs −1.1 | −50.70 vs −50.7 |
| EF-X | 0.620 vs 0.62 | +4.00 vs +4.0 | −0.90 vs −0.9 | −51.80 vs −51.8 |

### What would replace this

One EF cassette of a known generation, recorded and measured on a serviced deck. That would move all three rows from *placed* to *measured* and is the only thing that will.

## Behaviour represented

- 120 µs Type I and 70 µs Type II/IV record/replay paths
- asymmetric, memory-dependent magnetisation rather than a stateless clipper
- formulation-specific low-frequency MOL and high-frequency SOL behaviour
- hot-signal treble compression before broadband peak collapse
- particle hiss with stock-specific bandwidth and full-band target
- programme-dependent modulation noise
- smooth microscopic coating-density wander without synthetic clicks or theatrical drop-outs

Capstan wow/flutter, startup speed, head-gap/azimuth behaviour, crosstalk, motor/roller noise, measured response, and output electronics remain in the machine layer. The pre-existing TPS-L2 and HS-JX707 machine constants are unchanged.

## Sources and calibration boundary

- TDK SA 1988 package data: <https://tapeartrepros.sergiostuff.com/wp-content/uploads/2023/12/Packaging_TDK-SA-100_1988.pdf>
- TDK's original SA technical/J-card material: <https://tapeartrepros.sergiostuff.com/wp-content/uploads/2024/01/JCard_TDK-SA-C60_1974.pdf>
- Sony CHF comparative measurements: <https://audiochrome.blogspot.com/2020/07/cassette-tape-measurements-sony-hf-hf-s.html>
- TDK MA/MA-R comparative laboratory review: <https://www.worldradiohistory.com/UK/Hi-Fi-Choice/1983-1989/Hi-Fi%20Choice%20Iss.%20037%20Cassette%20Decks%20%26%20Tapes%201984.pdf>
- AES overview of slow-speed magnetic-recording headroom trade-offs: <https://secure.aes.org/forum/pubs/conventions/?elib=2820>
- Sony Type I survey used as the EF bracket, and confirmation that it contains no EF row: <http://audiochrome.blogspot.com/2020/07/cassette-tape-measurements-sony-hf-hf-s.html> and <http://audiochrome.blogspot.com/2020/12/index-to-cassette-tape-measurements.html>
- Measurement conditions for that survey: <http://audiochrome.blogspot.com/2019/12/cassette-tape-comparative-measurements.html>
- Sony EF market position, generations and the Super EF packaging text: <https://www.tapeheads.net/threads/sony-super-ef.15678/>, <https://www.tapeheads.net/threads/sony-ef-vs-hf.11114/>, <https://www.tapeheads.net/threads/sony-ef-vs-super-ef.22390/>
- Sony EF90 1985 shell, leader and market description: <https://rudiecast.wordpress.com/2017/04/06/sony-ef90/>
- Dated EF, Super EF and EF-X packaging examples: <https://ultraferric.com/products/sony-ef-1985-eu>, <https://www.cassettecomeback.com/products/sony-super-ef-1990-us>, <https://ultraferric.com/products/sony-ef-x-1995-us>

This is a physically structured reference model, not yet a bit-for-bit clone of one surviving cassette. A true physical-twin claim still requires calibrated record/playback sweeps, multi-level harmonic captures, and noise/modulation recordings from known samples on serviced reference decks.
