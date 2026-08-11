package com.yqdscott.walktape;

import java.util.Arrays;

/**
 * Allocation-free model of benign cassette-player tolerances.
 *
 * <p>The shared delay adds only bounded, sub-service-limit slow speed variation. A tiny static
 * channel sensitivity difference, fractional head azimuth and frequency-shaped crosstalk model a
 * real individual unit. There are intentionally no clicks, drop-outs, discontinuities or random
 * gain holes: those would be faults, not character.</p>
 */
final class MachineImperfectionDsp implements TapeMachineDsp {
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int CONTROL_STRIDE = 8;
    static final double[] TRANSPORT_HZ = {0.055, 0.16, 0.43};
    // Full LIVED-IN preset is 0.0284% RMS additional speed variation.
    static final double[] FULL_SPEED_PEAK = {0.00014, 0.00034, 0.00016};
    static final float FULL_AZIMUTH_DRIFT_SAMPLES = 0.018f;

    private final MachineConditionProfile profile;
    private final float[] delayLeft;
    private final float[] delayRight;
    private final int delayMask;
    private final float baseDelaySamples;
    private final float baseAzimuthSamples;
    private final float azimuthDriftSamples;
    private final float leftDirectMix;
    private final float rightDirectMix;
    private final float leftToRightMix;
    private final float rightToLeftMix;
    private final float highFrequencyBlend;
    private final float highFrequencyPole;
    private final boolean softenRight;
    private final float[] initialSine = new float[TRANSPORT_HZ.length];
    private final float[] initialCosine = new float[TRANSPORT_HZ.length];
    private final float[] transportSine = new float[TRANSPORT_HZ.length];
    private final float[] transportCosine = new float[TRANSPORT_HZ.length];
    private final float[] stepSine = new float[TRANSPORT_HZ.length];
    private final float[] stepCosine = new float[TRANSPORT_HZ.length];
    private final float[] delayAmplitude = new float[TRANSPORT_HZ.length];

    private int writeIndex;
    private int framesUntilControl;
    private int oscillatorUpdates;
    private float interpolatedDelay;
    private float delayStep;
    private float interpolatedAzimuth;
    private float azimuthStep;
    private float lowPassedLeft;
    private float lowPassedRight;

