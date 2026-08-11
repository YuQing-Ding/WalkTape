# Sony WM-F2015 reference model

## Evidence boundary

The WM-F2015 revised service manual says that the unit is almost identical to the
WM-AF23/BF23 and directs service work to that manual. The implemented model therefore uses the
WM-F2015 supplement together with Sony's WM-AF23/AF29/BF23 circuit and mechanism documentation:

- MF-WMAF23-04 transport with distinct capstan and midway belts
- AN6650 motor-speed controller and a 3 kHz speed adjustment
- service limits of ±0.5% at the speed checkpoint and ±1.5% beginning-to-end difference
- LA4570M playback/power-amplifier path
- manual `NORMAL` / `CrO2-METAL` tape selector
- 6.3 kHz playback-head phase check
- no Dolby noise-reduction circuit

Primary references:

- [Sony WM-F2015 revised service manual](https://doc.walkman.land/sony/Sony_WM-F2015_-_Service_Manual.pdf)
- [Sony WM-AF23/AF29/BF23 service manual](https://doc.walkman.land/sony/Sony_WM-AF23-AF29-BF23_-_Service_Manual.pdf)
- [Sony WM-F2015 operating instructions](https://doc.walkman.land/sony/Sony_WM-F2015_-_Operating_Instructions.pdf)

The manuals do **not** publish a wow-and-flutter result, a complete frequency-response trace,
noise spectrum, phase response, or distortion transfer curve. Values derived below are visibly
labelled `MODEL` in the UI and must not be represented as Sony factory specifications.

## Implemented topology

The machine stage is deliberately separate from `TapeMediumDsp`. A selected CHF, SA, or MA-X
cassette still supplies coating EQ, MOL/SOL, magnetic hysteresis, tape hiss, and coating wander.
The WM-F2015 stage adds only the hardware that belongs to the player:

```text
tape-medium output
  → dual-belt transport time modulation
  → small shared-head azimuth drift
  → head / LA4570M replay contour
  → NORMAL or CrO2-METAL bandwidth
  → channel leakage and machine electronics bed
  → low-voltage headphone-output soft knee
```

The transport model uses four bounded components at 0.62, 2.10, 5.40, and 10.20 Hz plus a small
irregular term. Their quadrature target is 0.340% RMS. This is an intentionally audible,
healthy-unit prior for the documented two-belt mechanism—not a number printed by Sony. Pitch is
modulated with a quadratic fractional-delay line, so sustained piano notes move in pitch rather
than merely changing volume.

For `NATURAL` and `LIVED-IN`, the bounded unit-tolerance speed and azimuth components are summed
into that same physical transport delay. They are not rendered as a second cascaded mechanism;
this keeps the topology honest and leaves enough realtime reserve for 24-bit/192 kHz decoding.

The `NORMAL` path rolls toward an 11.6 kHz model target; `CrO2/METAL` retains the public 15 kHz
catalogue endpoint. Switching is crossfaded over 25 ms. The machine electronics bed targets
−65 dB integrated RMS and remains quieter than the independently generated cassette hiss.

## Acceptance and next calibration

Automated gates cover deterministic block processing, frequency endpoints, selector switching,
machine-versus-tape noise separation, an audible but bounded pitch-motion target, and realtime
headroom on the connected Pixel benchmark.

This profile should be called a physical twin only after several serviced WM-F2015 units are
captured with calibrated speed, azimuth, sweeps, silence, multilevel tones, crosstalk, and a known
headphone load. The image/manual-derived priors can then be replaced with a held-out multi-unit
magnitude, phase, modulation-spectrum, and nonlinear fit.
