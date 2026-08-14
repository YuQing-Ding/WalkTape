package com.yqdscott.walktape;

import java.util.Arrays;

/**
 * Realtime machine-only model of a serviced Sony WM-F2015.
 *
 * <p>Sony's revised WM-F2015 manual identifies the machine as an AF23/BF23 derivative. The
 * associated factory manual documents the MF-WMAF23-04 transport, separate midway and capstan
 * belts, manual Normal/CrO2-Metal selector, LA4570M replay/power amplifier, AN6650 motor drive,
 * 6.3 kHz head-phase check, 3 kHz speed calibration (+/-0.5 percent), and a maximum +/-1.5
 * percent beginning-to-end tape-speed difference. The catalogue bandwidth is 40 Hz-15 kHz.
 *
 * <p>The manuals do not publish a wow/flutter measurement. The 0.340 percent RMS transport
 * target below is therefore an explicit healthy-unit model derived from the documented two-belt
 * mechanism, not a falsely attributed Sony specification. Magnetic coating, tape hiss, MOL and
 * SOL remain in {@link TapeMediumDsp}; this class only renders the F2015 transport, head/replay
 * bandwidth, low-voltage output stage, channel leakage and very low machine noise.</p>
 */
public final class SonyWmF2015Dsp implements TapeMachineDsp {
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int TRANSPORT_CONTROL_STRIDE = 4;
    private static final float RANDOM_SPEED_RMS = 0.00055f;
    private static final float PROGRAM_CROSSTALK = 0.014f;
    // Re-referenced with the tape stage's calibration to published bias noise. At the old value
    // this portable's own electronics sat above a correctly quiet Type I stock, which inverted the
    // physical order: on a real machine the tape is what you hear hissing.
    private static final float ELECTRONICS_NOISE_RMS = dbToLinear(-79.0f);

    // Midway belt/flywheel, idler, capstan belt and motor/roller components. Their quadrature
    // sum with the bounded stochastic term is 0.340 percent RMS.
    private static final double[] TRANSPORT_HZ = {0.62, 2.10, 5.40, 10.20};
    private static final double[] SPEED_PEAK = {
            0.0040905379, 0.00165, 0.00150, 0.00090
    };
    private static final double[] INITIAL_PHASE = {1.18, 4.37, 2.54, 5.71};

    private final int sampleRate;
    private final boolean transportEnabled;
    private final boolean machineNoiseEnabled;
    private final float[] transportSine = new float[TRANSPORT_HZ.length];
    private final float[] transportCosine = new float[TRANSPORT_HZ.length];
    private final float[] transportStepSine = new float[TRANSPORT_HZ.length];
    private final float[] transportStepCosine = new float[TRANSPORT_HZ.length];
    private final float[] delayAmplitude = new float[TRANSPORT_HZ.length];
    private final float[] conditionInitialSine =
            new float[MachineImperfectionDsp.TRANSPORT_HZ.length];
    private final float[] conditionInitialCosine =
            new float[MachineImperfectionDsp.TRANSPORT_HZ.length];
    private final float[] conditionSine =
            new float[MachineImperfectionDsp.TRANSPORT_HZ.length];
    private final float[] conditionCosine =
            new float[MachineImperfectionDsp.TRANSPORT_HZ.length];
    private final float[] conditionStepSine =
            new float[MachineImperfectionDsp.TRANSPORT_HZ.length];
    private final float[] conditionStepCosine =
            new float[MachineImperfectionDsp.TRANSPORT_HZ.length];
    private final float[] conditionDelayAmplitude =
            new float[MachineImperfectionDsp.TRANSPORT_HZ.length];
    private final float[] delayLeft;
    private final float[] delayRight;
    private final int delayMask;
    private final float baseDelaySamples;
    private final MachineConditionProfile conditionProfile;
    private final boolean integratedConditionEnabled;
    private final float conditionBaseAzimuthSamples;
    private final float conditionAzimuthDriftSamples;
    private final boolean conditionSoftenRight;
    private final float conditionHighFrequencyBlend;
    private final float conditionHighFrequencyPole;
    private final float conditionLeftDirectMix;
    private final float conditionRightDirectMix;
    private final float conditionLeftToRightMix;
    private final float conditionRightToLeftMix;
    private final float azimuthStepSine;
    private final float azimuthStepCosine;
    private final StereoBiquad headHighPass;
    private final StereoPeaking headContour;
    private final StereoPeaking replayContour;
    private final StereoBiquad normalBandwidth;
    private final StereoBiquad chromeMetalBandwidth;
    private final IrregularFlutter irregularFlutter;
    private final ElectronicsNoise electronicsNoiseLeft;
    private final ElectronicsNoise electronicsNoiseRight;
    private final MotorBed motorBed;

