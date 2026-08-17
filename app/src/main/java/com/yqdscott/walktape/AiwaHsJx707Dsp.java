package com.yqdscott.walktape;

import java.util.Arrays;

/**
 * Realtime machine-only reference model of a serviced Aiwa HS-JX707 with BBE and DSL disabled.
 *
 * <p>No trustworthy per-frequency sweep of a correctly aligned JX707 is publicly available, so
 * most of this renderer is modelled against Aiwa's 1992 service limits: the usable bands end at
 * 8 kHz and 12.5 kHz respectively, steady transport remains below the published 0.45% RMS
 * service ceiling, and unassisted playback exceeds the specified 45 dB signal-to-noise ratio.
 * The 0.320% RMS transport target is a conservative serviced-unit value inside that envelope,
 * not a claim that every surviving machine measures identically.</p>
 *
 * <p>The <em>replay equalisation</em> is no longer part of that envelope-fitting. It is solved
 * from Aiwa's own component values by {@link AiwaHsJx707ReplayEq}, whose netlist was read off the
 * service manual schematic and validated against the IEC characteristic to 1.2 dB on Normal and
 * 1.9 dB on Cr/Metal from 63 Hz to 16 kHz. The record pre-emphasis and its exact inverse either
 * side of the tape stage carry the standard curve, so what {@code ReplayErrorEq} adds is only this
 * machine's departure from a perfect deck: a slight presence lift, a tape-type-dependent treble
 * tilt, and a bass turnover set by C19 and R17 that reaches -4.5 dB at 20 Hz. A spec-derived 63 Hz
 * Butterworth high-pass used to stand in for that turnover and has been removed, because keeping
 * both would count the same rolloff twice.</p>
 *
 * <p>What the schematic cannot supply stays an explicit prior. The head's long-wavelength contour
 * and the 8 kHz / 12.5 kHz endpoints are head and tape losses, not amplifier behaviour, so they
 * remain spec-derived and are marked as such where they are built.</p>
 *
 * <p>Production playback supplies magnetic non-linearity and tape hiss through the independent
 * {@link TapeMediumDsp}; {@link DolbyNoiseReductionDsp} supplies the service-manual-confirmed
 * B/C path around that medium. The package-private machine-only constructor prevents those
 * media effects from being counted twice while retaining the JX707 mechanics and electronics.</p>
 */
public final class AiwaHsJx707Dsp implements TapeMachineDsp {
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int TRANSPORT_CONTROL_STRIDE = 4;
    private static final float RANDOM_SPEED_RMS = 0.00050f;
    private static final float INTEGRATED_HISS_RMS = dbToLinear(-56f);
    private static final float PROGRAM_CROSSTALK = 0.0085f;

    // A belt/capstan fundamental, the auto-reverse transport's mid-rate modulation and two
    // flutter components. Their quadrature sum plus the stochastic term is 0.320% RMS.
    private static final double[] TRANSPORT_HZ = {0.48, 2.85, 5.70, 10.80};
    private static final double[] SPEED_PEAK = {0.00365, 0.00165, 0.00175, 0.00095};
    private static final double[] INITIAL_PHASE = {1.31, 4.74, 2.38, 5.63};

    private final int sampleRate;
    private final boolean transportEnabled;
    private final boolean hissEnabled;
    private final boolean saturationEnabled;
    private final boolean machineNoiseEnabled;
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
    private final StereoShelf normalRecordEq;
    private final StereoShelf metalRecordEq;
    private final StereoShelf normalPlaybackEq;
    private final StereoShelf metalPlaybackEq;
    private final ReplayErrorEq normalReplayError;
    private final ReplayErrorEq metalReplayError;
    private final StereoPeaking headContour;
    private final StereoBiquad normalBandwidth;
    private final StereoBiquad metalBandwidth;
    private final StereoOnePoleHighPass outputCoupling;
    private final BbeProcessor bbe;
    private final TapeTransportDynamics transport;
    private final StereoBiquad spacingLoss;
    private final float bbeMixStep;
    private final float clipKnee;
    private final float clipCompression;
    private final TapeStage tapeLeft;
    private final TapeStage tapeRight;
    private final HissGenerator hissLeft;
    private final HissGenerator hissRight;
    private final MotorBed motorBed;
    private final IrregularFlutter irregularFlutter;
    private final float envelopeAttack;
    private final float envelopeRelease;

    private volatile boolean highTape;
    private volatile boolean bbeEnabled;
    private float bbeMix;
    private float highTapeMix;
    private float saturationEnvelope;
    private int writeIndex;
    private int transportFramesUntilUpdate;
    private int oscillatorFrames;
    private float interpolatedDelay;
    private float delayStep;
    private float randomDelay;
    private float azimuthSine;
    private float azimuthCosine;
    private float interpolatedAzimuth;
    private float azimuthStep;
    private int transportControlCountdown;
    private boolean modeTransitionActive;
    private boolean transitionTargetHigh;

    public AiwaHsJx707Dsp(int sampleRate) {
        this(sampleRate, 0x4a58373037L, true, true, true);
    }

    /** Package-private deterministic constructor for signal-level calibration tests. */
    AiwaHsJx707Dsp(int sampleRate,
                   long noiseSeed,
                   boolean transportEnabled,
                   boolean hissEnabled,
                   boolean saturationEnabled) {
        this(sampleRate, noiseSeed, transportEnabled, hissEnabled, saturationEnabled, false);
    }

    /** Machine-only production path retains motor/roller noise without duplicating tape hiss. */
    AiwaHsJx707Dsp(int sampleRate,
                   long noiseSeed,
                   boolean transportEnabled,
                   boolean hissEnabled,
                   boolean saturationEnabled,
                   boolean machineNoiseEnabled) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        this.transportEnabled = transportEnabled;
        this.hissEnabled = hissEnabled;
        this.saturationEnabled = saturationEnabled;
        this.machineNoiseEnabled = machineNoiseEnabled;

