# Sony TPS-L2 component and electromechanical audio model

## Scope and model identity

The target is the **Sony TPS-L2**, not a TPS-L1. The implemented electrical reference is the
revised TPS-L2 main board identified by Sony's serial-number thresholds (US 106001+, Canada
207501+, AEP 108501+, UK 117501+, E 118501+). The older board is a materially different circuit;
it is not silently mixed with the revised circuit.

TPS-L2 is a playback-only machine. It has no tape record or bias oscillator path, so this model
does not invent one. `TapeMediumDsp` represents material that was recorded on another deck;
`TpsL2Dsp` begins at TPS-L2 playback/transport behavior.

## Evidence hierarchy

1. Sony service-manual schematic, board drawings, parts list, voltages and published specs.
2. Toshiba 2SC2458 manufacturer data and public measurements from a serviced TPS-L2.
3. Repair observations for the MNF-1600B/FG motor, auto-stop cam, flywheel and back tension.
4. Explicit engineering priors only where Sony published no numeric parameter.

Every part in `TpsL2Schematic` carries an evidence class. `SONY_SERVICE_MANUAL` values are direct
transcriptions; `MANUFACTURER_DATASHEET` identifies manufacturer device behavior;
`ENGINEERING_PRIOR` identifies a value that requires future measurement. Priors use a `P-` prefix
and are never presented as Sony component values.

## Revised-model circuit transcription

`TpsL2Schematic` is the executable source of truth for the revised circuit. Its coverage tests
currently require 50 resistors, 42 capacitors, five discrete transistors and five IC macro
instances, plus the motor, 35 uH choke, diodes, thermistor, head, switches, microphone, jacks and
3 V supply. Both stereo channels remain separate.

The signal path is:

```text
PP181-3602E playback head
  -> C101/C201 head coupling and R101/R102 or R201/R202 loading
  -> Q101/Q201 (2SC2458) bias and feedback network
  -> IC101/IC201 (CX182 preamp/AGC pin macro)
  -> R/C feedback, playback EQ and S301 LOW/HIGH network
  -> RV101/RV201 20 kohm volume controls
  -> IC102/IC202 (CX184 power/ripple-filter pin macro)
  -> C116/C216 220 uF and R801-R804 3.9 ohm headphone outputs
```

The shared electrical/mechanical path is:

```text
two AA cells + C901 220 uF
  -> IC601 CX183 FG shaper/sawtooth/comparator/DC-amp macro
  -> Q601/Q602 servo pair -> Q603 motor driver -> L601 35 uH
  -> MNF-1600B motor + FG
  -> belt elasticity/damping -> flywheel/capstan -> tape tension and pack radius
  -> motor current -> battery sag/ripple -> CX184 -> CX182 and output headroom
```

## Two-level solver architecture

### Offline component reference

`TpsL2CircuitReferenceModel` runs at 4x sample rate and keeps independent states for:

- head winding resistance/inductance and input coupling;
- the 2SC2458 junction, emitter bypass and collector feedback;
- CX182 input, AGC detector/control and external feedback pins;
- the LOW/HIGH playback network and all surrounding R/C values;
- CX184 finite PSRR, power-stage feedback/compensation, output capacitor and headphone load;
- C901, CX184 ripple output and channel decoupling under programme-dependent current.
- the S801 HOT LINE branch through C801/R810-R815 and the C-1004Q electret pin macro.

The measured whole-machine response remains the small-signal authority. The reference computes
the nonlinear component network and the same network with nominal linear devices, then adds only
their residual to the measured transfer target. This prevents double-counting head/EQ response.

`TpsL2TransportReferenceModel` runs an 8x coupled differential system containing motor back EMF,
torque/current, FG feedback, servo integration, motor/flywheel inertias, belt torsion, capstan
friction, tape tension, pack radius and the C901 supply. Its unknown motor constants, belt modulus
and inertias are explicitly marked as priors.

These reference solvers are intentionally not allocated or run on Android's realtime audio
thread. They are deterministic calibration and regression oracles.

