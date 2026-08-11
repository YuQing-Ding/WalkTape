# Sony WM-D6C reference model

This profile is a machine-only model. The selected `TapeMediumDsp` remains responsible for
magnetic hysteresis, MOL/SOL, coating wander, modulation noise and tape hiss. The D6C stage does
not duplicate those effects.

## Primary references

- Sony WM-D6C / TC-D6C Service Manual (1984), specifications, electrical adjustments, block
  diagram and LED driver circuit:
  `https://audiomuzeum.hu/wp-content/uploads/1980/1984%20Sony%20Walkman%20Professional%20WM-D6C/WM-D6C_TC-D6C-service-manual-1984.pdf`
- Sony WM-D6C Service Manual, later consolidated edition:
  `https://retronik.silicium.org/DOCUMENTS/Audiovideo/Sony/Sony-WM-D6C-Service-Manual.pdf`
- Sony WM-D6C Operating Instructions, tape/Dolby selector and PEAK meter behaviour:
  `https://doc.walkman.land/sony/Sony_WM-D6C_-_Operating_Instructions.pdf`
- Walkman.land D6C archive entry, exterior and mechanism cross-check:
  `https://walkman.land/sony/wm-d6c`

## Published targets represented directly

- Frequency response: 40 Hz–15 kHz, +/-3 dB, Dolby NR off, Type I/II/IV.
- Wow/flutter: 0.04% WRMS (NAB) and +/-0.14% DIN.
- Tape speed calibration: 3,000 Hz +/-9 Hz, corresponding to +/-0.3%.
- Manual Normal / CrO2 / Metal selector, with 120 us / 70 us replay equalisation.
- Five LED PEAK meter at -10, -5, 0, +3 and +6 dB. During playback the original displays
  whichever of the left and right channels is higher at that instant.
- Signal/noise, NAB peak level: 58 dB for Type II/IV with Dolby off; 71 dB with Dolby C.
  WalkTape exposes the original OFF/B/C selector and runs the complementary processor described
  in `DOLBY_BC_MODEL.md` around the selected tape stock.

## Implementation decisions

- The quartz transport uses four deterministic residual speed components plus bounded irregular
  flutter. Their quadrature total is 0.040% RMS. This is a calibrated decomposition of the
  published scalar target; Sony did not publish those individual component amplitudes.
- The reference response uses Butterworth -3 dB endpoints and only a 0.22 dB low head contour.
  Sony publishes a tolerance envelope, not a centre-line sweep, so stronger tonal shaping would
  be invented data.
- A correctly matched tape selector is neutral because `TapeMediumDsp` already performs the
  appropriate record/replay pair. A deliberately wrong physical selector introduces the exact
  120 us / 70 us asymptotic mismatch (4.68 dB) with a click-free 35 ms transition.
- Machine electronics noise is held near -78 dB RMS so the selected tape stock, not duplicated
  synthetic hiss, determines the published system noise character.
- The OFF/B/C rail is a real control. Switching it flushes queued old-mode PCM, re-enters at the
  audible playhead, and keeps the quartz transport, replay response, and machine calibration
  unchanged.
- The screen meter is derived from final post-DSP PCM in 10 ms windows. Each point carries media
  time and is sampled at the audible `AudioTrack` playback head, avoiding the two-to-four-second
  visual lead that a decoder-thread meter would have. Digital full scale is calibrated to the
  meter's +6 dB mark, placing 0 dB at -6 dBFS.

This is a factory-spec-constrained reference model, not a claim that one particular surviving
unit's full swept transfer function or unit-specific Dolby tracking error has been captured. A future measured
impulse/sweep dataset can replace the neutral centre line without changing the transport, tape
medium or live-meter architecture.