        double maximumExcursion = 0.0;
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            double phaseStep = TWO_PI * TRANSPORT_HZ[component]
                    * TRANSPORT_CONTROL_STRIDE / sampleRate;
            transportStepSine[component] = (float) Math.sin(phaseStep);
            transportStepCosine[component] = (float) Math.cos(phaseStep);
            delayAmplitude[component] = (float) (SPEED_PEAK[component] * sampleRate
                    / (TWO_PI * TRANSPORT_HZ[component]));
            maximumExcursion += Math.abs(delayAmplitude[component]);
        }
        baseDelaySamples = (float) maximumExcursion + 14f;
        // Head room for the delay a spin-up accumulates: the tape runs slow for a few hundred
        // milliseconds, and every sample of that shortfall has to live in this buffer.
        float maximumSlipSamples = sampleRate * 0.5f;
        int requiredDelay = (int) Math.ceil(baseDelaySamples + maximumExcursion + 36f
                + maximumSlipSamples);
        int delaySize = 1;
        while (delaySize < requiredDelay) {
            delaySize <<= 1;
        }
        delayLeft = new float[delaySize];
        delayRight = new float[delaySize];
        delayMask = delaySize - 1;
        double azimuthPhaseStep = TWO_PI * 0.13 * TRANSPORT_CONTROL_STRIDE / sampleRate;
        azimuthStepSine = (float) Math.sin(azimuthPhaseStep);
        azimuthStepCosine = (float) Math.cos(azimuthPhaseStep);

        float safeTop = sampleRate * 0.43f;
        float normalCorner = Math.min(1_326f, safeTop); // 120 us
        float metalCorner = Math.min(2_274f, safeTop);  // 70 us
        normalRecordEq = new StereoShelf(sampleRate, normalCorner, dbToLinear(5.2f));
        metalRecordEq = new StereoShelf(sampleRate, metalCorner, dbToLinear(4.4f));
        normalPlaybackEq = new StereoShelf(sampleRate, normalCorner, 1f / dbToLinear(5.2f));
        metalPlaybackEq = new StereoShelf(sampleRate, metalCorner, 1f / dbToLinear(4.4f));

        // The replay equaliser is solved from Aiwa's own component values rather than approximated
        // from the spec envelope, so what reaches the output is this machine's real departure from
        // the IEC characteristic. It supplies the electrical bass rolloff that a spec-derived
        // high-pass used to stand in for.
        AiwaHsJx707ReplayEq replayEq = new AiwaHsJx707ReplayEq();
        normalReplayError = new ReplayErrorEq(sampleRate, replayEq, false);
        metalReplayError = new ReplayErrorEq(sampleRate, replayEq, true);

        // Aiwa specifies a usable band, not a measured centre-line curve. Butterworth corners at
        // the published endpoints keep the model centred safely inside the +/-4.5 dB envelope.
        // The head's own long-wavelength contour stays a prior: the schematic cannot supply it.
        headContour = new StereoPeaking(sampleRate, Math.min(108f, safeTop), 0.82f, 0.85f);
        normalBandwidth = StereoBiquad.lowPass(sampleRate, Math.min(8_000f, safeTop), 0.7071f);
        metalBandwidth = StereoBiquad.lowPass(sampleRate, Math.min(12_500f, safeTop), 0.7071f);

        // C86/C85 220u feeding the rated 32 ohm load. The Zobel and the chip coil in the same
        // network sit at 154 kHz and 1.5 MHz, so this capacitor is the only part of the output
        // that lands in the audio band.
        AiwaHsJx707OutputStage output = new AiwaHsJx707OutputStage();
        outputCoupling = new StereoOnePoleHighPass(sampleRate,
                output.couplingTimeConstantSeconds());
        clipKnee = (float) output.linearFractionOfSwing();
        clipCompression = 1f / (1f - clipKnee);

        // IC4's BBE, from the licensed BBE family rather than from the XRC5484, which nobody
        // publishes. It sits where the real one does: after the replay chain and the head and tape
        // losses, before the volume control and the main amplifier.
        bbe = new BbeProcessor(sampleRate, new AiwaHsJx707Bbe());
        // The keyed transport. Nothing about a mechanism this size happens on the sample the
        // key goes down, so PLAY glides into tune and PAUSE coasts out of it.
        transport = new TapeTransportDynamics(sampleRate, TRANSPORT_CONTROL_STRIDE,
                maximumSlipSamples);
        // Losing head contact costs treble before it costs level: the gap between head and
        // oxide attenuates short wavelengths first, which is spacing loss.
        spacingLoss = StereoBiquad.lowPass(sampleRate, Math.min(2_600f, safeTop), 0.7071f);
        bbeMixStep = 1f / Math.max(1f, sampleRate * 0.025f);

        tapeLeft = new TapeStage(sampleRate, 0.026f);
        tapeRight = new TapeStage(sampleRate, -0.021f);
        envelopeAttack = timeCoefficient(sampleRate, 0.0028f);
        envelopeRelease = timeCoefficient(sampleRate, 0.105f);
        hissLeft = new HissGenerator(sampleRate, noiseSeed ^ 0x414957414cL,
                INTEGRATED_HISS_RMS);
        hissRight = new HissGenerator(sampleRate, noiseSeed ^ 0x4149574152L,
                INTEGRATED_HISS_RMS);
        motorBed = new MotorBed(sampleRate, noiseSeed ^ 0x4a58373037L);
        irregularFlutter = new IrregularFlutter(noiseSeed ^ 0x5452414e53L);
        reset();
    }

    @Override
    public void setHighTape(boolean enabled) {
        highTape = enabled;
    }

    /**
     * Aiwa's S5 slide switch. Off is what this renderer has always shipped, so it stays the
     * default and turning it on is the only thing that changes the sound.
     */
    /**
     * A key press on the transport, which the mechanism then takes its own time to obey.
     *
     * <p>The renderer does not jump: PLAY winds the capstan up over a third of a second and the
     * pitch rises into tune, PAUSE lets the tape coast to a halt against a head that is still
     * touching it, STOP retracts the head so the sound goes before the tape does, and winding
     * lifts the head clear.</p>
     */
    @Override
    public void setTransportState(TapeTransportState state) {
        transport.setState(state);
    }

    @Override
    public void setBbeEnabled(boolean enabled) {
        bbeEnabled = enabled;
    }

    public boolean isBbeEnabled() {
        return bbeEnabled;
    }

    @Override
    public void reset() {
        Arrays.fill(delayLeft, 0f);
        Arrays.fill(delayRight, 0f);
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            transportSine[component] = (float) Math.sin(INITIAL_PHASE[component]);
            transportCosine[component] = (float) Math.cos(INITIAL_PHASE[component]);
        }
        writeIndex = 0;
        transportFramesUntilUpdate = 0;
        oscillatorFrames = 0;
        randomDelay = 0f;
        interpolatedDelay = baseDelaySamples;
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            interpolatedDelay += delayAmplitude[component] * transportSine[component];
        }
        delayStep = 0f;
        azimuthSine = (float) Math.sin(2.71);
        azimuthCosine = (float) Math.cos(2.71);
        interpolatedAzimuth = 0.11f + 0.035f * azimuthSine;
        azimuthStep = 0f;
        highTapeMix = highTape ? 1f : 0f;
        modeTransitionActive = false;
        transitionTargetHigh = highTape;
        saturationEnvelope = 0f;
        normalRecordEq.reset();
        metalRecordEq.reset();
        normalPlaybackEq.reset();
        metalPlaybackEq.reset();
        normalReplayError.reset();
        metalReplayError.reset();
        headContour.reset();
        normalBandwidth.reset();
        metalBandwidth.reset();
        outputCoupling.reset();
        bbe.reset();
        // A renderer rebuilt at the audible playhead is a machine already running, so the
        // transport settles rather than restarting: a seek must not sound like pressing PLAY.
        transport.reset();
        spacingLoss.reset();
        transportControlCountdown = 0;
        bbeMix = bbeEnabled ? 1f : 0f;
        tapeLeft.reset();
        tapeRight.reset();
        hissLeft.reset();
        hissRight.reset();
        motorBed.reset();
        irregularFlutter.reset();
    }

    @Override
    public void process(float[] stereo, int frameCount) {
        if (frameCount < 0 || frameCount * 2 > stereo.length) {
            throw new IllegalArgumentException("Invalid stereo frame count");
        }
        final boolean targetHigh = highTape;
        final float targetMix = targetHigh ? 1f : 0f;
        final float bbeTarget = bbeEnabled ? 1f : 0f;
        if (bbeTarget > 0f && bbeMix <= 0f) {
            // Coming back from fully off the filters hold nothing, so clear the detector too and
            // let the 25 ms ramp cover the start-up transient.
            bbe.reset();
        }
        final float mixStep = 1f / Math.max(1f, sampleRate * 0.025f);
        float modeMix = highTapeMix;
        float envelope = saturationEnvelope;

        if (targetMix != modeMix
                && (!modeTransitionActive || transitionTargetHigh != targetHigh)) {
            resetModePath(targetHigh);
            modeTransitionActive = true;
            transitionTargetHigh = targetHigh;
        }

        for (int frame = 0; frame < frameCount; frame++) {
            int sample = frame * 2;
            float left = stereo[sample];
            float right = stereo[sample + 1];

            if (targetMix > modeMix) {
                modeMix = Math.min(targetMix, modeMix + mixStep);
            } else if (targetMix < modeMix) {
                modeMix = Math.max(targetMix, modeMix - mixStep);
            }

            boolean transitioning = modeMix > 0f && modeMix < 1f;
            if (saturationEnabled) {
                if (transitioning) {
                    float normalLeft = normalRecordEq.processLeft(left);
                    float normalRight = normalRecordEq.processRight(right);
                    float metalLeft = metalRecordEq.processLeft(left);
                    float metalRight = metalRecordEq.processRight(right);
                    left = lerp(normalLeft, metalLeft, modeMix);
                    right = lerp(normalRight, metalRight, modeMix);
                } else if (modeMix >= 1f) {
                    left = metalRecordEq.processLeft(left);
                    right = metalRecordEq.processRight(right);
                } else {
                    left = normalRecordEq.processLeft(left);
                    right = normalRecordEq.processRight(right);
                }
                float magnitude = Math.max(Math.abs(left), Math.abs(right));
                float rate = magnitude > envelope ? envelopeAttack : envelopeRelease;
                envelope += (magnitude - envelope) * rate;
                left = tapeLeft.process(left, envelope, modeMix);
                right = tapeRight.process(right, envelope, modeMix);
            }

            if (transportControlCountdown <= 0) {
                transport.advance(TRANSPORT_CONTROL_STRIDE);
                transportControlCountdown = TRANSPORT_CONTROL_STRIDE;
            }
            transportControlCountdown--;

            if (transportEnabled) {
                delayLeft[writeIndex] = left;
                delayRight[writeIndex] = right;
                if (transportFramesUntilUpdate <= 0) {
                    updateTransport();
                    transportFramesUntilUpdate = TRANSPORT_CONTROL_STRIDE;
                }
                interpolatedDelay += delayStep;
                interpolatedAzimuth += azimuthStep;
                left = readDelay(delayLeft, interpolatedDelay);
                right = readDelay(delayRight, interpolatedDelay + interpolatedAzimuth);
                writeIndex = (writeIndex + 1) & delayMask;
                transportFramesUntilUpdate--;
            }

            // Head-to-tape contact, applied where the head is: before any of the electronics.
            // Treble goes before level, because a gap between head and oxide attenuates short
            // wavelengths first. Both filters keep running at full contact so re-engaging the
            // head does not start from a cleared state.
            float spacedLeft = spacingLoss.processLeft(left);
            float spacedRight = spacingLoss.processRight(right);
            float contact = transport.headContact();
            float headGain = transport.headOutputGain();
            if (contact < 0.9995f || headGain < 0.9995f) {
                left = lerp(spacedLeft, left, contact) * headGain;
                right = lerp(spacedRight, right, contact) * headGain;
            }

            if (saturationEnabled) {
                if (transitioning) {
                    float normalLeft = normalPlaybackEq.processLeft(left);
                    float normalRight = normalPlaybackEq.processRight(right);
                    float metalLeft = metalPlaybackEq.processLeft(left);
                    float metalRight = metalPlaybackEq.processRight(right);
                    left = lerp(normalLeft, metalLeft, modeMix);
                    right = lerp(normalRight, metalRight, modeMix);
                } else if (modeMix >= 1f) {
                    left = metalPlaybackEq.processLeft(left);
                    right = metalPlaybackEq.processRight(right);
                } else {
                    left = normalPlaybackEq.processLeft(left);
                    right = normalPlaybackEq.processRight(right);
                }
            }

            if (transitioning) {
                float normalLeft = normalReplayError.processLeft(left);
                float normalRight = normalReplayError.processRight(right);
                float metalLeft = metalReplayError.processLeft(left);
                float metalRight = metalReplayError.processRight(right);
                left = lerp(normalLeft, metalLeft, modeMix);
                right = lerp(normalRight, metalRight, modeMix);
            } else if (modeMix >= 1f) {
                left = metalReplayError.processLeft(left);
                right = metalReplayError.processRight(right);
            } else {
                left = normalReplayError.processLeft(left);
                right = normalReplayError.processRight(right);
            }

            left = headContour.processLeft(left);
            right = headContour.processRight(right);

            if (hissEnabled || machineNoiseEnabled) {
                if (hissEnabled) {
                    left += hissLeft.next();
                    right += hissRight.next();
                }
                // The running gear arrives and leaves with the motor rather than switching, and
                // a key press adds one cam/lever thump that the chassis conducts into the head.
                // Both ride the same mechanical bed because in the machine they are the same path.
                float motor = motorBed.next();
                float mechanical = motor * transport.motorNoiseGain()
                        + motor * transport.transitionEnergy() * 5.5f;
                left += mechanical;
                right += mechanical * 0.87f;
            }

            if (transitioning) {
                float normalLeft = normalBandwidth.processLeft(left);
                float normalRight = normalBandwidth.processRight(right);
                float metalLeft = metalBandwidth.processLeft(left);
                float metalRight = metalBandwidth.processRight(right);
                left = lerp(normalLeft, metalLeft, modeMix);
                right = lerp(normalRight, metalRight, modeMix);
            } else if (modeMix >= 1f) {
                left = metalBandwidth.processLeft(left);
                right = metalBandwidth.processRight(right);
            } else {
                left = normalBandwidth.processLeft(left);
                right = normalBandwidth.processRight(right);
            }

            // IC4. The real one is switched in ahead of the volume control and the main amp, so
            // it sees the machine's own band limits rather than a flat signal, which is why it
            // goes after the bandwidth stage rather than at the head.
            if (bbeTarget != bbeMix) {
                bbeMix = bbeTarget > bbeMix
                        ? Math.min(bbeTarget, bbeMix + bbeMixStep)
                        : Math.max(bbeTarget, bbeMix - bbeMixStep);
            }
            if (bbeMix > 0f) {
                float wetLeft = bbe.processLeft(left);
                float wetRight = bbe.processRight(right);
                left = bbeMix >= 1f ? wetLeft : lerp(left, wetLeft, bbeMix);
                right = bbeMix >= 1f ? wetRight : lerp(right, wetRight, bbeMix);
            }

            float crossedLeft = left + PROGRAM_CROSSTALK * right;
            float crossedRight = right + PROGRAM_CROSSTALK * left;
            crossedLeft = outputCoupling.processLeft(crossedLeft);
            crossedRight = outputCoupling.processRight(crossedRight);
            stereo[sample] = outputStage(crossedLeft);
            stereo[sample + 1] = outputStage(crossedRight);
        }
        highTapeMix = modeMix;
        saturationEnvelope = envelope;
        if (modeMix == targetMix) {
            modeTransitionActive = false;
        }
    }

    private void resetModePath(boolean metal) {
        if (metal) {
            metalRecordEq.reset();
            metalPlaybackEq.reset();
            metalReplayError.reset();
            metalBandwidth.reset();
        } else {
            normalRecordEq.reset();
            normalPlaybackEq.reset();
            normalReplayError.reset();
            normalBandwidth.reset();
        }
    }

    private void updateTransport() {
        float targetDelay = baseDelaySamples;
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            float sine = transportSine[component];
            float cosine = transportCosine[component];
            float nextSine = sine * transportStepCosine[component]
                    + cosine * transportStepSine[component];
            float nextCosine = cosine * transportStepCosine[component]
                    - sine * transportStepSine[component];
            transportSine[component] = nextSine;
            transportCosine[component] = nextCosine;
            targetDelay += delayAmplitude[component] * nextSine;
        }
        // A leaky integration turns the specified stochastic speed term into bounded head/tape
        // displacement. It cannot accumulate an unbounded random walk during a long album.
        randomDelay = randomDelay * 0.99935f
                + irregularFlutter.nextSpeedError() * TRANSPORT_CONTROL_STRIDE;
        targetDelay += randomDelay;
        // Running slow means the programme arrives late. Feeding that shortfall into the same
        // delay line the wow uses is what makes a spin-up an audible pitch glide rather than a
        // fade: the read pointer falls behind the write pointer while the capstan catches up.
        targetDelay += transport.slipSamples();
        delayStep = (targetDelay - interpolatedDelay) / TRANSPORT_CONTROL_STRIDE;

        float nextAzimuthSine = azimuthSine * azimuthStepCosine
                + azimuthCosine * azimuthStepSine;
        float nextAzimuthCosine = azimuthCosine * azimuthStepCosine
                - azimuthSine * azimuthStepSine;
        azimuthSine = nextAzimuthSine;
        azimuthCosine = nextAzimuthCosine;
        float targetAzimuth = 0.11f + 0.035f * nextAzimuthSine;
        azimuthStep = (targetAzimuth - interpolatedAzimuth) / TRANSPORT_CONTROL_STRIDE;

        oscillatorFrames += TRANSPORT_CONTROL_STRIDE;
        if (oscillatorFrames >= 16_384) {
            oscillatorFrames = 0;
            for (int component = 0; component < TRANSPORT_HZ.length; component++) {
                float norm = (float) (1.0 / Math.sqrt(transportSine[component]
                        * transportSine[component] + transportCosine[component]
                        * transportCosine[component]));
                transportSine[component] *= norm;
                transportCosine[component] *= norm;
            }
            float azimuthNorm = (float) (1.0 / Math.sqrt(azimuthSine * azimuthSine
                    + azimuthCosine * azimuthCosine));
            azimuthSine *= azimuthNorm;
            azimuthCosine *= azimuthNorm;
        }
    }

    private float readDelay(float[] ring, float delaySamples) {
        float read = writeIndex - delaySamples;
        int lower = (int) read;
        if (read < lower) {
            lower--;
        }
        float fraction = read - lower;
        float first = ring[lower & delayMask];
        float second = ring[(lower + 1) & delayMask];
        return first + (second - first) * fraction;
    }

    public static float nominalWowFlutterRmsPercent() {
        double sum = RANDOM_SPEED_RMS * RANDOM_SPEED_RMS;
        for (double peak : SPEED_PEAK) {
            sum += peak * peak * 0.5;
        }
        return (float) (Math.sqrt(sum) * 100.0);
    }

    public static float serviceLimitWowFlutterRmsPercent() {
        return 0.45f;
    }

    public static float integratedHissFloorDb() {
        return -56f;
    }

    /**
     * The TA7688F running out of swing, with both constants derived rather than chosen.
     *
     * <p>The knee is where Toshiba's THD leaves its floor — 10 mW into 32 ohm, 0.533 of the
     * available swing — and the curve's asymptote is the rail itself, which fixes the compression
     * coefficient at {@code 1 / (1 - knee)} with nothing left to pick. 10% THD then falls near
     * Toshiba's 27 mW point. The previous knee of 0.86 was invented and, worse, its ceiling of 1.04
     * sat above the rail the part actually has.</p>
     */
    private float outputStage(float input) {
        float magnitude = Math.abs(input);
        if (magnitude <= clipKnee) {
            return input;
        }
        float excess = magnitude - clipKnee;
        float limited = Math.min(1f, clipKnee + excess / (1f + excess * clipCompression));
        return input < 0f ? -limited : limited;
    }

    private static float lerp(float from, float to, float mix) {
        return from + (to - from) * mix;
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static float timeCoefficient(int sampleRate, float seconds) {
        return 1f - (float) Math.exp(-1.0 / Math.max(1.0, sampleRate * seconds));
    }

    private static final class IrregularFlutter {
        private final long initialState;
        private long state;

        IrregularFlutter(long seed) {
            initialState = seed == 0 ? 0x6a09e667f3bcc909L : seed;
            reset();
        }

        float nextSpeedError() {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            float uniform = ((state >>> 40) & 0xffffffL) * (2f / 0xffffffL) - 1f;
            return uniform * 1.7320508f * RANDOM_SPEED_RMS;
        }

        void reset() {
            state = initialState;
        }
    }

    private static final class TapeStage {
        private final float bias;
        private final float baseLowPass;
        private float memory;

        TapeStage(int sampleRate, float bias) {
            this.bias = bias;
            baseLowPass = 1f - (float) Math.exp(-TWO_PI * Math.min(17_500f,
                    sampleRate * 0.39f) / sampleRate);
        }

        float process(float input, float envelope, float metalMix) {
            float drive = lerp(1.16f, 1.075f, metalMix);
            float biased = input * drive + bias;
            float squared = biased * biased;
            float curved = biased * (1f + 0.055f * squared)
                    / (1f + 0.215f * squared) - bias;
            float loss = Math.min(0.30f, envelope * lerp(0.20f, 0.11f, metalMix));
            float coefficient = baseLowPass * (1f - loss);
            memory += (curved / drive - memory) * coefficient;
            return memory;
        }

        void reset() {
            memory = 0f;
        }
    }

    private static final class HissGenerator {
        private final long initialState;
        private final float scale;
        private final float highPassPole;
        private final float lowPassCoefficient;
        private long state;
        private float previousWhite;
        private float highPassed;
        private float lowPassed;

        HissGenerator(int sampleRate, long seed, float targetRms) {
            initialState = seed == 0 ? 0xbb67ae8584caa73bL : seed;
            highPassPole = (float) Math.exp(-TWO_PI * 1_450f / sampleRate);
            lowPassCoefficient = 1f - (float) Math.exp(-TWO_PI
                    * Math.min(13_800f, sampleRate * 0.40f) / sampleRate);
            // Calibrated for the two one-pole sections above at 48 kHz. The endpoint bandwidth
            // filters subsequently keep both tape types within the service response envelope.
            scale = targetRms * 2.48f;
            reset();
        }

        float next() {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            float white = ((state >>> 40) & 0xffffffL) * (2f / 0xffffffL) - 1f;
            highPassed = white - previousWhite + highPassPole * highPassed;
            previousWhite = white;
            lowPassed += (highPassed - lowPassed) * lowPassCoefficient;
            return lowPassed * scale;
        }

        void reset() {
            state = initialState;
            previousWhite = 0f;
            highPassed = 0f;
            lowPassed = 0f;
        }
    }

    private static final class MotorBed {
        private static final float FUNDAMENTAL_GAIN = dbToLinear(-76f);
        private static final float HARMONIC_GAIN = dbToLinear(-81f);
        private static final float DRIFT_GAIN = dbToLinear(-84f);
        private final float fundamentalStepSine;
        private final float fundamentalStepCosine;
        private final float harmonicStepSine;
        private final float harmonicStepCosine;
        private final long initialState;
        private long state;
        private float fundamentalSine;
        private float fundamentalCosine;
        private float harmonicSine;
        private float harmonicCosine;
        private int frames;

        MotorBed(int sampleRate, long seed) {
            double fundamentalStep = TWO_PI * 49.8 / sampleRate;
            double harmonicStep = TWO_PI * 99.6 / sampleRate;
            fundamentalStepSine = (float) Math.sin(fundamentalStep);
            fundamentalStepCosine = (float) Math.cos(fundamentalStep);
            harmonicStepSine = (float) Math.sin(harmonicStep);
            harmonicStepCosine = (float) Math.cos(harmonicStep);
            initialState = seed == 0 ? 0x3c6ef372fe94f82bL : seed;
            reset();
        }

        float next() {
            float nextFundamentalSine = fundamentalSine * fundamentalStepCosine
                    + fundamentalCosine * fundamentalStepSine;
            float nextFundamentalCosine = fundamentalCosine * fundamentalStepCosine
                    - fundamentalSine * fundamentalStepSine;
            fundamentalSine = nextFundamentalSine;
            fundamentalCosine = nextFundamentalCosine;
            float nextHarmonicSine = harmonicSine * harmonicStepCosine
                    + harmonicCosine * harmonicStepSine;
            float nextHarmonicCosine = harmonicCosine * harmonicStepCosine
                    - harmonicSine * harmonicStepSine;
            harmonicSine = nextHarmonicSine;
            harmonicCosine = nextHarmonicCosine;
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            float drift = (((state >>> 44) & 0xfffffL) / (float) 0xfffffL - 0.5f)
                    * DRIFT_GAIN;
            if (++frames >= 16_384) {
                frames = 0;
                float fundamentalNorm = (float) (1.0 / Math.sqrt(fundamentalSine
                        * fundamentalSine + fundamentalCosine * fundamentalCosine));
                fundamentalSine *= fundamentalNorm;
                fundamentalCosine *= fundamentalNorm;
                float harmonicNorm = (float) (1.0 / Math.sqrt(harmonicSine * harmonicSine
                        + harmonicCosine * harmonicCosine));
                harmonicSine *= harmonicNorm;
                harmonicCosine *= harmonicNorm;
            }
            return fundamentalSine * FUNDAMENTAL_GAIN
                    + harmonicSine * HARMONIC_GAIN + drift;
        }

        void reset() {
            state = initialState;
            fundamentalSine = (float) Math.sin(1.7);
            fundamentalCosine = (float) Math.cos(1.7);
            harmonicSine = (float) Math.sin(4.1);
            harmonicCosine = (float) Math.cos(4.1);
            frames = 0;
        }
    }

    /**
     * This machine's replay equaliser, as its departure from a perfect IEC deck.
     *
     * <p>The record pre-emphasis and its exact inverse either side of the tape stage already carry
     * the standard characteristic, so what is left for the renderer to add is only the difference
     * between the curve Aiwa's components actually realise and the curve the standard asks for:
     * {@code closedLoop(s) / idealReplay(s)}, normalised to unity at 1 kHz. Everything here is
     * solved from {@link AiwaHsJx707ReplayEq}, so it moves if the transcription moves.</p>
     *
     * <p>{@code 1 + Zf/Zg} has two poles and two zeros, and the ideal replay amplifier is the
     * first-order shelf {@code (1 + s*120us)/(1 + s*3180us)}. Dividing one by the other leaves
     * three first-order sections. They are paired nearest-neighbour rather than in any natural
     * order, purely so no single section carries a large gain: the sections come out at ratios of
     * about 62, 0.92 and 1.27 instead of one section swinging by 36 dB.</p>
     *
     * <p>The audible result is small and entirely derived: about -1.3 dB at 63 Hz rising to
     * +1.2 dB by 16 kHz on Normal, -1.0 dB to +1.9 dB on Cr/Metal, and a bass turnover below
     * 63 Hz that reaches -4.5 dB at 20 Hz because of C19 and R17.</p>
     */
    private static final class ReplayErrorEq {
        private final StereoFirstOrderShelf bassTurnover;
        private final StereoFirstOrderShelf bassTrim;
        private final StereoFirstOrderShelf trebleTilt;

        ReplayErrorEq(int sampleRate, AiwaHsJx707ReplayEq source, boolean metal) {
            double[] poles = source.poleTimeConstantsSeconds(metal);
            double[] zeros = source.zeroTimeConstantsSeconds(metal);
            double idealTreble = metal ? AiwaHsJx707ReplayEq.IEC_METAL_SECONDS
                    : AiwaHsJx707ReplayEq.IEC_NORMAL_SECONDS;
            bassTurnover = new StereoFirstOrderShelf(sampleRate, zeros[0], poles[1]);
            bassTrim = new StereoFirstOrderShelf(sampleRate,
                    AiwaHsJx707ReplayEq.IEC_BASS_SECONDS, poles[0]);
            trebleTilt = new StereoFirstOrderShelf(sampleRate, zeros[1], idealTreble);
        }

        float processLeft(float input) {
            return trebleTilt.processLeft(
                    bassTrim.processLeft(bassTurnover.processLeft(input)));
        }

        float processRight(float input) {
            return trebleTilt.processRight(
                    bassTrim.processRight(bassTurnover.processRight(input)));
        }

        void reset() {
            bassTurnover.reset();
            bassTrim.reset();
            trebleTilt.reset();
        }
    }

    /**
     * IC4's BBE, realtime.
     *
     * <p>Two networks that do not interact. The <b>magnitude</b> network is the through path plus a
     * first-order low boost and a first-order high boost in parallel, which gives the family's
     * characteristic V with its floor near 0 dB rather than below it. The <b>phase</b> network is
     * two first-order all-pass sections at the two split frequencies, which is what puts the
     * treble a full turn ahead of the bass — the part of BBE that is not equalisation.</p>
     *
     * <p>The high boost is level-dependent, as BBE II is: a detector opens it from about -48 dBFS
     * and has it fully open by -28 dBFS. Both channels detect separately, which is what BD3860K
     * does with its own DET1 and DET2 pins. The gain is recomputed on a stride and glided rather
     * than applied per sample, because the detector's own 20 ms attack means nothing it produces
     * needs sample-rate resolution.</p>
     */
    private static final class BbeProcessor {
        private static final int CONTROL_STRIDE = 32;

        private final float lowB0;
        private final float lowA1;
        private final float highB0;
        private final float highA1;
        private final float allPassLow;
        private final float allPassHigh;
        private final float loContourGain;
        private final float processGain;
        private final float attack;
        private final float release;
        private final float thresholdLinear;
        private final float inverseSpanLog;
        private final float gainGlide;

        private float lowLeftX1;
        private float lowLeftY1;
        private float lowRightX1;
        private float lowRightY1;
        private float highLeftX1;
        private float highLeftY1;
        private float highRightX1;
        private float highRightY1;
        private float apLowLeft;
        private float apHighLeft;
        private float apLowRight;
        private float apHighRight;
        private float envelopeLeft;
        private float envelopeRight;
        private float openLeft;
        private float openRight;
        private float targetLeft;
        private float targetRight;
        private int strideLeft;
        private int strideRight;

        BbeProcessor(int sampleRate, AiwaHsJx707Bbe reference) {
            // Prewarped bilinear, so the realtime corners land on the analogue prototype's rather
            // than a few per cent below it. At 4.6 kHz against 48 kHz that difference is audible
            // in a sweep even though it is small.
            double lowK = Math.tan(Math.PI * Math.min(reference.lowSplitHertz(),
                    sampleRate * 0.45) / sampleRate);
            lowB0 = (float) (lowK / (1.0 + lowK));
            lowA1 = (float) ((lowK - 1.0) / (1.0 + lowK));

            double highK = Math.tan(Math.PI * Math.min(reference.processCornerHertz(),
                    sampleRate * 0.45) / sampleRate);
            highB0 = (float) (1.0 / (1.0 + highK));
            highA1 = (float) ((highK - 1.0) / (1.0 + highK));

            allPassLow = allPassCoefficient(sampleRate, reference.lowSplitHertz());
            allPassHigh = allPassCoefficient(sampleRate, reference.highSplitHertz());

            loContourGain = (float) reference.loContourPathGain();
            processGain = (float) reference.processPathGain();

            attack = timeCoefficient(sampleRate, (float) AiwaHsJx707Bbe.DETECTOR_ATTACK_SECONDS);
            release = timeCoefficient(sampleRate, (float) AiwaHsJx707Bbe.DETECTOR_RELEASE_SECONDS);
            thresholdLinear = (float) Math.pow(10.0, AiwaHsJx707Bbe.thresholdDbFs() / 20.0);
            double span = (AiwaHsJx707Bbe.fullProcessDbFs() - AiwaHsJx707Bbe.thresholdDbFs()) / 20.0;
            inverseSpanLog = (float) (1.0 / (span * Math.log(10.0)));
            gainGlide = timeCoefficient(sampleRate, 0.005f);
        }

        private static float allPassCoefficient(int sampleRate, double hertz) {
            double k = Math.tan(Math.PI * Math.min(hertz, sampleRate * 0.45) / sampleRate);
            return (float) ((1.0 - k) / (1.0 + k));
        }

        float processLeft(float input) {
            if (--strideLeft <= 0) {
                strideLeft = CONTROL_STRIDE;
                targetLeft = openingFor(envelopeLeft);
            }
            float magnitude = Math.abs(input);
            envelopeLeft += (magnitude - envelopeLeft)
                    * (magnitude > envelopeLeft ? attack : release);
            openLeft += (targetLeft - openLeft) * gainGlide;

            float low = lowB0 * (input + lowLeftX1) - lowA1 * lowLeftY1;
            lowLeftX1 = input;
            lowLeftY1 = low;
            float high = highB0 * (input - highLeftX1) - highA1 * highLeftY1;
            highLeftX1 = input;
            highLeftY1 = high;

            float summed = input + loContourGain * low + processGain * openLeft * high;

            float stage = allPassLow * summed + apLowLeft;
            apLowLeft = summed - allPassLow * stage;
            float output = allPassHigh * stage + apHighLeft;
            apHighLeft = stage - allPassHigh * output;
            return output;
        }

        float processRight(float input) {
            if (--strideRight <= 0) {
                strideRight = CONTROL_STRIDE;
                targetRight = openingFor(envelopeRight);
            }
            float magnitude = Math.abs(input);
            envelopeRight += (magnitude - envelopeRight)
                    * (magnitude > envelopeRight ? attack : release);
            openRight += (targetRight - openRight) * gainGlide;

            float low = lowB0 * (input + lowRightX1) - lowA1 * lowRightY1;
            lowRightX1 = input;
            lowRightY1 = low;
            float high = highB0 * (input - highRightX1) - highA1 * highRightY1;
            highRightX1 = input;
            highRightY1 = high;

            float summed = input + loContourGain * low + processGain * openRight * high;

            float stage = allPassLow * summed + apLowRight;
            apLowRight = summed - allPassLow * stage;
            float output = allPassHigh * stage + apHighRight;
            apHighRight = stage - allPassHigh * output;
            return output;
        }

        /** BD3860K's Fig 16 read as a straight line in decibels between its two stated levels. */
        private float openingFor(float envelope) {
            if (envelope <= thresholdLinear) {
                return 0f;
            }
            float opening = (float) Math.log(envelope / thresholdLinear) * inverseSpanLog;
            return opening >= 1f ? 1f : opening;
        }

        void reset() {
            lowLeftX1 = 0f;
            lowLeftY1 = 0f;
            lowRightX1 = 0f;
            lowRightY1 = 0f;
            highLeftX1 = 0f;
            highLeftY1 = 0f;
            highRightX1 = 0f;
            highRightY1 = 0f;
            apLowLeft = 0f;
            apHighLeft = 0f;
            apLowRight = 0f;
            apHighRight = 0f;
            envelopeLeft = 0f;
            envelopeRight = 0f;
            openLeft = 0f;
            openRight = 0f;
            targetLeft = 0f;
            targetRight = 0f;
            strideLeft = 0;
            strideRight = 0;
        }
    }

    /** {@code s*tau / (1 + s*tau)} by the bilinear transform: one series coupling capacitor. */
    private static final class StereoOnePoleHighPass {
        private final float b0;
        private final float a1;
        private float leftX1;
        private float leftY1;
        private float rightX1;
        private float rightY1;

        StereoOnePoleHighPass(int sampleRate, double tauSeconds) {
            double term = 2.0 * sampleRate * tauSeconds;
            b0 = (float) (term / (1.0 + term));
            a1 = (float) ((1.0 - term) / (1.0 + term));
        }

        float processLeft(float input) {
            float output = b0 * (input - leftX1) - a1 * leftY1;
            leftX1 = input;
            leftY1 = output;
            return output;
        }

        float processRight(float input) {
            float output = b0 * (input - rightX1) - a1 * rightY1;
            rightX1 = input;
            rightY1 = output;
            return output;
        }

        void reset() {
            leftX1 = 0f;
            leftY1 = 0f;
            rightX1 = 0f;
            rightY1 = 0f;
        }
    }

    /**
     * {@code (1 + s*zero) / (1 + s*pole)} by the bilinear transform, unity at 1 kHz.
     *
     * <p>Normalising every section at 1 kHz rather than only the finished cascade keeps the
     * intermediate gains near one, which matters because the longest and shortest time constants
     * in this equaliser are four orders of magnitude apart.</p>
     */
    private static final class StereoFirstOrderShelf {
        private static final double NORMALISE_AT_HERTZ = 1_000.0;
        private final float b0;
        private final float b1;
        private final float a1;
        private float leftX1;
        private float leftY1;
        private float rightX1;
        private float rightY1;

        StereoFirstOrderShelf(int sampleRate, double zeroSeconds, double poleSeconds) {
            double rate = 2.0 * sampleRate;
            double zeroTerm = rate * zeroSeconds;
            double poleTerm = rate * poleSeconds;
            double denominator = 1.0 + poleTerm;
            double rawB0 = (1.0 + zeroTerm) / denominator;
            double rawB1 = (1.0 - zeroTerm) / denominator;
            double rawA1 = (1.0 - poleTerm) / denominator;

            double omega = TWO_PI * Math.min(NORMALISE_AT_HERTZ, sampleRate * 0.45) / sampleRate;
            double cos = Math.cos(omega);
            double sin = Math.sin(omega);
            double gain = Math.hypot(rawB0 + rawB1 * cos, -rawB1 * sin)
                    / Math.hypot(1.0 + rawA1 * cos, -rawA1 * sin);

            b0 = (float) (rawB0 / gain);
            b1 = (float) (rawB1 / gain);
            a1 = (float) rawA1;
        }

        float processLeft(float input) {
            float output = b0 * input + b1 * leftX1 - a1 * leftY1;
            leftX1 = input;
            leftY1 = output;
            return output;
        }

        float processRight(float input) {
            float output = b0 * input + b1 * rightX1 - a1 * rightY1;
            rightX1 = input;
            rightY1 = output;
            return output;
        }

        void reset() {
            leftX1 = 0f;
            leftY1 = 0f;
            rightX1 = 0f;
            rightY1 = 0f;
        }
    }

    private static final class StereoShelf {
        private final float coefficient;
        private final float highGain;
        private float lowLeft;
        private float lowRight;

        StereoShelf(int sampleRate, float corner, float highGain) {
            coefficient = 1f - (float) Math.exp(-TWO_PI * corner / sampleRate);
            this.highGain = highGain;
        }

        float processLeft(float input) {
            lowLeft += (input - lowLeft) * coefficient;
            return lowLeft + (input - lowLeft) * highGain;
        }

        float processRight(float input) {
            lowRight += (input - lowRight) * coefficient;
            return lowRight + (input - lowRight) * highGain;
        }

        void reset() {
            lowLeft = 0f;
            lowRight = 0f;
        }
    }

    private static final class StereoPeaking {
        private final StereoBiquad delegate;

        StereoPeaking(int sampleRate, float frequency, float q, float gainDb) {
            double a = Math.pow(10.0, gainDb / 40.0);
            double omega = TWO_PI * frequency / sampleRate;
            double alpha = Math.sin(omega) / (2.0 * q);
            double cos = Math.cos(omega);
            double b0 = 1.0 + alpha * a;
            double b1 = -2.0 * cos;
            double b2 = 1.0 - alpha * a;
            double a0 = 1.0 + alpha / a;
            double a1 = -2.0 * cos;
            double a2 = 1.0 - alpha / a;
            delegate = new StereoBiquad(b0 / a0, b1 / a0, b2 / a0,
                    a1 / a0, a2 / a0);
        }

        float processLeft(float input) {
            return delegate.processLeft(input);
        }

        float processRight(float input) {
            return delegate.processRight(input);
        }

        void reset() {
            delegate.reset();
        }
    }

    private static final class StereoBiquad {
        private final float b0;
        private final float b1;
        private final float b2;
        private final float a1;
        private final float a2;
        private float leftZ1;
        private float leftZ2;
        private float rightZ1;
        private float rightZ2;

        StereoBiquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = (float) b0;
            this.b1 = (float) b1;
            this.b2 = (float) b2;
            this.a1 = (float) a1;
            this.a2 = (float) a2;
        }

        static StereoBiquad lowPass(int sampleRate, float frequency, float q) {
            double omega = TWO_PI * frequency / sampleRate;
            double cos = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            double b0 = (1.0 - cos) * 0.5;
            double b1 = 1.0 - cos;
            double b2 = b0;
            double a0 = 1.0 + alpha;
            return new StereoBiquad(b0 / a0, b1 / a0, b2 / a0,
                    -2.0 * cos / a0, (1.0 - alpha) / a0);
        }

        float processLeft(float input) {
            float output = b0 * input + leftZ1;
            leftZ1 = b1 * input - a1 * output + leftZ2;
            leftZ2 = b2 * input - a2 * output;
            return output;
        }

        float processRight(float input) {
            float output = b0 * input + rightZ1;
            rightZ1 = b1 * input - a1 * output + rightZ2;
            rightZ2 = b2 * input - a2 * output;
            return output;
        }

        void reset() {
            leftZ1 = 0f;
            leftZ2 = 0f;
            rightZ1 = 0f;
            rightZ2 = 0f;
        }
    }
}
