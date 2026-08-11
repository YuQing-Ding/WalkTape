package com.yqdscott.walktape;

import java.util.Arrays;

/**
 * Realtime reference model of a serviced Aiwa HS-JX707 with BBE, DSL and Dolby disabled.
 *
 * <p>No trustworthy per-frequency sweep of a correctly aligned JX707 is publicly available.
 * This renderer therefore models the audio path against Aiwa's 1992 service limits: the manual
 * tape selector changes the 120 us Normal and 70 us Cr/Metal paths, the usable bands end at
 * 8 kHz and 12.5 kHz respectively, steady transport remains below the published 0.45% RMS
 * service ceiling, and unassisted playback exceeds the specified 45 dB signal-to-noise ratio.
 * The 0.320% RMS transport target is a conservative serviced-unit value inside that envelope,
 * not a claim that every surviving machine measures identically.</p>
 *
 * <p>Production playback supplies magnetic non-linearity and tape hiss through the independent
 * {@link TapeMediumDsp}; the package-private machine-only constructor prevents those media
 * effects from being counted twice while retaining the JX707 mechanics and electronics.</p>
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
    private final StereoBiquad headHighPass;
    private final StereoPeaking headContour;
    private final StereoBiquad normalBandwidth;
    private final StereoBiquad metalBandwidth;
    private final TapeStage tapeLeft;
    private final TapeStage tapeRight;
    private final HissGenerator hissLeft;
    private final HissGenerator hissRight;
    private final MotorBed motorBed;
    private final IrregularFlutter irregularFlutter;
    private final float envelopeAttack;
    private final float envelopeRelease;

    private volatile boolean highTape;
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
        int requiredDelay = (int) Math.ceil(baseDelaySamples + maximumExcursion + 36f);
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

        // Aiwa specifies a usable band, not a measured centre-line curve. Butterworth corners at
        // the published endpoints keep the model centred safely inside the +/-4.5 dB envelope.
        headHighPass = StereoBiquad.highPass(sampleRate, Math.min(63f, safeTop), 0.7071f);
        headContour = new StereoPeaking(sampleRate, Math.min(108f, safeTop), 0.82f, 0.85f);
        normalBandwidth = StereoBiquad.lowPass(sampleRate, Math.min(8_000f, safeTop), 0.7071f);
        metalBandwidth = StereoBiquad.lowPass(sampleRate, Math.min(12_500f, safeTop), 0.7071f);

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
        headHighPass.reset();
        headContour.reset();
        normalBandwidth.reset();
        metalBandwidth.reset();
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

            left = headHighPass.processLeft(left);
            right = headHighPass.processRight(right);
            left = headContour.processLeft(left);
            right = headContour.processRight(right);

            if (hissEnabled) {
                left += hissLeft.next();
                right += hissRight.next();
                float motor = motorBed.next();
                left += motor;
                right += motor * 0.87f;
            } else if (machineNoiseEnabled) {
                float motor = motorBed.next();
                left += motor;
                right += motor * 0.87f;
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

            float crossedLeft = left + PROGRAM_CROSSTALK * right;
            float crossedRight = right + PROGRAM_CROSSTALK * left;
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
            metalBandwidth.reset();
        } else {
            normalRecordEq.reset();
            normalPlaybackEq.reset();
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

    private static float outputStage(float input) {
        float magnitude = Math.abs(input);
        if (magnitude <= 0.86f) {
            return input;
        }
        float excess = magnitude - 0.86f;
        float rounded = 0.86f + excess / (1f + excess * 2.8f);
        float limited = Math.min(1.04f, rounded);
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

        static StereoBiquad highPass(int sampleRate, float frequency, float q) {
            double omega = TWO_PI * frequency / sampleRate;
            double cos = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            double b0 = (1.0 + cos) * 0.5;
            double b1 = -(1.0 + cos);
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
