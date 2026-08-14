package com.yqdscott.walktape;

import java.util.Arrays;

/**
 * Real-time stereo model of a Sony TPS-L2, with a legacy self-contained Type-I path.
 *
 * <p>The public Walkman Archive traces have been digitised into a smooth LOW response and the
 * measured HIGH-minus-LOW tone contour.  The same source reports 0.219% RMS wow/flutter and a
 * -67 dB analyser noise reference with a broad 8-9 kHz mound.  Those measurements still do not
 * describe every magnetic and mechanical non-linearity, so the renderer combines the measured
 * transfer targets with a conservative tape, head and transport model in one continuous signal
 * path rather than adding decorative sound effects. Production playback disables this class's
 * legacy media blocks and places an independent {@link TapeMediumDsp} before the unchanged
 * machine response, transport and output stage.</p>
 */
public final class TpsL2Dsp implements TapeMachineDsp {

    private static final double TWO_PI = Math.PI * 2.0;

    // The archive trace sits near -67 dB over much of the band. It is a spectral reference, not
    // the RMS sum of every noise bin. A no-Dolby compact cassette has a substantially higher
    // integrated noise level; this is deliberately the strong "full analogue" voicing.
    private static final float HISS_REFERENCE_RMS = dbToLinear(-67f);
    private static final float INTEGRATED_HISS_RMS = dbToLinear(-57.5f);

    private static final float RANDOM_TRANSPORT_SPEED_RMS = 0.00042f;
    private static final float CAM_TRANSPORT_SPEED_RMS = 0.00055f;
    private static final float CAM_HZ = 0.36f;
    private static final float STARTUP_SLOWDOWN = 0.012f;
    private static final float STARTUP_TIME_CONSTANT_SECONDS = 3.4f;
    private static final float STARTUP_SETTLED_SECONDS = 15f;
    private static final float PROGRAM_CROSSTALK = 0.0125f;
    private static final float AZIMUTH_OFFSET_SAMPLES = 0.16f;
    private static final float AZIMUTH_DRIFT_SAMPLES = 0.055f;
    private static final int TRANSPORT_CONTROL_STRIDE = 4;

    // Remaining belt/capstan/idler components. Together with the measured cam and stochastic
    // terms, their quadrature RMS is exactly the published 0.219% steady-state figure.
    private static final double[] TRANSPORT_HZ = {0.55, 3.2, 5.3, 9.4};
    private static final double[] SPEED_PEAK = {
            0.00245, 0.00070, 0.00135, 0.00056515485
    };
    private static final double[] INITIAL_PHASE = {1.74, 4.21, 2.63, 5.42};

    private final int sampleRate;
    private final boolean transportEnabled;
    private final boolean hissEnabled;
    private final boolean saturationEnabled;
    private final boolean machineStageEnabled;
    private final float[] transportSine = new float[TRANSPORT_HZ.length];
    private final float[] transportCosine = new float[TRANSPORT_HZ.length];
    private final float[] transportStepSine = new float[TRANSPORT_HZ.length];
    private final float[] transportStepCosine = new float[TRANSPORT_HZ.length];
    private final float[] delayAmplitude = new float[TRANSPORT_HZ.length];

    private final float[] delayLeft;
    private final float[] delayRight;
    private final int delayMask;
    private final float baseDelaySamples;
    private final float azimuthStepSine;
    private final float azimuthStepCosine;
    private final float startupDecay;

    private final StereoOnePoleShelf recordPreEmphasis;
    private final StereoOnePoleShelf playbackDeEmphasis;
    private final StereoBiquad highPass;
    private final StereoPeakingFilter headBump;
    private final StereoPeakingFilter measuredMidDip;
    private final StereoBiquad measuredRollOff;
    private final StereoOnePoleShelf measuredHighToneShelf;
    private final TapeChannel tapeLeft;
    private final TapeChannel tapeRight;
    private final NoiseGenerator noiseLeft;
    private final NoiseGenerator noiseRight;
    private final CamTransport camTransport;
    private final TransportNoise transportNoise;
    private final MechanicalBed mechanicalBed;
    private final TpsL2ElectromechanicalModel electromechanics;
    private final TpsL2PlaybackElectronics playbackElectronics;
    private final TpsL2TapeLayerBleed tapeLayerBleed;
    private final float[] layerBleedFrame = new float[2];
    private final float saturationEnvelopeAttack;
    private final float saturationEnvelopeRelease;

    private int writeIndex;
    private float azimuthSine;
    private float azimuthCosine;
    private int oscillatorFrames;
    private float interpolatedTransportDelay;
    private float transportDelayStep;
    private float interpolatedAzimuth;
    private float azimuthInterpolationStep;
    private float startupSlowdown;
    private float startupDelaySamples;
    private float electromechanicalDelaySamples;
    private int transportFramesUntilUpdate;
    private float highTapeMix;
    private float saturationEnvelope;
    private volatile boolean highTape;

    public TpsL2Dsp(int sampleRate) {
        this(sampleRate, 0x5450534cL, true, true, true);
    }

    /** Package-private deterministic constructor used by signal-level tests. */
    TpsL2Dsp(int sampleRate,
             long noiseSeed,
             boolean transportEnabled,
             boolean hissEnabled,
             boolean saturationEnabled) {
        this(sampleRate, noiseSeed, transportEnabled, hissEnabled, saturationEnabled, false);
    }

    /** Machine-only production path keeps the TPS mechanics/electronics but omits baked-in tape. */
    TpsL2Dsp(int sampleRate,
             long noiseSeed,
             boolean transportEnabled,
             boolean hissEnabled,
             boolean saturationEnabled,
             boolean machineStageEnabled) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        this.transportEnabled = transportEnabled;
        this.hissEnabled = hissEnabled;
        this.saturationEnabled = saturationEnabled;
        this.machineStageEnabled = machineStageEnabled;
        camTransport = new CamTransport(sampleRate);
        startupDecay = (float) Math.exp(-TRANSPORT_CONTROL_STRIDE
                / (sampleRate * STARTUP_TIME_CONSTANT_SECONDS));