### Realtime reduced model

`TpsL2Dsp`, `TpsL2ElectromechanicalModel`, `TpsL2PlaybackElectronics` and
`TpsL2TapeLayerBleed` implement the stable realtime reduction:

- measured LOW response and HIGH-minus-LOW contour;
- true fractional-delay speed modulation, rather than amplitude tremolo;
- 0.219% RMS steady wow/flutter, slow servo start and periodic auto-stop-cam load;
- tape-position-dependent reel load, motor current, C901 sag and CX184/CX182 rail coupling;
- programme-dependent supply draw, transistor asymmetry, output knee and 35 ohm loading;
- belt/motor/bearing noise, rail ripple and transport-state key/cam transients;
- magnetic saturation/hysteresis approximation, level-dependent HF loss and shaped hiss in the
  separate tape-medium stage;
- adjacent-layer print-through with both echoes a wound pack produces: the pre-echo one supply-pack
  revolution ahead of the programme and the weaker post-echo one revolution behind it, spectrally
  shaped by the demagnetising law so the printed shadow peaks where the recorded wavelength equals
  `2*pi*d`. With the IEC 60094-1 tape speed of 4.7625 cm/s and 18 um C-60 stock that is 421 Hz;
- a two-cell alkaline supply whose terminal voltage and series resistance both follow depth of
  discharge, costing rail voltage, output headroom and finally speed. Depth is an input, defaulting
  to fresh, because charge drawn belongs to a listening session rather than to one track;
- explicit STOPPED, STARTING, PLAYING, PAUSED, FAST_FORWARD and REWIND states.

Print-through pre-echo is the one effect that genuinely depends on tape the head has not reached
yet, so the machine stage reports a look-ahead of one full pack revolution through
`TapeMachineDsp.latencyFrames()`. `PlaybackController` discards exactly that many leading frames
after every reset and runs an equal flush past the end of the programme, which keeps the audible
timeline sample accurate and preserves the closing seconds of every track.

Input validation, output limiting and state sanitisation prevent NaN/Inf, DC runaway and invalid
frame counts. All allocations occur outside `process`, and UI state crosses into DSP through
volatile requests that are consumed by the decoder/audio thread.

## Tape stock calibration

`TapeStockProfile` publishes measured figures for each formulation. Those figures now drive the
renderer instead of sitting beside it as labels. For every stock, `TapeMediumDsp` is required to
reproduce, at its own output:

- total harmonic distortion at Dolby level (`thdAtReferencePercent`);
- the level at which distortion reaches three per cent (`mol315Db`);
- the maximum output the coating can be driven to at 10 kHz (`sol10kDb`);
- A-weighted bias noise (`biasNoiseDb`), weighted against the analog IEC 61672 curve applied in the
  frequency domain rather than through a bilinear-transformed filter, which is several dB wrong in
  the top octave at 48 kHz;
- replay level relative to the type's reference tape (`sensitivityDb`).

`recordTrebleGainDb`, `magneticDrive`, `magneticKnee`, `maximumDynamicLoss` and `renderedHissRmsDb`
are solved against those targets rather than voiced by hand, and
`TapeStockCalibrationTest` is the gate that keeps them agreeing.

Reaching a saturation output level at all required replacing the rational magnetisation curve with
one that has a genuine ceiling. The previous curve `v(1+n*v^2)/(1+d*v^2)` approaches a straight line
of slope `n/d`, so the rendered stock compressed but never saturated: 10 kHz output kept climbing
12 to 19 dB past where real tape stops. The curve is now `v/(1+|v|^k)^(1/k)`, sampled into a table
so the shape costs one interpolation per sample instead of two `Math.pow` calls.

## Record level

A coating has one fixed maximum output level, so where the recordist set the level control decides
how much of the music sits under it. The renderer previously had no such stage: it fed the source
file's own level straight at the coating, and a modern master peaking at 0 dBFS lands 18 dB above
reference flux, where Sony CHF measures 28 per cent distortion. The music arrived permanently
crushed against the ceiling, which is heard as gritty highs and no dynamic range at all.