    private volatile boolean highTape;
    private float highTapeMix;
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
    private boolean bandwidthTargetHigh;
    private float conditionLowPassedLeft;
    private float conditionLowPassedRight;

    public SonyWmF2015Dsp(int sampleRate) {
        this(sampleRate, 0x574d4632303135L, true, true);
    }

    /** Package-private deterministic constructor for calibration and performance tests. */
    SonyWmF2015Dsp(int sampleRate,
                   long seed,
                   boolean transportEnabled,
                   boolean machineNoiseEnabled) {
        this(sampleRate, seed, transportEnabled, machineNoiseEnabled,
                MachineConditionProfile.calibrated(), 0L);
    }

    /** Integrates healthy-unit tolerances into the physical transport instead of cascading it. */
    SonyWmF2015Dsp(int sampleRate,
                   long seed,
                   boolean transportEnabled,
                   boolean machineNoiseEnabled,
                   MachineConditionProfile requestedCondition,
                   long unitSeed) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        this.transportEnabled = transportEnabled;
        this.machineNoiseEnabled = machineNoiseEnabled;
        conditionProfile = requestedCondition == null
                ? MachineConditionProfile.calibrated()
                : MachineConditionProfile.forId(requestedCondition.id);
        integratedConditionEnabled = !conditionProfile.isCalibrated();

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
        double maximumConditionExcursion = 0.0;
        for (int component = 0;
             component < MachineImperfectionDsp.TRANSPORT_HZ.length;
             component++) {
            double phase = MachineImperfectionDsp.seededPhase(unitSeed, component);
            conditionInitialSine[component] = (float) Math.sin(phase);
            conditionInitialCosine[component] = (float) Math.cos(phase);
            double frequency = MachineImperfectionDsp.TRANSPORT_HZ[component];
            double phaseStep = TWO_PI * frequency * TRANSPORT_CONTROL_STRIDE / sampleRate;
            conditionStepSine[component] = (float) Math.sin(phaseStep);
            conditionStepCosine[component] = (float) Math.cos(phaseStep);
            conditionDelayAmplitude[component] = (float) (
                    MachineImperfectionDsp.FULL_SPEED_PEAK[component]
                            * conditionProfile.transportScale * sampleRate
                            / (TWO_PI * frequency));
            maximumConditionExcursion += Math.abs(conditionDelayAmplitude[component]);
        }
        baseDelaySamples = (float) (maximumExcursion + maximumConditionExcursion) + 12f;
        int requiredDelay = (int) Math.ceil(baseDelaySamples + maximumExcursion
                + maximumConditionExcursion + 40f);
        int delaySize = 1;
        while (delaySize < requiredDelay) {
            delaySize <<= 1;
        }
        delayLeft = new float[delaySize];
        delayRight = new float[delaySize];
        delayMask = delaySize - 1;

        // The manual's 6.3 kHz phase check constrains this to a small, shared-head error.
        double azimuthPhaseStep = TWO_PI * 0.11 * TRANSPORT_CONTROL_STRIDE / sampleRate;
        azimuthStepSine = (float) Math.sin(azimuthPhaseStep);
        azimuthStepCosine = (float) Math.cos(azimuthPhaseStep);