        double maximumExcursion = 0.0;
        for (int i = 0; i < TRANSPORT_HZ.length; i++) {
            double step = TWO_PI * TRANSPORT_HZ[i] * TRANSPORT_CONTROL_STRIDE
                    / sampleRate;
            transportStepSine[i] = (float) Math.sin(step);
            transportStepCosine[i] = (float) Math.cos(step);
            // Integrating fractional speed error gives its equivalent delay excursion.
            delayAmplitude[i] = (float) (SPEED_PEAK[i] * sampleRate
                    / (TWO_PI * TRANSPORT_HZ[i]));
            maximumExcursion += Math.abs(delayAmplitude[i]);
        }
        double maximumCamExcursion = camTransport.maximumAbsDelaySamples();
        // Integrating the reported initial 1.0-1.5% speed deficit adds a small permanent delay as
        // the slow servo settles. Reserve it up front so startup never reallocates on the audio
        // thread. The two stereo rings still total only a few tens of kilobytes at 48 kHz.
        double maximumStartupDelay = STARTUP_SLOWDOWN
                * STARTUP_TIME_CONSTANT_SECONDS * sampleRate * 1.02;
        // Extra margin covers stochastic scrape flutter and the inter-channel azimuth offset.
        baseDelaySamples = (float) (maximumExcursion + maximumCamExcursion + 8.0);
        int requestedDelaySize = (int) Math.ceil(baseDelaySamples + maximumExcursion
                + maximumCamExcursion + maximumStartupDelay + 16.0);
        int delaySize = 1;
        while (delaySize < requestedDelaySize) {
            delaySize <<= 1;
        }
        delayLeft = new float[delaySize];
        delayRight = new float[delaySize];
        delayMask = delaySize - 1;
        double azimuthStep = TWO_PI * 0.17 * TRANSPORT_CONTROL_STRIDE / sampleRate;
        azimuthStepSine = (float) Math.sin(azimuthStep);
        azimuthStepCosine = (float) Math.cos(azimuthStep);

        float recordCorner = Math.min(1_326f, sampleRate * 0.20f); // 120 us Type-I replay EQ.
        float safeMaximum = sampleRate * 0.43f;

        // Pre/de-emphasis is linear at low level, but treble reaches the magnetic curve first.
        // That creates the level-dependent high-frequency loss characteristic of cassette tape.
        float recordTrebleGain = dbToLinear(5.5f);
        recordPreEmphasis = new StereoOnePoleShelf(sampleRate, recordCorner,
                recordTrebleGain);
        playbackDeEmphasis = new StereoOnePoleShelf(sampleRate, recordCorner,
                1f / recordTrebleGain);
        // Four sections are a least-squares fit to the published LOW trace, normalised at 1 kHz.
        // Representative targets are -2.9 dB at 40 Hz, +1.4 dB at 100 Hz, -1.2 dB at 3.15 kHz,
        // -4.4 dB at 10 kHz, -10.3 dB at 12.5 kHz and -20.3 dB at 16 kHz. This remains much
        // cheaper than a long convolution filter on phones decoding 24-bit lossless material.
        highPass = StereoBiquad.highPass(sampleRate, 33.36f, 0.587f);
        headBump = new StereoPeakingFilter(sampleRate, 88.93f, 1.504f, 1.915f);
        measuredMidDip = new StereoPeakingFilter(sampleRate,
                Math.min(5_013f, safeMaximum), 0.689f, -2.736f);
        measuredRollOff = StereoBiquad.lowPass(sampleRate,
                Math.min(7_712f, safeMaximum), 1.070f);
        // The measured HIGH-minus-LOW trace is a broad shelf, not the bell-shaped boost used by
        // the first reference renderer. This physical one-pole split fits the digitised trace to
        // within 0.22 dB at the regression frequencies while costing less than another biquad.
        measuredHighToneShelf = new StereoOnePoleShelf(sampleRate,
                Math.min(2_710f, safeMaximum), dbToLinear(7.263f));

