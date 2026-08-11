package com.yqdscott.walktape;

import java.util.Arrays;

/**
 * Allocation-free machine-only model of a factory-aligned Sony WM-D6C.
 *
 * <p>The 1984 Sony service manual specifies 40 Hz–15 kHz (+/-3 dB), 0.04 percent
 * WRMS wow/flutter (NAB), +/-0.14 percent DIN and a quartz-locked disc-drive transport.
 * It also documents the manual Normal/CrO2/Metal replay selector and a 3 kHz speed
 * adjustment. Magnetic saturation, coating noise and modulation noise are deliberately
 * left to {@link TapeMediumDsp}; this class models only transport, head/replay bandwidth,
 * selector mismatch, channel leakage and the exceptionally quiet machine electronics.
 * {@link DolbyNoiseReductionDsp} supplies the manual's OFF/B/C record-and-replay path around
 * the selected magnetic stock, before this machine-only playback stage.
 *
 * <p>The factory manual publishes a tolerance envelope rather than a centre-line sweep.
 * Consequently the reference path is intentionally neutral between its Butterworth -3 dB
 * endpoints. It does not invent a fashionable "vintage" EQ for this professional recorder.</p>
 */
public final class SonyWmD6cDsp implements TapeMachineDsp {
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int TRANSPORT_CONTROL_STRIDE = 4;
    private static final float RANDOM_SPEED_RMS = 0.00010f;
    private static final float PROGRAM_CROSSTALK = 0.0040f;
    private static final float MACHINE_NOISE_RMS = dbToLinear(-78f);
    private static final float REPLAY_EQ_MISMATCH_DB = 4.68f; // 20 log10(120 us / 70 us)

    // Quartz servo residual, flywheel/capstan, pinch system and upper flutter component.
    // Their quadrature sum with RANDOM_SPEED_RMS is exactly the published 0.040% WRMS target.
    private static final double[] TRANSPORT_HZ = {0.58, 4.80, 9.60, 13.70};
    private static final double[] SPEED_PEAK = {0.00027514, 0.00035, 0.00027, 0.00017};
    private static final double[] INITIAL_PHASE = {1.43, 4.61, 2.17, 5.38};

    private final int sampleRate;
    private final boolean transportEnabled;
    private final boolean machineNoiseEnabled;
    private final boolean tapeStockHigh;
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
    private final StereoBiquad headHighPass;
    private final StereoBiquad headBump;
    private final StereoBiquad replayBandwidth;
    private final StereoShelf brightMismatch;
    private final StereoShelf darkMismatch;
    private final IrregularFlutter irregularFlutter;
    private final ShapedNoise noiseLeft;
    private final ShapedNoise noiseRight;
    private final MotorBed motorBed;

    private volatile boolean highTape;
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
    private float selectorMismatchMix;

    public SonyWmD6cDsp(int sampleRate) {
        this(sampleRate, 0x574d443643313938L, true, true, true);
    }

    /** Package-private deterministic constructor for calibration and performance tests. */
    SonyWmD6cDsp(int sampleRate,
                 long seed,
                 boolean transportEnabled,
                 boolean machineNoiseEnabled,
                 boolean tapeStockHigh) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        this.transportEnabled = transportEnabled;
        this.machineNoiseEnabled = machineNoiseEnabled;
        this.tapeStockHigh = tapeStockHigh;
        highTape = tapeStockHigh;

