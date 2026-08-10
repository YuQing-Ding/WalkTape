# TPS-L2 audio calibration plan

## Status

Playback now uses a dedicated `MediaExtractor` / `MediaCodec` pipeline. AAC/M4A, FLAC, MP3, WAV/PCM, Ogg/Vorbis and Opus are decoded to interleaved stereo float PCM, processed by `TpsL2Dsp`, and streamed through `AudioTrack`. Unsupported codec and DRM failures now report the actual track encoding instead of a generic `MediaPlayer` error.

The implemented TPS-L2 reference renderer includes:

- a four-section least-squares fit of the digitized LOW trace from 20 Hz to 16 kHz, including its 90 Hz head bump and steep post-10 kHz loss
- the measured HIGH-minus-LOW contour as a broad shelf that reaches approximately +6 dB around 5–7 kHz
- true pitch/time wow and flutter through a fractional-delay line, with 0.219% nominal steady-state RMS speed error
- the reported 1.2% slow start and 10–15 second servo settling transient, plus a smooth 2.8 second auto-stop-cam wow pulse
- deterministic, spectrum-shaped hiss with the measured broad 8–9 kHz mound and narrow low-frequency mechanism lines
- conservative memory-dependent magnetic saturation
- independent stereo signal/filter/delay state and click-free 20 ms LOW/HIGH transitions

Signal-level unit tests verify eight LOW response anchors, five HIGH-minus-LOW anchors, the hiss spectral mound, startup pitch recovery, calibration constants, hiss RMS, channel independence and deterministic output.

The old prototype chain remains bypassed because:

- `CustomWowFlutter` multiplies sample amplitude, which produces tremolo rather than transport speed variation.
- Playback is forced to 22.05 kHz mono, discarding stereo imaging and all content above 11.025 kHz.
- Hiss is unshaped random noise and does not match a measured power spectrum.
- Clipping, bass, noise, squeal, and gain values are arbitrary rather than derived from a level-calibrated capture.

## Public reference targets

The Walkman Archive's serviced-unit measurements provide a useful starting point:

- Nominal frequency response: 40 Hz–12 kHz
- Measured wow and flutter: 0.219% RMS
- Reported background noise: approximately −67 dB, with audible energy concentrated around 8–9 kHz
- HIGH tone/tape position: approximately +6 dB around 5–7 kHz relative to LOW
- Mechanical transport, standard head, no noise reduction or sound processor

Source: [Walkman Archive — Sony TPS-L2](https://www.walkman-archive.com/gadgets/walkman_sony_01_tps-l2_eng_v3.htm)

Direct measurement images used for the fit:

- [Tone LOW response](https://www.walkman-archive.com/gadgets/sony/tps-l2/sony_tps-l2_tone_low.png)
- [Tone HIGH response](https://www.walkman-archive.com/gadgets/sony/tps-l2/sony_tps-l2_tone_high.png)
- [Background-noise spectrum](https://www.walkman-archive.com/gadgets/sony/tps-l2/sony_tps-l2_noise_tone_low.png)

Independent restored-unit reports place TPS-L2/WM-3 steady-state wow and flutter in the same range and describe the slow servo start and periodic auto-stop-cam load: [WM-3/TPS-L2 motor measurements](https://www.stereo2go.com/forums/threads/wm-3-tps-l2-wm-3-motor-disassembly-and-maintenance.7424/).

These traces describe one restored machine, a Sony UX-Pro reference tape recorded on a modified D6C, and one analyser setup. They contain no raw samples, phase response, reference-deck deconvolution or documented headphone load. The fitted result is therefore a strong public reference profile, not a factory tolerance or a physical twin.

## Measurement campaign

Use at least three serviced TPS-L2 units so the renderer can distinguish the model's stable character from the quirks of one ageing mechanism.

For each unit, capture the headphone output through a known high-impedance measurement interface and document battery voltage, output level, tape, head cleaning/alignment, load, temperature, and transport direction. Capture both LOW and HIGH positions.

Required test material:

1. Calibrated playback-level and azimuth tapes.
2. 3 kHz or 3.15 kHz wow-and-flutter tape.
3. Log sweeps and stepped tones from 20 Hz to 20 kHz.
4. Recorded digital silence for the full noise spectrum.
5. Multi-level tones and crest-factor signals for compression, clipping, and intermodulation.
6. Left-only and right-only material for channel imbalance and crosstalk.
7. Music excerpts with strong piano, sustained strings, transients, bass, and dense high frequencies.

Also record the same tapes through a reference deck. That separates the TPS-L2 playback contribution from the tape's recording-deck and formulation contribution.

## Renderer topology

The implemented realtime path remains stereo and floating point at the source sample rate:

```text
digital source
  → virtual record level and tape formulation
  → record EQ / magnetic saturation
  → tape bandwidth, head gap, azimuth and channel coupling
  → fractional-delay transport speed modulation
  → measured LOW/HIGH playback response
  → measured colored and level-modulated hiss
  → dropout, scrape flutter and ageing layer
  → TPS-L2 playback/headphone-stage nonlinearity
  → true-peak protection and output
```

Important implementation details:

- Wow and flutter must modulate time through a fractional-delay line or high-quality variable resampler. It must not modulate gain.
- Separate slow wow, capstan-rate flutter, motor/belt sidebands, and stochastic scrape flutter. Fit their spectrum to captures rather than using one sine wave.
- Build the base frequency response from measured curves. Model HIGH/LOW as measured deltas, not generic “treble boost.”
- Match the noise power spectral density and stereo correlation. Hiss should interact slightly with signal level and tape motion.
- Make tape condition a deterministic seeded layer, so a cassette has a stable personality across playbacks.
- Keep machine, tape formulation, recording level, and ageing as separate profiles. A TPS-L2 is only one part of the sound.

## Acceptance gates

The UI identifies the current engine as `TPS-L2 REFERENCE`. It can move from `REFERENCE` to `PHYSICAL TWIN` only when all of these are repeatable:

- Frequency-response error is within the agreed tolerance against held-out captures for both tone positions.
- Weighted wow-and-flutter magnitude and the modulation spectrum match held-out units, not only the headline RMS number.
- Noise floor, noise spectrum, channel imbalance, crosstalk, THD+N, and clipping curves are measured and matched.
- Realtime output is click-free under play, seek, pause, rewind/fast-forward animation, sample-rate changes, and route changes.
- Level-matched blind listening tests cannot reliably separate the renderer from the corresponding physical-unit capture on the validation set.
- Performance stays inside the chosen latency and battery budget on representative low-, mid-, and high-tier Android devices.

## Next calibration milestone

1. Capture raw LOW/HIGH sweeps from multiple serviced TPS-L2 units and keep the reference-deck captures as golden files.
2. Replace the image-derived magnitude fit with a multi-unit magnitude/phase fit after deconvolving the tape and recording deck.
3. Fit the complete wow/flutter sideband spectrum and stochastic scrape component to 3.15 kHz captures; keep the current cam/servo behavior as the public-data prior.
4. Measure crosstalk, channel mismatch and multi-level distortion curves.
5. Add route-change and long-play tests on a physical mid-tier Android device in addition to the Pixel benchmark.