        tapeLeft = new TapeChannel(sampleRate, 0.038f);
        tapeRight = new TapeChannel(sampleRate, -0.031f);
        saturationEnvelopeAttack = timeCoefficient(sampleRate, 0.0022f);
        saturationEnvelopeRelease = timeCoefficient(sampleRate, 0.085f);
        noiseLeft = new NoiseGenerator(sampleRate, noiseSeed ^ 0x4c454654L,
                INTEGRATED_HISS_RMS);
        noiseRight = new NoiseGenerator(sampleRate, noiseSeed ^ 0x52474854L,
                INTEGRATED_HISS_RMS);
        transportNoise = new TransportNoise(sampleRate / TRANSPORT_CONTROL_STRIDE,
                noiseSeed ^ 0x5452414eL, RANDOM_TRANSPORT_SPEED_RMS,
                TRANSPORT_CONTROL_STRIDE);
        mechanicalBed = new MechanicalBed(sampleRate, noiseSeed ^ 0x4d4f544f52L);
        electromechanics = new TpsL2ElectromechanicalModel(sampleRate,
                noiseSeed ^ 0x504f574552524149L);
        playbackElectronics = new TpsL2PlaybackElectronics(sampleRate);
        tapeLayerBleed = new TpsL2TapeLayerBleed(sampleRate);
        reset();
    }

    @Override
    public void setHighTape(boolean enabled) {
        highTape = enabled;
    }

    @Override
    public void setTapePosition(float position) {
        electromechanics.setTapePosition(position);
        tapeLayerBleed.setTapePosition(position);
    }

    @Override
    public void setTransportState(TapeTransportState state) {
        electromechanics.setTransportState(state);
    }

    @Override
    public void setBatteryDepthOfDischarge(float depth) {
        electromechanics.setBatteryDepthOfDischarge(depth);
    }

    /**
     * One supply-pack revolution, which is what the print-through pre-echo has to look ahead by.
     *
     * <p>Only the production machine stage carries the tape-layer model; the legacy self-contained
     * path stays sample aligned.</p>
     */
    @Override
    public int latencyFrames() {
        return machineStageEnabled ? tapeLayerBleed.latencyFrames() : 0;
    }

    /** Clears all magnetic, transport and filter history after loading or seeking. */
    @Override
    public void reset() {
        Arrays.fill(delayLeft, 0f);
        Arrays.fill(delayRight, 0f);
        for (int component = 0; component < INITIAL_PHASE.length; component++) {
            transportSine[component] = (float) Math.sin(INITIAL_PHASE[component]);
            transportCosine[component] = (float) Math.cos(INITIAL_PHASE[component]);
        }
        writeIndex = 0;
        azimuthSine = (float) Math.sin(2.17);
        azimuthCosine = (float) Math.cos(2.17);
        oscillatorFrames = 0;
        camTransport.reset();
        interpolatedTransportDelay = baseDelaySamples + camTransport.currentDelaySamples();
        for (int component = 0; component < transportSine.length; component++) {
            interpolatedTransportDelay += delayAmplitude[component]
                    * transportSine[component];
        }
        transportDelayStep = 0f;
        interpolatedAzimuth = AZIMUTH_OFFSET_SAMPLES
                + AZIMUTH_DRIFT_SAMPLES * azimuthSine;
        azimuthInterpolationStep = 0f;
        startupSlowdown = STARTUP_SLOWDOWN;
        startupDelaySamples = 0f;
        electromechanicalDelaySamples = 0f;
        transportFramesUntilUpdate = 0;
        saturationEnvelope = 0f;
        highTapeMix = highTape ? 1f : 0f;
        recordPreEmphasis.reset();
        playbackDeEmphasis.reset();
        highPass.reset();
        headBump.reset();
        measuredMidDip.reset();
        measuredRollOff.reset();
        measuredHighToneShelf.reset();
        tapeLeft.reset();
        tapeRight.reset();
        noiseLeft.reset();
        noiseRight.reset();
        transportNoise.reset();
        mechanicalBed.reset();
        electromechanics.reset();
        playbackElectronics.reset();
        tapeLayerBleed.reset();
    }

    /** Processes {@code frameCount} interleaved stereo float frames in place. */
    @Override
    public void process(float[] stereo, int frameCount) {
        if (stereo == null || frameCount < 0 || frameCount > stereo.length / 2) {
            throw new IllegalArgumentException("Invalid stereo frame count");
        }

        final float mixStep = 1f / Math.max(1f, sampleRate * 0.020f);
        // A playback block is at most a few tens of milliseconds. Snapshot the volatile switch and
        // keep the ramp in a register; polling and publishing it for every sample was needlessly
        // expensive on ART and made lossless playback more vulnerable to seek-time underruns.
        final float targetHighTapeMix = highTape ? 1f : 0f;
        float currentHighTapeMix = highTapeMix;
        for (int frame = 0; frame < frameCount; frame++) {
            int sample = frame * 2;
            float left = finiteAudio(stereo[sample]);
            float right = finiteAudio(stereo[sample + 1]);

            if (saturationEnabled) {
                left = recordPreEmphasis.processLeft(left);
                right = recordPreEmphasis.processRight(right);
                float magnitude = Math.max(Math.abs(left), Math.abs(right));
                float envelopeRate = magnitude > saturationEnvelope
                        ? saturationEnvelopeAttack : saturationEnvelopeRelease;
                saturationEnvelope += (magnitude - saturationEnvelope) * envelopeRate;
                // TapeChannel band-limits the transient before its magnetic curve so the stronger
                // saturation stays rounded instead of producing brittle digital edges.
                left = tapeLeft.process(left, saturationEnvelope);
                right = tapeRight.process(right, saturationEnvelope);
            }

            if (transportEnabled) {
                delayLeft[writeIndex] = left;
                delayRight[writeIndex] = right;
                if (transportFramesUntilUpdate == 0) {
                    advanceTransportControl();
                }
                float delay = interpolatedTransportDelay;
                float azimuth = interpolatedAzimuth;
                interpolatedTransportDelay += transportDelayStep;
                interpolatedAzimuth += azimuthInterpolationStep;
                transportFramesUntilUpdate--;

                float readPosition = wrapReadPosition(writeIndex - delay);
                left = quadraticRead(delayLeft, readPosition);

                // A tiny, slowly moving azimuth error makes the two sides of the stereo head less
                // mathematically identical without inventing separate left/right pitch wobble.
                right = quadraticRead(delayRight, wrapReadPosition(readPosition + azimuth));
                writeIndex = (writeIndex + 1) & delayMask;
            }

            if (saturationEnabled) {
                left = playbackDeEmphasis.processLeft(left);
                right = playbackDeEmphasis.processRight(right);
            }

            left = highPass.processLeft(left);
            right = highPass.processRight(right);
            left = headBump.processLeft(left);
            right = headBump.processRight(right);
            left = measuredMidDip.processLeft(left);
            right = measuredMidDip.processRight(right);
            left = measuredRollOff.processLeft(left);
            right = measuredRollOff.processRight(right);

            // Keep the HIGH branch warm even in LOW mode. Switching then crossfades between two
            // continuous signals instead of waking a stale IIR state and producing a tiny click.
            float highLeft = measuredHighToneShelf.processLeft(left);
            float highRight = measuredHighToneShelf.processRight(right);
            if (currentHighTapeMix < targetHighTapeMix) {
                currentHighTapeMix = Math.min(targetHighTapeMix,
                        currentHighTapeMix + mixStep);
            } else if (currentHighTapeMix > targetHighTapeMix) {
                currentHighTapeMix = Math.max(targetHighTapeMix,
                        currentHighTapeMix - mixStep);
            }
            left += (highLeft - left) * currentHighTapeMix;
            right += (highRight - right) * currentHighTapeMix;

            float programEnvelope = saturationEnabled ? Math.min(1f, saturationEnvelope) : 0f;
            if (transportEnabled) {
                float contactGain = transportNoise.contactGain();
                left *= contactGain;
                right *= contactGain * 0.9985f;
            }

            if (saturationEnabled || machineStageEnabled) {
                float originalLeft = left;
                float originalRight = right;
                left = originalLeft * (1f - PROGRAM_CROSSTALK)
                        + originalRight * PROGRAM_CROSSTALK;
                right = originalRight * (1f - PROGRAM_CROSSTALK)
                        + originalLeft * PROGRAM_CROSSTALK;
            }

            if (machineStageEnabled) {
                tapeLayerBleed.process(left, right, layerBleedFrame);
                left = layerBleedFrame[0];
                right = layerBleedFrame[1];
            }

            if (hissEnabled) {
                float mechanism = mechanicalBed.next()
                        * electromechanics.mechanicalNoiseGain();
                mechanism += electromechanics.nextMechanicalTransient();
                left += noiseLeft.next(programEnvelope) + mechanism * 1.04f;
                right += noiseRight.next(programEnvelope) + mechanism * 0.91f;
            } else if (machineStageEnabled) {
                float mechanism = mechanicalBed.next()
                        * electromechanics.mechanicalNoiseGain();
                mechanism += electromechanics.nextMechanicalTransient();
                left += mechanism * 1.04f;
                right += mechanism * 0.91f;
            }

            if (machineStageEnabled) {
                float ripple = electromechanics.nextRailRipple();
                float railScale = electromechanics.outputHeadroomScale();
                playbackElectronics.beginFrame(left, right);
                stereo[sample] = sanitizeOutput(
                        playbackElectronics.processLeft(left, ripple, railScale));
                stereo[sample + 1] = sanitizeOutput(
                        playbackElectronics.processRight(right, ripple, railScale));
            } else if (saturationEnabled) {
                stereo[sample] = sanitizeOutput(analogueOutputStage(left));
                stereo[sample + 1] = sanitizeOutput(analogueOutputStage(right));
            } else {
                stereo[sample] = sanitizeOutput(clamp(left));
                stereo[sample + 1] = sanitizeOutput(clamp(right));
            }
        }
        highTapeMix = currentHighTapeMix;
    }

    public static float nominalWowFlutterRmsPercent() {
        double sumSquares = 0.0;
        for (double peak : SPEED_PEAK) {
            sumSquares += peak * peak * 0.5;
        }
        sumSquares += CAM_TRANSPORT_SPEED_RMS * CAM_TRANSPORT_SPEED_RMS;
        sumSquares += RANDOM_TRANSPORT_SPEED_RMS * RANDOM_TRANSPORT_SPEED_RMS;
        return (float) (Math.sqrt(sumSquares) * 100.0);
    }

    static float startupSpeedDeficitPercent() {
        return STARTUP_SLOWDOWN * 100f;
    }

    static float startupSettledSeconds() {
        return STARTUP_SETTLED_SECONDS;
    }

    static float camWowPeriodSeconds() {
        return 1f / CAM_HZ;
    }

    /** Spectral analyser reference published for the TPS-L2. */
    public static float referenceNoiseFloorDb() {
        return linearToDb(HISS_REFERENCE_RMS);
    }

    /** Full-band RMS target used by the no-Dolby cassette renderer. */
    static float integratedHissFloorDb() {
        return linearToDb(INTEGRATED_HISS_RMS);
    }

    static float serviceManualTransportCurrentMilliamps(TapeTransportState state) {
        if (state == TapeTransportState.FAST_FORWARD) {
            return TpsL2ElectromechanicalModel.FAST_FORWARD_CURRENT_MA;
        }
        if (state == TapeTransportState.REWIND) {
            return TpsL2ElectromechanicalModel.REWIND_CURRENT_MA;
        }
        return TpsL2ElectromechanicalModel.PLAY_CURRENT_MA;
    }

    static float serviceManualMainReservoirMicrofarads() {
        return TpsL2ElectromechanicalModel.MAIN_RESERVOIR_UF;
    }

    private float wrapReadPosition(float position) {
        if (position < 0.0) {
            position += delayLeft.length;
        } else if (position >= delayLeft.length) {
            position -= delayLeft.length;
        }
        return position;
    }

    private float quadraticRead(float[] buffer, float position) {
        // Read positions are wrapped to non-negative values, so truncation is floor without a
        // per-sample libm call.
        int index1 = (int) position;
        float fraction = position - index1;
        float p0 = buffer[(index1 - 1) & delayMask];
        float p1 = buffer[index1 & delayMask];
        float p2 = buffer[(index1 + 1) & delayMask];
        // Three-point Lagrange interpolation retains materially more top-end than a cheap linear
        // delay while costing much less than the former four-point cubic on ART.
        return p1 + 0.5f * fraction * (p2 - p0
                + fraction * (p0 - 2f * p1 + p2));
    }

    private static float analogueOutputStage(float value) {
        // Unity around silence, then a continuously differentiable transformer/output-amplifier
        // knee. Most samples take the linear path; peaks asymptotically approach 0.95 instead of a
        // digital brick-wall edge.
        float driven = value * 1.08f;
        float magnitude = Math.abs(driven);
        if (magnitude <= 0.78f) {
            return driven;
        }
        float excess = magnitude - 0.78f;
        float softened = 0.78f + excess / (1f + excess * 5.882353f);
        return Math.copySign(softened, driven);
    }

    private void advanceTransportControl() {
        // A TPS-L2 servo has been reported to start roughly 1.0-1.5% slow and settle over
        // 10-15 seconds. Integrating that transient into the delay line changes pitch and time;
        // a gain wobble would only create tremolo. The steady-state W&F figure excludes this
        // one-shot startup drift.
        startupDelaySamples += startupSlowdown * TRANSPORT_CONTROL_STRIDE;
        startupSlowdown *= startupDecay;
        float camDelay = camTransport.nextDelaySamples();
        float randomDelay = transportNoise.nextDelaySamples();
        electromechanics.advanceControl(camTransport.currentLoad(),
                transportNoise.normalisedContactLoad(),
                playbackElectronics.programmePowerLoad());
        electromechanicalDelaySamples += electromechanics.residualSpeedError()
                * TRANSPORT_CONTROL_STRIDE;
        electromechanicalDelaySamples = Math.max(-6f,
                Math.min(6f, electromechanicalDelaySamples));
        float targetDelay = baseDelaySamples + startupDelaySamples
                + electromechanicalDelaySamples + camDelay + randomDelay;
        for (int component = 0; component < transportSine.length; component++) {
            float sine = transportSine[component];
            float cosine = transportCosine[component];
            transportSine[component] = sine * transportStepCosine[component]
                    + cosine * transportStepSine[component];
            transportCosine[component] = cosine * transportStepCosine[component]
                    - sine * transportStepSine[component];
            targetDelay += delayAmplitude[component] * transportSine[component];
        }
        transportDelayStep = (targetDelay - interpolatedTransportDelay)
                / TRANSPORT_CONTROL_STRIDE;

        float previousAzimuthSine = azimuthSine;
        azimuthSine = previousAzimuthSine * azimuthStepCosine
                + azimuthCosine * azimuthStepSine;
        azimuthCosine = azimuthCosine * azimuthStepCosine
                - previousAzimuthSine * azimuthStepSine;
        float targetAzimuth = AZIMUTH_OFFSET_SAMPLES
                + AZIMUTH_DRIFT_SAMPLES * azimuthSine;
        azimuthInterpolationStep = (targetAzimuth - interpolatedAzimuth)
                / TRANSPORT_CONTROL_STRIDE;
        transportFramesUntilUpdate = TRANSPORT_CONTROL_STRIDE;

        oscillatorFrames += TRANSPORT_CONTROL_STRIDE;
        if ((oscillatorFrames & 0x3fff) == 0) {
            normaliseOscillators();
        }
    }

    private void normaliseOscillators() {
        for (int component = 0; component < transportSine.length; component++) {
            float magnitude = (float) Math.hypot(transportSine[component],
                    transportCosine[component]);
            transportSine[component] /= magnitude;
            transportCosine[component] /= magnitude;
        }
        float azimuthMagnitude = (float) Math.hypot(azimuthSine, azimuthCosine);
        azimuthSine /= azimuthMagnitude;
        azimuthCosine /= azimuthMagnitude;
    }

    /** C1-continuous magnetic knee: linear at zero and flat at +/-1.5 coercive units. */
    private static float magneticCurve(float value) {
        if (value >= 1.5f) {
            return 1f;
        }
        if (value <= -1.5f) {
            return -1f;
        }
        return value - value * value * value * 0.14814815f;
    }

    private static float clamp(float value) {
        return Math.max(-0.995f, Math.min(0.995f, value));
    }

    private static float finiteAudio(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0f;
        }
        return Math.max(-8f, Math.min(8f, value));
    }

    private static float sanitizeOutput(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 0f : clamp(value);
    }

    private static float dbToLinear(float decibels) {
        return (float) Math.pow(10.0, decibels / 20.0);
    }

    private static float linearToDb(float linear) {
        return 20f * (float) Math.log10(linear);
    }

    /** Stateful, memory-dependent tape magnetisation curve with inexpensive 2x oversampling. */
    private static final class TapeChannel {
        private static final float MAGNETIC_DRIVE = 2.18f;
        private static final float INVERSE_MAGNETIC_DRIVE = 1f / MAGNETIC_DRIVE;
        private final float asymmetry;
        private final float magnetisationRate;
        private final float dynamicLossRate;
        private float previousInput;
        private float magnetisation;
        private float highFrequencyState;
        private float dcInput;
        private float dcOutput;

        TapeChannel(int sampleRate, float asymmetry) {
            this.asymmetry = asymmetry;
            magnetisationRate = timeCoefficient(sampleRate, 0.0017f);
            dynamicLossRate = cutoffCoefficient(sampleRate, 6_900f);
        }

        void reset() {
            previousInput = 0f;
            magnetisation = 0f;
            highFrequencyState = 0f;
            dcInput = 0f;
            dcOutput = 0f;
        }

        float process(float input, float programEnvelope) {
            // A short record-head integration band-limits the curve and softens sample-perfect
            // digital transients. One stateful magnetic solve per frame leaves enough CPU for
            // lossless decoding on a phone.
            float headIntegrated = input * 0.74f + previousInput * 0.26f;
            previousInput = input;
            float output = magnetise(headIntegrated, programEnvelope);

            // Remove only DC created by the deliberately asymmetric magnetic curve.
            float blocked = output - dcInput + 0.9975f * dcOutput;
            dcInput = output;
            dcOutput = blocked;
            return blocked;
        }

        private float magnetise(float input, float programEnvelope) {
            float previousMagnetisation = magnetisation;
            magnetisation += (input - magnetisation) * magnetisationRate;

            float offset = asymmetry + previousMagnetisation * 0.21f;
            float offsetCurve = magneticCurve(offset);
            // Compensate the tiny slope change caused by bias without a per-sample division.
            float curved = (magneticCurve(input * MAGNETIC_DRIVE + offset) - offsetCurve)
                    * INVERSE_MAGNETIC_DRIVE;

            // Most of the signal follows the magnetic curve. The small direct component represents
            // head/electronics feed-through and prevents hard fuzz at full-scale transients.
            float output = input * 0.18f + curved * 0.82f;
            highFrequencyState += (output - highFrequencyState) * dynamicLossRate;
            float loss = Math.min(0.34f, programEnvelope * 0.48f);
            return output + (highFrequencyState - output) * loss;
        }
    }

    /**
     * Periodic load from the auto-stop cam, reported on this mechanism as a small wow every few
     * seconds. A smooth half-wave pulse is integrated once into a delay lookup table, preserving
     * the non-sinusoidal pitch signature without doing transcendental work on the audio thread.
     */
    private static final class CamTransport {
        private static final int TABLE_SIZE = 512;
        private static final int TABLE_MASK = TABLE_SIZE - 1;
        private static final float INITIAL_TABLE_PHASE = TABLE_SIZE * 0.37f;

        private final float[] delayTable = new float[TABLE_SIZE];
        private final float[] loadTable = new float[TABLE_SIZE];
        private final float phaseStep;
        private final float maximumAbsDelaySamples;
        private float tablePhase;
        private float currentLoad;

        CamTransport(int sampleRate) {
            double[] pulse = new double[TABLE_SIZE];
            double mean = 0.0;
            for (int index = 0; index < TABLE_SIZE; index++) {
                double sine = Math.sin(TWO_PI * index / TABLE_SIZE);
                double positive = Math.max(0.0, sine);
                double square = positive * positive;
                double fourth = square * square;
                pulse[index] = fourth * fourth;
                loadTable[index] = (float) pulse[index];
                mean += pulse[index];
            }
            mean /= TABLE_SIZE;

            double squareSum = 0.0;
            for (int index = 0; index < TABLE_SIZE; index++) {
                pulse[index] -= mean;
                squareSum += pulse[index] * pulse[index];
            }
            double pulseRms = Math.sqrt(squareSum / TABLE_SIZE);
            double speedScale = CAM_TRANSPORT_SPEED_RMS / pulseRms;
            double samplesPerTableStep = sampleRate / (CAM_HZ * TABLE_SIZE);

            double accumulatedDelay = 0.0;
            for (int index = 0; index < TABLE_SIZE; index++) {
                delayTable[index] = (float) accumulatedDelay;
                accumulatedDelay += pulse[index] * speedScale * samplesPerTableStep;
            }
            // The discrete pulse has zero mean to floating-point precision. Remove its microscopic
            // residual slope anyway, so an hours-long session can never accumulate table drift.
            for (int index = 0; index < TABLE_SIZE; index++) {
                delayTable[index] -= (float) (accumulatedDelay * index / TABLE_SIZE);
            }

            float minimum = Float.POSITIVE_INFINITY;
            float maximum = Float.NEGATIVE_INFINITY;
            for (float value : delayTable) {
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
            float centre = (minimum + maximum) * 0.5f;
            float largest = 0f;
            for (int index = 0; index < TABLE_SIZE; index++) {
                delayTable[index] -= centre;
                largest = Math.max(largest, Math.abs(delayTable[index]));
            }
            maximumAbsDelaySamples = largest;
            phaseStep = CAM_HZ * TABLE_SIZE * TRANSPORT_CONTROL_STRIDE / sampleRate;
            reset();
        }

        void reset() {
            tablePhase = INITIAL_TABLE_PHASE;
            currentLoad = 0f;
        }

        float maximumAbsDelaySamples() {
            return maximumAbsDelaySamples;
        }

        float currentDelaySamples() {
            int first = (int) tablePhase;
            int second = (first + 1) & TABLE_MASK;
            float fraction = tablePhase - first;
            return delayTable[first] + (delayTable[second] - delayTable[first]) * fraction;
        }

        float nextDelaySamples() {
            float result = currentDelaySamples();
            int loadFirst = (int) tablePhase;
            int loadSecond = (loadFirst + 1) & TABLE_MASK;
            float loadFraction = tablePhase - loadFirst;
            currentLoad = loadTable[loadFirst]
                    + (loadTable[loadSecond] - loadTable[loadFirst]) * loadFraction;
            tablePhase += phaseStep;
            if (tablePhase >= TABLE_SIZE) {
                tablePhase -= TABLE_SIZE;
            }
            return result;
        }

        float currentLoad() {
            return currentLoad;
        }
    }

    /** Band-limited random speed error integrated into a continuously varying tape delay. */
    private static final class TransportNoise {
        private final long seed;
        private final float fastAlpha;
        private final float slowAlpha;
        private final float speedNormalisation;
        private final float slowRms;
        private final int frameStride;
        private long state;
        private float fast;
        private float slow;
        private float delaySamples;

        TransportNoise(int sampleRate, long seed, float targetSpeedRms, int frameStride) {
            this.seed = seed == 0 ? 1 : seed;
            this.frameStride = frameStride;
            fastAlpha = (float) (1.0 - Math.exp(-TWO_PI * 31.0 / sampleRate));
            slowAlpha = (float) (1.0 - Math.exp(-TWO_PI * 0.72 / sampleRate));

            double whiteVariance = 1.0 / 3.0;
            double fastVariance = whiteVariance * fastAlpha / (2.0 - fastAlpha);
            double slowVariance = whiteVariance * slowAlpha / (2.0 - slowAlpha);
            double covariance = whiteVariance * fastAlpha * slowAlpha
                    / (fastAlpha + slowAlpha - fastAlpha * slowAlpha);
            double differenceRms = Math.sqrt(Math.max(1e-20,
                    fastVariance + slowVariance - 2.0 * covariance));
            speedNormalisation = (float) (targetSpeedRms / differenceRms);
            slowRms = (float) Math.sqrt(Math.max(1e-20, slowVariance));
            reset();
        }

        void reset() {
            state = seed;
            fast = 0f;
            slow = 0f;
            delaySamples = 0f;
        }

        float nextDelaySamples() {
            float white = nextWhite();
            fast += fastAlpha * (white - fast);
            slow += slowAlpha * (white - slow);
            delaySamples += (fast - slow) * speedNormalisation * frameStride;
            // This should never engage in normal operation; it bounds pathological multi-hour
            // floating point accumulation without introducing a recurring modulation cycle.
            if (delaySamples > 4f) {
                delaySamples = 4f;
            } else if (delaySamples < -4f) {
                delaySamples = -4f;
            }
            return delaySamples;
        }

        float contactGain() {
            float wander = Math.max(-2.5f, Math.min(2.5f, slow / slowRms));
            return 0.988f + wander * 0.007f;
        }

        float normalisedContactLoad() {
            return Math.max(-1f, Math.min(1f, slow / (slowRms * 2.5f)));
        }

        private float nextWhite() {
            long x = state;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            state = x;
            return ((x >>> 40) / 8_388_607.5f) - 1f;
        }
    }

    private static final class NoiseGenerator {
        private final long seed;
        private final float targetRms;
        private final float bandLimitRate;
        private final float lowBodyRate;
        private final PeakingFilter measuredHissMound;
        private long state;
        private float normalisation;
        private float bandLimited;
        private float lowBody;

        NoiseGenerator(int sampleRate, long seed, float targetRms) {
            this.seed = seed == 0 ? 1 : seed;
            this.targetRms = targetRms;
            float highCut = Math.min(12_000f, sampleRate * 0.43f);
            bandLimitRate = cutoffCoefficient(sampleRate, highCut);
            lowBodyRate = cutoffCoefficient(sampleRate,
                    Math.min(520f, sampleRate * 0.08f));
            measuredHissMound = new PeakingFilter(sampleRate,
                    Math.min(8_600f, sampleRate * 0.35f), 0.82f, 5.2f);
            calibrate();
        }

        void reset() {
            state = seed;
            bandLimited = 0f;
            lowBody = 0f;
            measuredHissMound.reset();
        }

        float next(float programEnvelope) {
            // Magnetic modulation noise grows slightly with recorded level.
            float modulation = 1f + Math.min(0.48f, programEnvelope * 0.82f);
            return shapedNoise() * normalisation * modulation;
        }

        private void calibrate() {
            reset();
            double squareSum = 0.0;
            final int sampleCount = 65_536;
            for (int i = 0; i < sampleCount; i++) {
                float sample = shapedNoise();
                squareSum += sample * sample;
            }
            normalisation = targetRms / (float) Math.sqrt(squareSum / sampleCount);
            reset();
        }

        private float shapedNoise() {
            long x = state;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            state = x;
            float white = ((x >>> 40) / 8_388_607.5f) - 1f;
            bandLimited += (white - bandLimited) * bandLimitRate;
            lowBody += (bandLimited - lowBody) * lowBodyRate;
            float highBand = bandLimited - lowBody;
            // The public noise trace has a valley through the midrange and a broad maximum at
            // 8-9 kHz. A measured bell plus the machine bandwidth follows that shape more closely
            // than the former first-difference tilt, which kept rising toward Nyquist.
            return measuredHissMound.process(highBand);
        }
    }

    /** Quiet correlated motor/roller bed; hiss remains the dominant noise component. */
    private static final class MechanicalBed {
        private static final int CONTROL_STRIDE = 8;

        private final float rollerStepSine;
        private final float rollerStepCosine;
        private final float motorStepSine;
        private final float motorStepCosine;
        private final float commutatorStepSine;
        private final float commutatorStepCosine;
        private final float rollerAmplitude;
        private final float motorAmplitude;
        private final float commutatorAmplitude;
        private float rollerSine;
        private float rollerCosine;
        private float motorSine;
        private float motorCosine;
        private float commutatorSine;
        private float commutatorCosine;
        private float interpolatedOutput;
        private float outputStep;
        private int framesUntilUpdate;
        private int oscillatorFrames;

        MechanicalBed(int sampleRate, long seed) {
            double rollerStep = TWO_PI * 45.8 * CONTROL_STRIDE / sampleRate;
            double motorStep = TWO_PI * 91.7 * CONTROL_STRIDE / sampleRate;
            double commutatorStep = TWO_PI * 275.1 * CONTROL_STRIDE / sampleRate;
            rollerStepSine = (float) Math.sin(rollerStep);
            rollerStepCosine = (float) Math.cos(rollerStep);
            motorStepSine = (float) Math.sin(motorStep);
            motorStepCosine = (float) Math.cos(motorStep);
            commutatorStepSine = (float) Math.sin(commutatorStep);
            commutatorStepCosine = (float) Math.cos(commutatorStep);
            // The published trace contains a low hump around 45 Hz, its strongest narrow line
            // around 90 Hz and a weaker line near 275 Hz. Keep them quiet and mostly correlated;
            // the broad, independent tape hiss remains perceptually dominant.
            rollerAmplitude = dbToLinear(-74.5f);
            motorAmplitude = dbToLinear(-69.5f);
            commutatorAmplitude = dbToLinear(-79.5f);
            reset();
        }

        void reset() {
            rollerSine = (float) Math.sin(4.37);
            rollerCosine = (float) Math.cos(4.37);
            motorSine = (float) Math.sin(0.73);
            motorCosine = (float) Math.cos(0.73);
            commutatorSine = (float) Math.sin(3.11);
            commutatorCosine = (float) Math.cos(3.11);
            interpolatedOutput = oscillatorOutput();
            outputStep = 0f;
            framesUntilUpdate = 0;
            oscillatorFrames = 0;
        }

        float next() {
            if (framesUntilUpdate == 0) {
                advanceControl();
            }
            float result = interpolatedOutput;
            interpolatedOutput += outputStep;
            framesUntilUpdate--;
            return result;
        }

        private void advanceControl() {
            float previousRollerSine = rollerSine;
            rollerSine = previousRollerSine * rollerStepCosine
                    + rollerCosine * rollerStepSine;
            rollerCosine = rollerCosine * rollerStepCosine
                    - previousRollerSine * rollerStepSine;
            float previousMotorSine = motorSine;
            motorSine = previousMotorSine * motorStepCosine + motorCosine * motorStepSine;
            motorCosine = motorCosine * motorStepCosine - previousMotorSine * motorStepSine;
            float previousCommutatorSine = commutatorSine;
            commutatorSine = previousCommutatorSine * commutatorStepCosine
                    + commutatorCosine * commutatorStepSine;
            commutatorCosine = commutatorCosine * commutatorStepCosine
                    - previousCommutatorSine * commutatorStepSine;
            float target = oscillatorOutput();
            outputStep = (target - interpolatedOutput) / CONTROL_STRIDE;
            framesUntilUpdate = CONTROL_STRIDE;
            oscillatorFrames += CONTROL_STRIDE;
            if ((oscillatorFrames & 0x3fff) == 0) {
                float rollerMagnitude = (float) Math.hypot(rollerSine, rollerCosine);
                rollerSine /= rollerMagnitude;
                rollerCosine /= rollerMagnitude;
                float motorMagnitude = (float) Math.hypot(motorSine, motorCosine);
                motorSine /= motorMagnitude;
                motorCosine /= motorMagnitude;
                float commutatorMagnitude = (float) Math.hypot(commutatorSine,
                        commutatorCosine);
                commutatorSine /= commutatorMagnitude;
                commutatorCosine /= commutatorMagnitude;
            }
        }

        private float oscillatorOutput() {
            return rollerSine * rollerAmplitude + motorSine * motorAmplitude
                    + commutatorSine * commutatorAmplitude;
        }
    }

    private static float timeCoefficient(int sampleRate, float seconds) {
        return 1f - (float) Math.exp(-1.0 / Math.max(1.0, sampleRate * seconds));
    }

    private static float cutoffCoefficient(int sampleRate, float frequency) {
        float safeFrequency = Math.min(frequency, sampleRate * 0.43f);
        return 1f - (float) Math.exp(-TWO_PI * safeFrequency / sampleRate);
    }

    /** Lightweight record/replay shelf built from a physical single-pole RC split. */
    private static final class StereoOnePoleShelf {
        private final float rate;
        private final float highGain;
        private float lowLeft;
        private float lowRight;

        StereoOnePoleShelf(int sampleRate, float frequency, float highGain) {
            rate = cutoffCoefficient(sampleRate, frequency);
            this.highGain = highGain;
        }

        float processLeft(float input) {
            lowLeft += (input - lowLeft) * rate;
            return lowLeft + (input - lowLeft) * highGain;
        }

        float processRight(float input) {
            lowRight += (input - lowRight) * rate;
            return lowRight + (input - lowRight) * highGain;
        }

        void reset() {
            lowLeft = 0f;
            lowRight = 0f;
        }
    }

    /**
     * RBJ peaking filters have identical normalised b1 and a1 coefficients. Factoring that
     * shared term removes two multiplies per stereo frame without changing the transfer curve.
     */
    private static final class StereoPeakingFilter {
        private final float b0;
        private final float b2;
        private final float a1;
        private final float a2;
        private float leftZ1;
        private float leftZ2;
        private float rightZ1;
        private float rightZ2;

        StereoPeakingFilter(int sampleRate, float frequency, float q, float gainDb) {
            Biquad coefficients = Biquad.peaking(sampleRate, frequency, q, gainDb);
            b0 = coefficients.b0;
            b2 = coefficients.b2;
            a1 = coefficients.a1;
            a2 = coefficients.a2;
        }

        float processLeft(float input) {
            float output = input * b0 + leftZ1;
            leftZ1 = (input - output) * a1 + leftZ2;
            leftZ2 = input * b2 - output * a2;
            return output;
        }

        float processRight(float input) {
            float output = input * b0 + rightZ1;
            rightZ1 = (input - output) * a1 + rightZ2;
            rightZ2 = input * b2 - output * a2;
            return output;
        }

        void reset() {
            leftZ1 = 0f;
            leftZ2 = 0f;
            rightZ1 = 0f;
            rightZ2 = 0f;
        }
    }

    /** Mono form used for each channel's independent magnetic hiss. */
    private static final class PeakingFilter {
        private final float b0;
        private final float b2;
        private final float a1;
        private final float a2;
        private float z1;
        private float z2;

        PeakingFilter(int sampleRate, float frequency, float q, float gainDb) {
            Biquad coefficients = Biquad.peaking(sampleRate, frequency, q, gainDb);
            b0 = coefficients.b0;
            b2 = coefficients.b2;
            a1 = coefficients.a1;
            a2 = coefficients.a2;
        }

        float process(float input) {
            float output = input * b0 + z1;
            z1 = (input - output) * a1 + z2;
            z2 = input * b2 - output * a2;
            return output;
        }

        void reset() {
            z1 = 0f;
            z2 = 0f;
        }
    }

    private static final class StereoBiquad {
        private final float leftB0;
        private final float leftB1;
        private final float leftB2;
        private final float leftA1;
        private final float leftA2;
        private final float rightB0;
        private final float rightB1;
        private final float rightB2;
        private final float rightA1;
        private final float rightA2;
        private float leftZ1;
        private float leftZ2;
        private float rightZ1;
        private float rightZ2;

        private StereoBiquad(Biquad left, Biquad right) {
            leftB0 = left.b0;
            leftB1 = left.b1;
            leftB2 = left.b2;
            leftA1 = left.a1;
            leftA2 = left.a2;
            rightB0 = right.b0;
            rightB1 = right.b1;
            rightB2 = right.b2;
            rightA1 = right.a1;
            rightA2 = right.a2;
        }

        static StereoBiquad highPass(int sampleRate, float frequency, float q) {
            return new StereoBiquad(
                    Biquad.highPass(sampleRate, frequency, q),
                    Biquad.highPass(sampleRate, frequency, q));
        }

        static StereoBiquad lowPass(int sampleRate, float frequency, float q) {
            return new StereoBiquad(
                    Biquad.lowPass(sampleRate, frequency, q),
                    Biquad.lowPass(sampleRate, frequency, q));
        }

        static StereoBiquad lowPassAsymmetric(int sampleRate,
                                               float leftFrequency,
                                               float rightFrequency,
                                               float q) {
            return new StereoBiquad(
                    Biquad.lowPass(sampleRate, leftFrequency, q),
                    Biquad.lowPass(sampleRate, rightFrequency, q));
        }

        static StereoBiquad peaking(int sampleRate, float frequency, float q, float gainDb) {
            return new StereoBiquad(
                    Biquad.peaking(sampleRate, frequency, q, gainDb),
                    Biquad.peaking(sampleRate, frequency, q, gainDb));
        }

        static StereoBiquad highShelf(int sampleRate,
                                      float frequency,
                                      float slope,
                                      float gainDb) {
            return new StereoBiquad(
                    Biquad.highShelf(sampleRate, frequency, slope, gainDb),
                    Biquad.highShelf(sampleRate, frequency, slope, gainDb));
        }

        float processLeft(float input) {
            float output = input * leftB0 + leftZ1;
            leftZ1 = input * leftB1 - output * leftA1 + leftZ2;
            leftZ2 = input * leftB2 - output * leftA2;
            return output;
        }

        float processRight(float input) {
            float output = input * rightB0 + rightZ1;
            rightZ1 = input * rightB1 - output * rightA1 + rightZ2;
            rightZ2 = input * rightB2 - output * rightA2;
            return output;
        }

        void reset() {
            leftZ1 = 0f;
            leftZ2 = 0f;
            rightZ1 = 0f;
            rightZ2 = 0f;
        }
    }

    private static final class Biquad {
        private final float b0;
        private final float b1;
        private final float b2;
        private final float a1;
        private final float a2;
        private float z1;
        private float z2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0);
            this.b1 = (float) (b1 / a0);
            this.b2 = (float) (b2 / a0);
            this.a1 = (float) (a1 / a0);
            this.a2 = (float) (a2 / a0);
        }

        static Biquad lowPass(int sampleRate, float frequency, float q) {
            double omega = TWO_PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            return new Biquad((1.0 - cosine) * 0.5, 1.0 - cosine,
                    (1.0 - cosine) * 0.5, 1.0 + alpha, -2.0 * cosine, 1.0 - alpha);
        }

        static Biquad highPass(int sampleRate, float frequency, float q) {
            double omega = TWO_PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            return new Biquad((1.0 + cosine) * 0.5, -(1.0 + cosine),
                    (1.0 + cosine) * 0.5, 1.0 + alpha, -2.0 * cosine, 1.0 - alpha);
        }

        static Biquad peaking(int sampleRate, float frequency, float q, float gainDb) {
            double amplitude = Math.pow(10.0, gainDb / 40.0);
            double omega = TWO_PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            return new Biquad(1.0 + alpha * amplitude, -2.0 * cosine,
                    1.0 - alpha * amplitude, 1.0 + alpha / amplitude,
                    -2.0 * cosine, 1.0 - alpha / amplitude);
        }

        static Biquad highShelf(int sampleRate,
                                float frequency,
                                float slope,
                                float gainDb) {
            double amplitude = Math.pow(10.0, gainDb / 40.0);
            double omega = TWO_PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double sine = Math.sin(omega);
            double alpha = sine * 0.5 * Math.sqrt(
                    (amplitude + 1.0 / amplitude) * (1.0 / slope - 1.0) + 2.0);
            double beta = 2.0 * Math.sqrt(amplitude) * alpha;
            return new Biquad(
                    amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + beta),
                    -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine),
                    amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta),
                    (amplitude + 1.0) - (amplitude - 1.0) * cosine + beta,
                    2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine),
                    (amplitude + 1.0) - (amplitude - 1.0) * cosine - beta);
        }

        float process(float input) {
            float output = input * b0 + z1;
            z1 = input * b1 - output * a1 + z2;
            z2 = input * b2 - output * a2;
            return output;
        }

        void reset() {
            z1 = 0f;
            z2 = 0f;
        }
    }
}