`RecordLevelProfile` is that control, exposed as the third tab of the signal-chain sheet. Levels
are marked the way a deck's meter is: dB above reference flux, where programme peaks are meant to
land. Record gain is applied before the coating and taken back out after it, so the control changes
how hard the tape was driven rather than how loud playback is. Hiss rides the make-up gain, which is
the real trade — recording hot buys signal-to-noise and spends headroom.

The saturation itself runs at twice the sample rate. At 44.1 kHz the harmonics of a 7 kHz tone fold
back to frequencies that are not multiples of anything in the music, measured at only 29 dB below
the tone; oversampling moves the first reflection from 22 kHz to 44 kHz and puts that at -42 dB.
The published measurements are unaffected by both stages: the record level defaults to unity on a
directly constructed medium, and the renderer constants were re-solved against the oversampled
coating.

`relativeBiasDb` remains published but unmodelled. It describes how far this stock's optimum bias
sits from the reference tape for its type, which is a record-side alignment property; representing
it needs a deck bias-trim concept and a published 10 kHz sensitivity per stock, neither of which
exists in the model or in the sourced data.

## Tests and acceptance gates

The TPS-L2 regression suite covers:

- exact service-manual values and complete typed component coverage;
- reference-solver finite output at 8, 44.1, 48 and 96 kHz and extreme/non-finite input;
- decoder block-boundary invariance;
- power-amplifier programme current coupling into the CX184/CX182 supply;
- coupled motor/belt/flywheel/FG startup and all transport/pack extremes;
- LOW response anchors, HIGH-minus-LOW anchors and 8-9 kHz hiss spectrum;
- startup recovery, calibrated wow/flutter RMS, saturation/THD and stereo independence;
- realtime model bounds, deterministic output and tape-position load;
- print-through peak wavelength, echo ordering either side of the programme, the pre/post level
  asymmetry, DC rejection and the one-revolution look-ahead;
- alkaline service life at the service-manual PLAY current, monotonic discharge, and the rail and
  headroom a fresh pair must leave untouched.

Moving the UI label from `REFERENCE` to `PHYSICAL TWIN` still requires measured magnitude and
phase, distortion, crosstalk, noise, wow/flutter and startup data from multiple serviced machines,
plus device benchmarks. Passing software tests cannot manufacture unavailable silicon masks or
unit-specific mechanical measurements.

## Sources

- [Sony TPS-L2 service manual mirror](https://walkman.land/document/759/Sony_TPS-L2_-_Service_manualpdf)
- [DocTSF TPS-L2 technical archive](https://www.doctsf.com/sony-tps-l2/f14083)
- [Toshiba 2SC2458 manufacturer datasheet mirror](https://docs.rs-online.com/af88/0900766b808496c8.pdf)
- [Walkman Archive serviced-unit measurements](https://www.walkman-archive.com/gadgets/walkman_sony_01_tps-l2_eng_v3.htm)
- [TPS-L2/WM-3 mechanical restoration observations](https://stereo2go.com/forums/threads/sony-tps-l2-wm-3-restoration-tutorial-mechanical.7123/)
- [MNF-1600B motor/servo observations](https://stereo2go.com/forums/threads/wm-3-tps-l2-wm-3-motor-disassembly-and-maintenance.7424/)
- [TPS-L2 circuit-variant and rail-voltage repair notes](https://stereo2go.com/forums/threads/tps-l2-no-sound-after-service-mechanics-are-rotating.9291/)

## Known evidence limits

Sony's manual exposes block diagrams and pins for CX182, CX183 and CX184, but not their internal
transistor masks. Those devices are therefore auditable pin-level behavioral macros, not invented
internal transistor netlists. Sony also omits PP181-3602E winding parameters, MNF-1600B motor
constants, belt modulus and flywheel inertia. The current priors are physically bounded and tested,
but a unit-specific digital twin requires bench measurements from the target machine.