        conditionBaseAzimuthSamples =
                conditionProfile.azimuthMicroseconds * sampleRate / 1_000_000f;
        conditionAzimuthDriftSamples = MachineImperfectionDsp.FULL_AZIMUTH_DRIFT_SAMPLES
                * conditionProfile.transportScale;
        conditionSoftenRight = (unitSeed & 1L) == 0L;
        conditionHighFrequencyBlend = Math.min(0.08f,
                conditionProfile.highFrequencyMismatchDb * 0.155f);
        conditionHighFrequencyPole = cutoffCoefficient(sampleRate,
                Math.min(7_200f, sampleRate * 0.35f));
        float halfBalance = conditionProfile.channelBalanceDb * 0.5f;
        float conditionLeftGain = dbToLinear(conditionSoftenRight
                ? halfBalance : -halfBalance);
        float conditionRightGain = dbToLinear(conditionSoftenRight
                ? -halfBalance : halfBalance);
        float extraCrosstalk = conditionProfile.extraCrosstalk;
        float conditionNormalise = 1f / (1f + extraCrosstalk);
        conditionLeftDirectMix = conditionLeftGain * conditionNormalise;
        conditionRightDirectMix = conditionRightGain * conditionNormalise;
        conditionLeftToRightMix = conditionLeftGain * extraCrosstalk * conditionNormalise;
        conditionRightToLeftMix = conditionRightGain * extraCrosstalk * conditionNormalise;

        float safeTop = sampleRate * 0.43f;
        headHighPass = StereoBiquad.highPass(sampleRate, Math.min(36f, safeTop), 0.66f);
        headContour = new StereoPeaking(sampleRate, Math.min(92f, safeTop), 0.88f, 1.10f);
        replayContour = new StereoPeaking(sampleRate,
                Math.min(4_650f, safeTop), 0.72f, -0.72f);
        normalBandwidth = StereoBiquad.lowPass(sampleRate,
                Math.min(11_600f, safeTop), 0.68f);
        chromeMetalBandwidth = StereoBiquad.lowPass(sampleRate,
                Math.min(15_000f, safeTop), 0.69f);

