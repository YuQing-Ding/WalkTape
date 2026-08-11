# Dolby B/C complementary model

WalkTape does not implement Dolby as a treble-cut preset. On machines whose original circuitry
supports it, the source is compressed before `TapeMediumDsp` and expanded after it. The selected
tape's saturation, HF loss, modulation noise, and particle hiss therefore sit between a matched
record/play pair, as they do on cassette.

## Primary references

- Ray M. Dolby, “A Noise Reduction System for Consumer Tape Recording,” JAES 1971:
  `https://aes.org/publications/elibrary-page/?id=1905`
- Ray M. Dolby, “A 20 dB Audio Noise Reduction System for Consumer Applications,” JAES 1983
  (paper reproduced in this Dolby technical-paper collection):
  `https://www.richardhess.com/manuals/Dolby/dolby_422.pdf`
- Sony WM-D6C service manual, selector and CX20068 B/C signal path:
  `https://retronik.silicium.org/DOCUMENTS/Audiovideo/Sony/Sony-WM-D6C-Service-Manual.pdf`
- Aiwa HS-JX707/707D service manual, B/C indications, control pins, Dolby-level alignment, and
  dedicated B/C processor path:
  `https://walkman.land/document/197/Aiwa_HS-JX707-707D_-_Service_Manualpdf`

## Modelled topology

- B uses one complementary 10 dB sliding high-frequency side path. A 1.70 kHz effective
  sub-threshold turnover fits the paper's approximately 3, 6, 8.5, and 9.6 dB record boosts at
  600 Hz, 1.2 kHz, 2.4 kHz, and 5 kHz. Its detector retains the B circuit's half-wave behaviour.
- C uses two reverse-ordered 10 dB stages at the published 375 Hz turnover, with their control
  regions staggered by 20 dB. Its detectors are full-wave and use half the B smoothing times.
- C includes the published 12 dB, Q=1 spectral-skew notch centred at 20 kHz (clamped safely below
  Nyquist at lower render rates) and the complementary 50/70 µs antisaturation shelf. The latter
  measures about -1.0 dB at 2 kHz, -2.3 dB at 5 kHz, and -2.8 dB at 15 kHz before replay undoing.
- The expander side path is fed from its decoded output. This is the feedback arrangement in the
  papers and makes clean encode/decode complementary in frequency, phase, and dynamics.
- A prewarped one-pole high-pass coefficient table moves the action band without transcendental
  work on the audio thread. Coefficients are still interpolated for every sample; cached table
  deltas and block-local filter state only remove equivalent arithmetic and field traffic. The
  processor allocates nothing while rendering.

The internal 0-Dolby alignment is -18 dBFS. That is WalkTape's digital headroom decision, not a
claim that Sony or Aiwa specified a digital reference. It lets modern mastered sources retain
headroom for the side signal and sends loud programme material out of the boost band.

## Machine integration

- WM-D6C: the drawn three-position rail directly selects C, B, or OFF.
- HS-JX707: its Dolby control cycles OFF, B, and C and lights the selected indication.
- Other profiles force the NR processor to OFF, even if a saved B/C preference exists.
- OFF skips encode and decode sample-for-sample, preserving every existing machine/tape tuning
  constant.
- A live change discards PCM already rendered with the previous mode and resumes from the audible
  playhead with the full 450 ms startup reserve. The shorter tone-selector reserve was not enough
  for exact C processing behind a 192 kHz resampler on the Pixel 8.

## Verification and limits

Unit tests lock the published low-level B/C curves, maximum HF noise reduction, high-level band
withdrawal, clean complementary reconstruction, unsupported-machine bypass, and both machines'
UI selectors. Pixel 8 device tests additionally require the exact D6C/C chain, including a
192-to-48 kHz FIR conversion, to remain faster than realtime. A two-core stereo experiment was
rejected because Android scheduling and interleaved-buffer cache contention made block time less
stable than the optimized exact single-thread path. This is a standards/topology model of a
correctly aligned unit. Component spread,
aging, record/play Dolby-level misalignment, and a capture of one particular CX20068 or NJM2065
sample remain future measurement work; they are not invented here.