    MachineImperfectionDsp(int sampleRate,
                           MachineConditionProfile requestedProfile,
                           long unitSeed) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        profile = requestedProfile == null
                ? MachineConditionProfile.calibrated()
                : MachineConditionProfile.forId(requestedProfile.id);
        float scale = profile.transportScale;
        double maximumExcursion = 0.0;
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            double phase = seededPhase(unitSeed, component);
            initialSine[component] = (float) Math.sin(phase);
            initialCosine[component] = (float) Math.cos(phase);
            double phaseStep = TWO_PI * TRANSPORT_HZ[component]
                    * CONTROL_STRIDE / sampleRate;
            stepSine[component] = (float) Math.sin(phaseStep);
            stepCosine[component] = (float) Math.cos(phaseStep);
            delayAmplitude[component] = (float) (FULL_SPEED_PEAK[component] * scale
                    * sampleRate / (TWO_PI * TRANSPORT_HZ[component]));
            maximumExcursion += Math.abs(delayAmplitude[component]);
        }

        baseAzimuthSamples = profile.azimuthMicroseconds * sampleRate / 1_000_000f;
        azimuthDriftSamples = FULL_AZIMUTH_DRIFT_SAMPLES * scale;
        baseDelaySamples = (float) maximumExcursion + Math.abs(baseAzimuthSamples)
                + azimuthDriftSamples + 4f;
        int requestedSize = (int) Math.ceil(baseDelaySamples + maximumExcursion
                + Math.abs(baseAzimuthSamples) + azimuthDriftSamples + 8f);
        int delaySize = 1;
        while (delaySize < requestedSize) {
            delaySize <<= 1;
        }
        delayLeft = new float[delaySize];
        delayRight = new float[delaySize];
        delayMask = delaySize - 1;

        boolean leftHotter = (unitSeed & 1L) == 0L;
        float halfBalance = profile.channelBalanceDb * 0.5f;
        float leftGain = dbToLinear(leftHotter ? halfBalance : -halfBalance);
        float rightGain = dbToLinear(leftHotter ? -halfBalance : halfBalance);
        softenRight = leftHotter;
        // Mixing a 7.2 kHz one-pole path back at this low ratio produces the requested gentle
        // head-sensitivity mismatch without imposing a conspicuous filter on either channel.
        highFrequencyBlend = Math.min(0.08f,
                profile.highFrequencyMismatchDb * 0.155f);
        float extraCrosstalk = profile.extraCrosstalk;
        float crosstalkNormalise = 1f / (1f + extraCrosstalk);
        leftDirectMix = leftGain * crosstalkNormalise;
        rightDirectMix = rightGain * crosstalkNormalise;
        leftToRightMix = leftGain * extraCrosstalk * crosstalkNormalise;
        rightToLeftMix = rightGain * extraCrosstalk * crosstalkNormalise;
        float safeCorner = Math.min(7_200f, sampleRate * 0.35f);
        highFrequencyPole = 1f - (float) Math.exp(-TWO_PI * safeCorner / sampleRate);
        reset();
    }

    MachineConditionProfile profile() {
        return profile;
    }

    @Override
    public void setHighTape(boolean enabled) {
        // Unit tolerances are independent of the tape selector.
    }

    @Override
    public void reset() {
        Arrays.fill(delayLeft, 0f);
        Arrays.fill(delayRight, 0f);
        System.arraycopy(initialSine, 0, transportSine, 0, transportSine.length);
        System.arraycopy(initialCosine, 0, transportCosine, 0, transportCosine.length);
        writeIndex = 0;
        framesUntilControl = 0;
        oscillatorUpdates = 0;
        interpolatedDelay = targetDelay();
        delayStep = 0f;
        interpolatedAzimuth = targetAzimuth();
        azimuthStep = 0f;
        lowPassedLeft = 0f;
        lowPassedRight = 0f;
    }

    @Override
    public void process(float[] stereo, int frameCount) {
        if (stereo == null || frameCount < 0 || frameCount * 2 > stereo.length) {
            throw new IllegalArgumentException("Invalid stereo frame count");
        }
        for (int frame = 0; frame < frameCount; frame++) {
            if (framesUntilControl <= 0) {
                updateControl();
                framesUntilControl = CONTROL_STRIDE;
            }

            int sample = frame * 2;
            delayLeft[writeIndex] = finiteOrZero(stereo[sample]);
            delayRight[writeIndex] = finiteOrZero(stereo[sample + 1]);
            interpolatedDelay += delayStep;
            interpolatedAzimuth += azimuthStep;
            float left = quadraticRead(delayLeft, writeIndex - interpolatedDelay);
            float right = quadraticRead(delayRight,
                    writeIndex - interpolatedDelay - interpolatedAzimuth);
            writeIndex = (writeIndex + 1) & delayMask;
            framesUntilControl--;

            if (softenRight) {
                lowPassedRight += highFrequencyPole * (right - lowPassedRight);
                right += (lowPassedRight - right) * highFrequencyBlend;
            } else {
                lowPassedLeft += highFrequencyPole * (left - lowPassedLeft);
                left += (lowPassedLeft - left) * highFrequencyBlend;
            }

            float originalLeft = left;
            float originalRight = right;
            left = originalLeft * leftDirectMix + originalRight * rightToLeftMix;
            right = originalRight * rightDirectMix + originalLeft * leftToRightMix;
            stereo[sample] = finiteOrZero(left);
            stereo[sample + 1] = finiteOrZero(right);
        }
    }

    private void updateControl() {
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            float sine = transportSine[component];
            float cosine = transportCosine[component];
            transportSine[component] = sine * stepCosine[component]
                    + cosine * stepSine[component];
            transportCosine[component] = cosine * stepCosine[component]
                    - sine * stepSine[component];
        }
        float nextDelay = targetDelay();
        delayStep = (nextDelay - interpolatedDelay) / CONTROL_STRIDE;
        float nextAzimuth = targetAzimuth();
        azimuthStep = (nextAzimuth - interpolatedAzimuth) / CONTROL_STRIDE;

        oscillatorUpdates++;
        if ((oscillatorUpdates & 0x0fff) == 0) {
            for (int component = 0; component < TRANSPORT_HZ.length; component++) {
                float magnitude = (float) Math.hypot(
                        transportSine[component], transportCosine[component]);
                if (magnitude > 0f) {
                    transportSine[component] /= magnitude;
                    transportCosine[component] /= magnitude;
                }
            }
        }
    }

    private float targetDelay() {
        float target = baseDelaySamples;
        for (int component = 0; component < TRANSPORT_HZ.length; component++) {
            target += delayAmplitude[component] * transportSine[component];
        }
        return target;
    }

    private float targetAzimuth() {
        return baseAzimuthSamples + azimuthDriftSamples * transportSine[1];
    }

    private float quadraticRead(float[] ring, float position) {
        // Construction guarantees less than one ring of excursion, so one bounded wrap is
        // equivalent to the old loops and avoids two Math.floor calls per stereo frame.
        if (position < 0f) {
            position += ring.length;
        } else if (position >= ring.length) {
            position -= ring.length;
        }
        int centre = (int) position;
        float fraction = position - centre;
        float previous = ring[(centre - 1) & delayMask];
        float current = ring[centre & delayMask];
        float next = ring[(centre + 1) & delayMask];
        return current + 0.5f * fraction
                * (next - previous + fraction * (previous - 2f * current + next));
    }

    static double seededPhase(long seed, int component) {
        long mixed = seed + 0x9e3779b97f4a7c15L * (component + 1L);
        mixed ^= mixed >>> 30;
        mixed *= 0xbf58476d1ce4e5b9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94d049bb133111ebL;
        mixed ^= mixed >>> 31;
        return (mixed >>> 11) * 0x1.0p-53 * TWO_PI;
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0f;
    }
}