        irregularFlutter = new IrregularFlutter(seed ^ 0x5452414e53504f52L);
        electronicsNoiseLeft = new ElectronicsNoise(sampleRate,
                seed ^ 0x4c41343537304d4cL, ELECTRONICS_NOISE_RMS);
        electronicsNoiseRight = new ElectronicsNoise(sampleRate,
                seed ^ 0x4c41343537304d52L, ELECTRONICS_NOISE_RMS);
        motorBed = new MotorBed(sampleRate, seed ^ 0x414e363635304d4fL);
        reset();
    }

    @Override
    public void setHighTape(boolean enabled) {
        highTape = enabled;
    }

    MachineConditionProfile conditionProfile() {
        return conditionProfile;
    }

    @Override
    public void reset() {
        Arrays.fill(delayLeft, 0f);
        Arrays.fill(delayRight, 0f);
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            transportSine[component] = (float) Math.sin(INITIAL_PHASE[component]);
            transportCosine[component] = (float) Math.cos(INITIAL_PHASE[component]);
        }
        System.arraycopy(conditionInitialSine, 0, conditionSine, 0, conditionSine.length);
        System.arraycopy(conditionInitialCosine, 0, conditionCosine, 0, conditionCosine.length);
        writeIndex = 0;
        transportFramesUntilUpdate = 0;
        oscillatorFrames = 0;
        randomDelay = 0f;
        interpolatedDelay = baseDelaySamples;
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            interpolatedDelay += delayAmplitude[component] * transportSine[component];
        }
        for (int component = 0; component < conditionSine.length; component++) {
            interpolatedDelay += conditionDelayAmplitude[component] * conditionSine[component];
        }
        delayStep = 0f;
        azimuthSine = (float) Math.sin(2.29);
        azimuthCosine = (float) Math.cos(2.29);
        interpolatedAzimuth = 0.135f + 0.045f * azimuthSine
                + conditionBaseAzimuthSamples
                + conditionAzimuthDriftSamples * conditionSine[1];
        azimuthStep = 0f;
        highTapeMix = highTape ? 1f : 0f;
        bandwidthTargetHigh = highTape;
        headHighPass.reset();
        headContour.reset();
        replayContour.reset();
        normalBandwidth.reset();
        chromeMetalBandwidth.reset();
        irregularFlutter.reset();
        electronicsNoiseLeft.reset();
        electronicsNoiseRight.reset();
        motorBed.reset();
        conditionLowPassedLeft = 0f;
        conditionLowPassedRight = 0f;
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
        if (targetHigh != bandwidthTargetHigh) {
            // A stable selector position only runs its audible filter. Resetting the newly
            // selected path here gives it a clean state before the existing 25 ms crossfade;
            // both filters then run for the complete travel of the physical selector.
            if (targetHigh) {
                chromeMetalBandwidth.reset();
            } else {
                normalBandwidth.reset();
            }
            bandwidthTargetHigh = targetHigh;
        }

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
                float readPosition = wrapReadPosition(writeIndex - interpolatedDelay);
                left = quadraticRead(delayLeft, readPosition);
                right = quadraticRead(delayRight,
                        wrapReadPosition(readPosition - interpolatedAzimuth));
                writeIndex = (writeIndex + 1) & delayMask;
                transportFramesUntilUpdate--;
            }

            if (integratedConditionEnabled) {
                if (conditionSoftenRight) {
                    conditionLowPassedRight += conditionHighFrequencyPole
                            * (right - conditionLowPassedRight);
                    right += (conditionLowPassedRight - right) * conditionHighFrequencyBlend;
                } else {
                    conditionLowPassedLeft += conditionHighFrequencyPole
                            * (left - conditionLowPassedLeft);
                    left += (conditionLowPassedLeft - left) * conditionHighFrequencyBlend;
                }
                float conditionLeft = left;
                float conditionRight = right;
                left = conditionLeft * conditionLeftDirectMix
                        + conditionRight * conditionRightToLeftMix;
                right = conditionRight * conditionRightDirectMix
                        + conditionLeft * conditionLeftToRightMix;
            }

            left = headHighPass.processLeft(left);
            right = headHighPass.processRight(right);
            left = headContour.processLeft(left);
            right = headContour.processRight(right);
            left = replayContour.processLeft(left);
            right = replayContour.processRight(right);

            if (modeMix == 0f && targetMix == 0f) {
                left = normalBandwidth.processLeft(left);
                right = normalBandwidth.processRight(right);
            } else if (modeMix == 1f && targetMix == 1f) {
                left = chromeMetalBandwidth.processLeft(left);
                right = chromeMetalBandwidth.processRight(right);
            } else {
                float normalLeft = normalBandwidth.processLeft(left);
                float normalRight = normalBandwidth.processRight(right);
                float chromeLeft = chromeMetalBandwidth.processLeft(left);
                float chromeRight = chromeMetalBandwidth.processRight(right);
                if (modeMix < targetMix) {
                    modeMix = Math.min(targetMix, modeMix + mixStep);
                } else if (modeMix > targetMix) {
                    modeMix = Math.max(targetMix, modeMix - mixStep);
                }
                left = lerp(normalLeft, chromeLeft, modeMix);
                right = lerp(normalRight, chromeRight, modeMix);
            }

            if (machineNoiseEnabled) {
                float motor = motorBed.next();
                left += electronicsNoiseLeft.next() + motor;
                right += electronicsNoiseRight.next() + motor * 0.86f;
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
        highTapeMix = modeMix;
    }

    public static float nominalWowFlutterRmsPercent() {
        double sum = RANDOM_SPEED_RMS * RANDOM_SPEED_RMS;
        for (double peak : SPEED_PEAK) {
            sum += peak * peak * 0.5;
        }
        return (float) (Math.sqrt(sum) * 100.0);
    }

    public static float serviceTapeSpeedTolerancePercent() {
        return 0.5f;
    }

    public static float serviceBeginningToEndDifferencePercent() {
        return 1.5f;
    }

    /**
     * Realised floor for the whole machine.
     *
     * <p>With the electronics brought down to sit under a correctly calibrated tape, what remains
     * is dominated by the belt, motor and roller bed rather than by the amplifier.</p>
     */
    static float integratedMachineNoiseFloorDb() {
        return -74f;
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
        if (integratedConditionEnabled) {
            for (int component = 0; component < conditionSine.length; component++) {
                float sine = conditionSine[component];
                float cosine = conditionCosine[component];
                float nextSine = sine * conditionStepCosine[component]
                        + cosine * conditionStepSine[component];
                float nextCosine = cosine * conditionStepCosine[component]
                        - sine * conditionStepSine[component];
                conditionSine[component] = nextSine;
                conditionCosine[component] = nextCosine;
                targetDelay += conditionDelayAmplitude[component] * nextSine;
            }
        }
        randomDelay = randomDelay * 0.99930f
                + irregularFlutter.nextSpeedError() * TRANSPORT_CONTROL_STRIDE;
        targetDelay += randomDelay;
        delayStep = (targetDelay - interpolatedDelay) / TRANSPORT_CONTROL_STRIDE;

        float nextAzimuthSine = azimuthSine * azimuthStepCosine
                + azimuthCosine * azimuthStepSine;
        float nextAzimuthCosine = azimuthCosine * azimuthStepCosine
                - azimuthSine * azimuthStepSine;
        azimuthSine = nextAzimuthSine;
        azimuthCosine = nextAzimuthCosine;
        float targetAzimuth = 0.135f + 0.045f * nextAzimuthSine
                + conditionBaseAzimuthSamples
                + conditionAzimuthDriftSamples * conditionSine[1];
        azimuthStep = (targetAzimuth - interpolatedAzimuth) / TRANSPORT_CONTROL_STRIDE;

        oscillatorFrames += TRANSPORT_CONTROL_STRIDE;
        if (oscillatorFrames >= 16_384) {
            oscillatorFrames = 0;
            for (int component = 0; component < TRANSPORT_HZ.length; component++) {
                float inverseMagnitude = (float) (1.0 / Math.sqrt(
                        transportSine[component] * transportSine[component]
                                + transportCosine[component] * transportCosine[component]));
                transportSine[component] *= inverseMagnitude;
                transportCosine[component] *= inverseMagnitude;
            }
            if (integratedConditionEnabled) {
                for (int component = 0; component < conditionSine.length; component++) {
                    float inverseMagnitude = (float) (1.0 / Math.sqrt(
                            conditionSine[component] * conditionSine[component]
                                    + conditionCosine[component] * conditionCosine[component]));
                    conditionSine[component] *= inverseMagnitude;
                    conditionCosine[component] *= inverseMagnitude;
                }
            }
            float inverseAzimuthMagnitude = (float) (1.0 / Math.sqrt(
                    azimuthSine * azimuthSine + azimuthCosine * azimuthCosine));
            azimuthSine *= inverseAzimuthMagnitude;
            azimuthCosine *= inverseAzimuthMagnitude;
        }
    }

    private float wrapReadPosition(float position) {
        if (position < 0f) {
            position += delayLeft.length;
        } else if (position >= delayLeft.length) {
            position -= delayLeft.length;
        }
        return position;
    }

    private float quadraticRead(float[] buffer, float position) {
        int centre = (int) position;
        float fraction = position - centre;
        float previous = buffer[(centre - 1) & delayMask];
        float current = buffer[centre & delayMask];
        float next = buffer[(centre + 1) & delayMask];
        float first = 0.5f * (next - previous);
        float second = 0.5f * (previous - 2f * current + next);
        return current + fraction * (first + fraction * second);
    }

    private static float outputStage(float input) {
        float magnitude = Math.abs(input);
        if (magnitude <= 0.78f) {
            return input;
        }
        float excess = magnitude - 0.78f;
        float rounded = 0.78f + excess / (1f + excess * 3.15f);
        return Math.copySign(Math.min(1.025f, rounded), input);
    }

    private static float lerp(float from, float to, float mix) {
        return from + (to - from) * mix;
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static float cutoffCoefficient(int sampleRate, float frequency) {
        return 1f - (float) Math.exp(-TWO_PI * frequency / sampleRate);
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

    private static final class ElectronicsNoise {
        private final long initialState;
        private final float highPassPole;
        private final float lowPassCoefficient;
        private final float targetRms;
        private long state;
        private float previousWhite;
        private float highPassed;
        private float lowPassed;
        private float scale;

        ElectronicsNoise(int sampleRate, long seed, float targetRms) {
            initialState = seed == 0 ? 0xbb67ae8584caa73bL : seed;
            highPassPole = (float) Math.exp(-TWO_PI * 115f / sampleRate);
            lowPassCoefficient = cutoffCoefficient(sampleRate,
                    Math.min(16_500f, sampleRate * 0.41f));
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
            double sumSquares = 0.0;
            final int count = 32_768;
            for (int sample = 0; sample < count; sample++) {
                float value = shapedWhite();
                sumSquares += value * value;
            }
            scale = targetRms / (float) Math.sqrt(sumSquares / count);
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
        private static final float FUNDAMENTAL_GAIN = dbToLinear(-74f);
        private static final float SECOND_GAIN = dbToLinear(-80f);
        private static final float IDLER_GAIN = dbToLinear(-83f);
        private final Oscillator fundamental;
        private final Oscillator second;
        private final Oscillator idler;

        MotorBed(int sampleRate, long seed) {
            float phaseOffset = ((seed >>> 20) & 0xffffL) / 65_535f;
            fundamental = new Oscillator(sampleRate, 58.7f, 1.1f + phaseOffset);
            second = new Oscillator(sampleRate, 117.4f, 3.6f + phaseOffset);
            idler = new Oscillator(sampleRate, 7.35f, 5.2f + phaseOffset);
        }

        float next() {
            return fundamental.next() * FUNDAMENTAL_GAIN
                    + second.next() * SECOND_GAIN + idler.next() * IDLER_GAIN;
        }

        void reset() {
            fundamental.reset();
            second.reset();
            idler.reset();
        }
    }

    private static final class Oscillator {
        private final float initialPhase;
        private final float stepSine;
        private final float stepCosine;
        private float sine;
        private float cosine;
        private int frames;

        Oscillator(int sampleRate, float frequency, float initialPhase) {
            this.initialPhase = initialPhase;
            double step = TWO_PI * frequency / sampleRate;
            stepSine = (float) Math.sin(step);
            stepCosine = (float) Math.cos(step);
            reset();
        }

        float next() {
            float nextSine = sine * stepCosine + cosine * stepSine;
            float nextCosine = cosine * stepCosine - sine * stepSine;
            sine = nextSine;
            cosine = nextCosine;
            if (++frames >= 16_384) {
                frames = 0;
                float inverseMagnitude = (float) (1.0 / Math.sqrt(sine * sine
                        + cosine * cosine));
                sine *= inverseMagnitude;
                cosine *= inverseMagnitude;
            }
            return sine;
        }

        void reset() {
            sine = (float) Math.sin(initialPhase);
            cosine = (float) Math.cos(initialPhase);
            frames = 0;
        }
    }

    private static final class StereoPeaking {
        private final StereoBiquad delegate;

        StereoPeaking(int sampleRate, float frequency, float q, float gainDb) {
            double a = Math.pow(10.0, gainDb / 40.0);
            double omega = TWO_PI * frequency / sampleRate;
            double alpha = Math.sin(omega) / (2.0 * q);
            double cosine = Math.cos(omega);
            double b0 = 1.0 + alpha * a;
            double b1 = -2.0 * cosine;
            double b2 = 1.0 - alpha * a;
            double a0 = 1.0 + alpha / a;
            double a1 = -2.0 * cosine;
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
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            double b0 = (1.0 - cosine) * 0.5;
            double b1 = 1.0 - cosine;
            double b2 = b0;
            double a0 = 1.0 + alpha;
            return new StereoBiquad(b0 / a0, b1 / a0, b2 / a0,
                    -2.0 * cosine / a0, (1.0 - alpha) / a0);
        }

        static StereoBiquad highPass(int sampleRate, float frequency, float q) {
            double omega = TWO_PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            double b0 = (1.0 + cosine) * 0.5;
            double b1 = -(1.0 + cosine);
            double b2 = b0;
            double a0 = 1.0 + alpha;
            return new StereoBiquad(b0 / a0, b1 / a0, b2 / a0,
                    -2.0 * cosine / a0, (1.0 - alpha) / a0);
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