        double maximumExcursion = 0.0;
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            double step = TWO_PI * TRANSPORT_HZ[component]
                    * TRANSPORT_CONTROL_STRIDE / sampleRate;
            transportStepSine[component] = (float) Math.sin(step);
            transportStepCosine[component] = (float) Math.cos(step);
            delayAmplitude[component] = (float) (SPEED_PEAK[component] * sampleRate
                    / (TWO_PI * TRANSPORT_HZ[component]));
            maximumExcursion += Math.abs(delayAmplitude[component]);
        }
        baseDelaySamples = (float) maximumExcursion + 10f;
        int requiredDelay = (int) Math.ceil(baseDelaySamples + maximumExcursion + 24f);
        int delaySize = 1;
        while (delaySize < requiredDelay) {
            delaySize <<= 1;
        }
        delayLeft = new float[delaySize];
        delayRight = new float[delaySize];
        delayMask = delaySize - 1;

        double azimuthStepRadians = TWO_PI * 0.09 * TRANSPORT_CONTROL_STRIDE / sampleRate;
        azimuthStepSine = (float) Math.sin(azimuthStepRadians);
        azimuthStepCosine = (float) Math.cos(azimuthStepRadians);

        float safeTop = sampleRate * 0.43f;
        headHighPass = StereoBiquad.highPass(sampleRate, Math.min(40f, safeTop), 0.7071f);
        // A restrained 0.22 dB head resonance keeps the centre line essentially flat while
        // retaining the small low-frequency contour of a real amorphous record/playback head.
        headBump = StereoBiquad.peaking(sampleRate, Math.min(92f, safeTop), 0.82f, 0.22f);
        replayBandwidth = StereoBiquad.lowPass(sampleRate,
                Math.min(15_000f, safeTop), 0.7071f);
        float selectorCorner = Math.min(1_736f, safeTop);
        brightMismatch = new StereoShelf(sampleRate, selectorCorner,
                dbToLinear(REPLAY_EQ_MISMATCH_DB));
        darkMismatch = new StereoShelf(sampleRate, selectorCorner,
                dbToLinear(-REPLAY_EQ_MISMATCH_DB));

        irregularFlutter = new IrregularFlutter(seed ^ 0x51554152545a4c4bL);
        noiseLeft = new ShapedNoise(sampleRate, seed ^ 0x4436434c45465420L,
                MACHINE_NOISE_RMS);
        noiseRight = new ShapedNoise(sampleRate, seed ^ 0x4436435247485420L,
                MACHINE_NOISE_RMS);
        motorBed = new MotorBed(sampleRate);
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
        interpolatedDelay = baseDelaySamples;
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            transportSine[component] = (float) Math.sin(INITIAL_PHASE[component]);
            transportCosine[component] = (float) Math.cos(INITIAL_PHASE[component]);
            interpolatedDelay += delayAmplitude[component] * transportSine[component];
        }
        writeIndex = 0;
        transportFramesUntilUpdate = 0;
        oscillatorFrames = 0;
        delayStep = 0f;
        randomDelay = 0f;
        azimuthSine = (float) Math.sin(2.71);
        azimuthCosine = (float) Math.cos(2.71);
        interpolatedAzimuth = 0.040f + 0.014f * azimuthSine;
        azimuthStep = 0f;
        selectorMismatchMix = highTape == tapeStockHigh ? 0f : highTape ? 1f : -1f;
        headHighPass.reset();
        headBump.reset();
        replayBandwidth.reset();
        brightMismatch.reset();
        darkMismatch.reset();
        irregularFlutter.reset();
        noiseLeft.reset();
        noiseRight.reset();
        motorBed.reset();
    }

    @Override
    public void process(float[] stereo, int frameCount) {
        if (frameCount < 0 || frameCount * 2 > stereo.length) {
            throw new IllegalArgumentException("Invalid stereo frame count");
        }
        float mismatchMix = selectorMismatchMix;
        final float targetMismatch = highTape == tapeStockHigh ? 0f : highTape ? 1f : -1f;
        final float mismatchStep = 1f / Math.max(1f, sampleRate * 0.035f);

        for (int frame = 0; frame < frameCount; frame++) {
            int sample = frame * 2;
            float left = stereo[sample];
            float right = stereo[sample + 1];

            if (transportEnabled) {
                delayLeft[writeIndex] = left;
                delayRight[writeIndex] = right;
                if (transportFramesUntilUpdate <= 0) {
                    updateTransport();
                    transportFramesUntilUpdate = TRANSPORT_CONTROL_STRIDE;
                }
                interpolatedDelay += delayStep;
                interpolatedAzimuth += azimuthStep;
                left = cubicRead(delayLeft, interpolatedDelay);
                right = cubicRead(delayRight, interpolatedDelay + interpolatedAzimuth);
                writeIndex = (writeIndex + 1) & delayMask;
                transportFramesUntilUpdate--;
            }

            left = headHighPass.processLeft(left);
            right = headHighPass.processRight(right);
            left = headBump.processLeft(left);
            right = headBump.processRight(right);

            float brightLeft = brightMismatch.processLeft(left);
            float brightRight = brightMismatch.processRight(right);
            float darkLeft = darkMismatch.processLeft(left);
            float darkRight = darkMismatch.processRight(right);
            if (mismatchMix < targetMismatch) {
                mismatchMix = Math.min(targetMismatch, mismatchMix + mismatchStep);
            } else if (mismatchMix > targetMismatch) {
                mismatchMix = Math.max(targetMismatch, mismatchMix - mismatchStep);
            }
            if (mismatchMix > 0f) {
                left += (brightLeft - left) * mismatchMix;
                right += (brightRight - right) * mismatchMix;
            } else if (mismatchMix < 0f) {
                left += (darkLeft - left) * -mismatchMix;
                right += (darkRight - right) * -mismatchMix;
            }

            left = replayBandwidth.processLeft(left);
            right = replayBandwidth.processRight(right);
            if (machineNoiseEnabled) {
                float motor = motorBed.next();
                left += noiseLeft.next() + motor;
                right += noiseRight.next() + motor * 0.90f;
            }

            float originalLeft = left;
            float originalRight = right;
            left = originalLeft * (1f - PROGRAM_CROSSTALK)
                    + originalRight * PROGRAM_CROSSTALK;
            right = originalRight * (1f - PROGRAM_CROSSTALK)
                    + originalLeft * PROGRAM_CROSSTALK;
            stereo[sample] = outputStage(left);
            stereo[sample + 1] = outputStage(right);
        }
        selectorMismatchMix = mismatchMix;
    }

    public static float nominalWowFlutterRmsPercent() {
        double sum = RANDOM_SPEED_RMS * RANDOM_SPEED_RMS;
        for (double peak : SPEED_PEAK) {
            sum += peak * peak * 0.5;
        }
        return (float) (Math.sqrt(sum) * 100.0);
    }

    public static float dinWowFlutterPercent() {
        return 0.14f;
    }

    public static float speedCalibrationTolerancePercent() {
        return 0.30f;
    }

    static float machineNoiseFloorDb() {
        return -78f;
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
        randomDelay = randomDelay * 0.9988f
                + irregularFlutter.nextSpeedError() * TRANSPORT_CONTROL_STRIDE;
        targetDelay += randomDelay;
        delayStep = (targetDelay - interpolatedDelay) / TRANSPORT_CONTROL_STRIDE;

        float nextAzimuthSine = azimuthSine * azimuthStepCosine
                + azimuthCosine * azimuthStepSine;
        float nextAzimuthCosine = azimuthCosine * azimuthStepCosine
                - azimuthSine * azimuthStepSine;
        azimuthSine = nextAzimuthSine;
        azimuthCosine = nextAzimuthCosine;
        float targetAzimuth = 0.040f + 0.014f * nextAzimuthSine;
        azimuthStep = (targetAzimuth - interpolatedAzimuth) / TRANSPORT_CONTROL_STRIDE;

        oscillatorFrames += TRANSPORT_CONTROL_STRIDE;
        if (oscillatorFrames >= 16_384) {
            oscillatorFrames = 0;
            for (int component = 0; component < TRANSPORT_HZ.length; component++) {
                float norm = inverseMagnitude(transportSine[component],
                        transportCosine[component]);
                transportSine[component] *= norm;
                transportCosine[component] *= norm;
            }
            float azimuthNorm = inverseMagnitude(azimuthSine, azimuthCosine);
            azimuthSine *= azimuthNorm;
            azimuthCosine *= azimuthNorm;
        }
    }

    private float cubicRead(float[] ring, float delaySamples) {
        float position = writeIndex - delaySamples;
        int index = (int) Math.floor(position);
        float fraction = position - index;
        float y0 = ring[(index - 1) & delayMask];
        float y1 = ring[index & delayMask];
        float y2 = ring[(index + 1) & delayMask];
        float y3 = ring[(index + 2) & delayMask];
        float a = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3;
        float b = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3;
        float c = -0.5f * y0 + 0.5f * y2;
        return ((a * fraction + b) * fraction + c) * fraction + y1;
    }

    private static float outputStage(float input) {
        float magnitude = Math.abs(input);
        if (magnitude <= 0.94f) {
            return input;
        }
        float excess = magnitude - 0.94f;
        float rounded = 0.94f + excess / (1f + excess * 1.75f);
        return Math.copySign(Math.min(1.10f, rounded), input);
    }

    private static float inverseMagnitude(float sine, float cosine) {
        return (float) (1.0 / Math.sqrt(sine * sine + cosine * cosine));
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
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

    private static final class ShapedNoise {
        private final long initialState;
        private final float highPassPole;
        private final float lowPassCoefficient;
        private final float targetRms;
        private long state;
        private float previousWhite;
        private float highPassed;
        private float lowPassed;
        private float scale;

        ShapedNoise(int sampleRate, long seed, float targetRms) {
            initialState = seed == 0 ? 0xbb67ae8584caa73bL : seed;
            highPassPole = (float) Math.exp(-TWO_PI * 42f / sampleRate);
            lowPassCoefficient = 1f - (float) Math.exp(-TWO_PI
                    * Math.min(16_500f, sampleRate * 0.40f) / sampleRate);
            this.targetRms = targetRms;
            calibrate();
        }

        float next() {
            return shapedWhite() * scale;
        }

        void reset() {
            state = initialState;
            previousWhite = 0f;
            highPassed = 0f;
            lowPassed = 0f;
        }

        private void calibrate() {
            reset();
            double squares = 0.0;
            for (int sample = 0; sample < 32_768; sample++) {
                float value = shapedWhite();
                squares += value * value;
            }
            scale = targetRms / (float) Math.sqrt(squares / 32_768.0);
            reset();
        }

        private float shapedWhite() {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            float white = ((state >>> 40) & 0xffffffL) * (2f / 0xffffffL) - 1f;
            highPassed = white - previousWhite + highPassPole * highPassed;
            previousWhite = white;
            lowPassed += (highPassed - lowPassed) * lowPassCoefficient;
            return lowPassed;
        }
    }

    private static final class MotorBed {
        private static final float FUNDAMENTAL_GAIN = dbToLinear(-91f);
        private static final float HARMONIC_GAIN = dbToLinear(-96f);
        private final float fundamentalStepSine;
        private final float fundamentalStepCosine;
        private final float harmonicStepSine;
        private final float harmonicStepCosine;
        private float fundamentalSine;
        private float fundamentalCosine;
        private float harmonicSine;
        private float harmonicCosine;
        private int frames;

        MotorBed(int sampleRate) {
            double fundamentalStep = TWO_PI * 72.0 / sampleRate;
            double harmonicStep = TWO_PI * 144.0 / sampleRate;
            fundamentalStepSine = (float) Math.sin(fundamentalStep);
            fundamentalStepCosine = (float) Math.cos(fundamentalStep);
            harmonicStepSine = (float) Math.sin(harmonicStep);
            harmonicStepCosine = (float) Math.cos(harmonicStep);
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
            if (++frames >= 16_384) {
                frames = 0;
                float firstNorm = inverseMagnitude(fundamentalSine, fundamentalCosine);
                fundamentalSine *= firstNorm;
                fundamentalCosine *= firstNorm;
                float secondNorm = inverseMagnitude(harmonicSine, harmonicCosine);
                harmonicSine *= secondNorm;
                harmonicCosine *= secondNorm;
            }
            return fundamentalSine * FUNDAMENTAL_GAIN + harmonicSine * HARMONIC_GAIN;
        }

        void reset() {
            fundamentalSine = (float) Math.sin(1.11);
            fundamentalCosine = (float) Math.cos(1.11);
            harmonicSine = (float) Math.sin(4.31);
            harmonicCosine = (float) Math.cos(4.31);
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

        private StereoBiquad(double b0, double b1, double b2,
                             double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0);
            this.b1 = (float) (b1 / a0);
            this.b2 = (float) (b2 / a0);
            this.a1 = (float) (a1 / a0);
            this.a2 = (float) (a2 / a0);
        }

        static StereoBiquad lowPass(int sampleRate, float frequency, float q) {
            double omega = TWO_PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            return new StereoBiquad((1.0 - cosine) * 0.5, 1.0 - cosine,
                    (1.0 - cosine) * 0.5, 1.0 + alpha,
                    -2.0 * cosine, 1.0 - alpha);
        }

        static StereoBiquad highPass(int sampleRate, float frequency, float q) {
            double omega = TWO_PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            return new StereoBiquad((1.0 + cosine) * 0.5, -(1.0 + cosine),
                    (1.0 + cosine) * 0.5, 1.0 + alpha,
                    -2.0 * cosine, 1.0 - alpha);
        }

        static StereoBiquad peaking(int sampleRate, float frequency, float q, float gainDb) {
            double amplitude = Math.pow(10.0, gainDb / 40.0);
            double omega = TWO_PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            return new StereoBiquad(1.0 + alpha * amplitude, -2.0 * cosine,
                    1.0 - alpha * amplitude, 1.0 + alpha / amplitude,
                    -2.0 * cosine, 1.0 - alpha / amplitude);
        }

        float processLeft(float input) {
            float output = input * b0 + leftZ1;
            leftZ1 = input * b1 - output * a1 + leftZ2;
            leftZ2 = input * b2 - output * a2;
            return output;
        }

        float processRight(float input) {
            float output = input * b0 + rightZ1;
            rightZ1 = input * b1 - output * a1 + rightZ2;
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
}
