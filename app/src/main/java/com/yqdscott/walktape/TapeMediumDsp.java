package com.yqdscott.walktape;

/**
 * Allocation-free record/tape/replay model for one cassette formulation.
 *
 * <p>This stage intentionally contains no capstan, head-gap or player electronics behaviour;
 * those belong to the selected machine.  The order is record equalisation, magnetic coating,
 * replay equalisation, coating instability and tape noise.</p>
 */
final class TapeMediumDsp implements TapeMachineDsp {
    private static final double TWO_PI = Math.PI * 2.0;

    private final TapeStockProfile profile;
    private final StereoShelf recordEqualisation;
    private final StereoShelf replayEqualisation;
    private final MagneticChannel leftCoating;
    private final MagneticChannel rightCoating;
    private final TapeNoise leftNoise;
    private final TapeNoise rightNoise;
    private final CoatingWander coatingWander;
    private final float envelopeAttack;
    private final float envelopeRelease;
    private float programEnvelope;

    TapeMediumDsp(int sampleRate, TapeStockProfile requestedProfile) {
        this(sampleRate, requestedProfile, 0x544150454d454449L);
    }

    TapeMediumDsp(int sampleRate, TapeStockProfile requestedProfile, long seed) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        profile = requestedProfile == null
                ? TapeStockProfile.sonyChf1978()
                : TapeStockProfile.forId(requestedProfile.id);

        float safeTop = sampleRate * 0.43f;
        float replayCorner = (float) (1.0 / (TWO_PI
                * profile.replayEqMicroseconds * 0.000001));
        replayCorner = Math.min(replayCorner, safeTop);
        float recordGain = dbToLinear(profile.recordTrebleGainDb);
        recordEqualisation = new StereoShelf(sampleRate, replayCorner, recordGain);
        replayEqualisation = new StereoShelf(sampleRate, replayCorner, 1f / recordGain);

        leftCoating = new MagneticChannel(sampleRate, profile, 0.030f);
        rightCoating = new MagneticChannel(sampleRate, profile, -0.024f);
        leftNoise = new TapeNoise(sampleRate, seed ^ 0x4c4546544348414eL, profile);
        rightNoise = new TapeNoise(sampleRate, seed ^ 0x524748544348414eL, profile);
        coatingWander = new CoatingWander(sampleRate, seed ^ 0x434f4154494e4757L,
                profile.coatingWanderDepth);
        envelopeAttack = timeCoefficient(sampleRate, 0.0024f);
        envelopeRelease = timeCoefficient(sampleRate, 0.110f);
        reset();
    }

    TapeStockProfile profile() {
        return profile;
    }

    @Override
    public void setHighTape(boolean enabled) {
        // A tape formulation does not change when the player's tone/tape selector moves.
    }

    @Override
    public void reset() {
        recordEqualisation.reset();
        replayEqualisation.reset();
        leftCoating.reset();
        rightCoating.reset();
        leftNoise.reset();
        rightNoise.reset();
        coatingWander.reset();
        programEnvelope = 0f;
    }

    @Override
    public void process(float[] stereo, int frameCount) {
        if (frameCount < 0 || frameCount * 2 > stereo.length) {
            throw new IllegalArgumentException("Invalid stereo frame count");
        }
        float envelope = programEnvelope;
        for (int frame = 0; frame < frameCount; frame++) {
            int sample = frame * 2;
            float left = recordEqualisation.processLeft(stereo[sample]);
            float right = recordEqualisation.processRight(stereo[sample + 1]);

            float magnitude = Math.max(Math.abs(left), Math.abs(right));
            float envelopeRate = magnitude > envelope ? envelopeAttack : envelopeRelease;
            envelope += (magnitude - envelope) * envelopeRate;

            left = leftCoating.process(left, envelope);
            right = rightCoating.process(right, envelope);
            left = replayEqualisation.processLeft(left);
            right = replayEqualisation.processRight(right);

            // Microscopic coating-density and particle-orientation changes amplitude-modulate
            // both programme and hiss. It is deliberately smooth: a healthy tape should not
            // invent conspicuous clicks or fake random drop-outs.
            float wander = coatingWander.next();
            float leftGain = 1f + wander;
            float rightGain = 1f + wander * 0.83f;
            left = left * leftGain + leftNoise.next(envelope, wander);
            right = right * rightGain + rightNoise.next(envelope, wander * 0.91f);

            // Leave a little headroom for the machine's measured response and analogue output
            // stage. This guard is continuous and normally inactive.
            stereo[sample] = safetyKnee(left);
            stereo[sample + 1] = safetyKnee(right);
        }
        programEnvelope = envelope;
    }

    static float renderedHissFloorDb(TapeStockProfile profile) {
        return TapeStockProfile.forId(profile == null ? null : profile.id).renderedHissRmsDb;
    }

    private static float safetyKnee(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= 1.08f) {
            return value;
        }
        float excess = magnitude - 1.08f;
        float rounded = 1.08f + excess / (1f + excess * 3.4f);
        return Math.copySign(Math.min(1.22f, rounded), value);
    }

    private static float timeCoefficient(int sampleRate, float seconds) {
        return 1f - (float) Math.exp(-1.0 / (sampleRate * seconds));
    }

    private static float cutoffCoefficient(int sampleRate, float frequency) {
        return 1f - (float) Math.exp(-TWO_PI * frequency / sampleRate);
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    /** Stateful asymmetric hysteresis approximation with level-dependent HF saturation. */
    private static final class MagneticChannel {
        private final float drive;
        private final float inverseDrive;
        private final float asymmetry;
        private final float curveNumerator;
        private final float curveDenominator;
        private final float directFeed;
        private final float hysteresisRate;
        private final float hysteresisDepth;
        private final float baseBandwidthCoefficient;
        private final float maximumDynamicLoss;
        private float previousInput;
        private float magnetisation;
        private float coatingState;
        private float dcInput;
        private float dcOutput;

        MagneticChannel(int sampleRate, TapeStockProfile profile, float channelAsymmetry) {
            drive = profile.magneticDrive;
            inverseDrive = 1f / drive;
            asymmetry = channelAsymmetry * (profile.iecType == 1 ? 1f : 0.72f);
            if (profile.iecType == 1) {
                curveNumerator = 0.026f;
                curveDenominator = 0.315f;
                directFeed = 0.13f;
                hysteresisDepth = 0.205f;
            } else if (profile.iecType == 2) {
                curveNumerator = 0.034f;
                curveDenominator = 0.238f;
                directFeed = 0.17f;
                hysteresisDepth = 0.155f;
            } else {
                curveNumerator = 0.041f;
                curveDenominator = 0.185f;
                directFeed = 0.21f;
                hysteresisDepth = 0.115f;
            }
            hysteresisRate = timeCoefficient(sampleRate, profile.iecType == 1
                    ? 0.0019f : 0.00145f);
            baseBandwidthCoefficient = cutoffCoefficient(sampleRate,
                    Math.min(profile.coatingBandwidthHz, sampleRate * 0.42f));
            maximumDynamicLoss = profile.maximumDynamicLoss;
        }

        float process(float input, float envelope) {
            // Record-head integration removes sample-perfect corners before the non-linearity.
            float headIntegrated = input * 0.77f + previousInput * 0.23f;
            previousInput = input;

            float previousMagnetisation = magnetisation;
            magnetisation += (headIntegrated - magnetisation) * hysteresisRate;
            float offset = asymmetry + previousMagnetisation * hysteresisDepth;
            float curved = (curve(headIntegrated * drive + offset) - curve(offset))
                    * inverseDrive;
            float output = headIntegrated * directFeed + curved * (1f - directFeed);

            // HF saturation (SOL) arrives before low-frequency MOL. Lowering this coating pole
            // with programme level gives hot cymbals/piano attacks the familiar rounded edge.
            float normalisedEnvelope = Math.min(1.25f, envelope * drive * 0.92f);
            float dynamicLoss = maximumDynamicLoss * normalisedEnvelope
                    * normalisedEnvelope / (0.34f + normalisedEnvelope * normalisedEnvelope);
            float coefficient = baseBandwidthCoefficient * (1f - dynamicLoss);
            coatingState += (output - coatingState) * coefficient;

            // Remove DC generated by the deliberate left/right coating asymmetry.
            float blocked = coatingState - dcInput + 0.9974f * dcOutput;
            dcInput = coatingState;
            dcOutput = blocked;
            return blocked;
        }

        private float curve(float input) {
            float squared = input * input;
            return input * (1f + curveNumerator * squared)
                    / (1f + curveDenominator * squared);
        }

        void reset() {
            previousInput = 0f;
            magnetisation = 0f;
            coatingState = 0f;
            dcInput = 0f;
            dcOutput = 0f;
        }
    }

    /** Tape-particle noise, spectrally bounded and level-modulated by recorded programme. */
    private static final class TapeNoise {
        private final long initialState;
        private final float highPassPole;
        private final float lowPassCoefficient;
        private final float targetRms;
        private final float modulationDepth;
        private long state;
        private float previousWhite;
        private float highPassed;
        private float lowPassed;
        private float scale;

        TapeNoise(int sampleRate, long seed, TapeStockProfile profile) {
            initialState = seed == 0 ? 0x9e3779b97f4a7c15L : seed;
            float highPass = profile.iecType == 1 ? 760f : 1_180f;
            float lowPass = Math.min(profile.iecType == 4 ? 18_500f : 15_500f,
                    sampleRate * 0.41f);
            highPassPole = (float) Math.exp(-TWO_PI * highPass / sampleRate);
            lowPassCoefficient = cutoffCoefficient(sampleRate, lowPass);
            targetRms = dbToLinear(profile.renderedHissRmsDb);
            modulationDepth = profile.modulationNoiseDepth;
            calibrate();
        }

        float next(float envelope, float wander) {
            float modulation = 1f + Math.min(modulationDepth,
                    envelope * modulationDepth * 1.18f) + Math.max(-0.10f, wander * 8f);
            return shapedWhite() * scale * modulation;
        }

        void reset() {
            state = initialState;
            previousWhite = 0f;
            highPassed = 0f;
            lowPassed = 0f;
        }

        private void calibrate() {
            reset();
            double squareSum = 0.0;
            final int sampleCount = 32_768;
            for (int sample = 0; sample < sampleCount; sample++) {
                float value = shapedWhite();
                squareSum += value * value;
            }
            scale = targetRms / (float) Math.sqrt(squareSum / sampleCount);
            reset();
        }

        private float shapedWhite() {
            long x = state;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            state = x;
            float white = ((x >>> 40) / 8_388_607.5f) - 1f;
            highPassed = white - previousWhite + highPassPole * highPassed;
            previousWhite = white;
            lowPassed += (highPassed - lowPassed) * lowPassCoefficient;
            return lowPassed;
        }
    }

    /** Smooth sub-audible coating-density variation; updated at a low control rate. */
    private static final class CoatingWander {
        private static final int CONTROL_STRIDE = 8;
        private final long initialState;
        private final float fastCoefficient;
        private final float slowCoefficient;
        private final float depth;
        private long state;
        private float fast;
        private float slow;
        private float current;
        private float step;
        private int framesUntilUpdate;

        CoatingWander(int sampleRate, long seed, float depth) {
            initialState = seed == 0 ? 0x6a09e667f3bcc909L : seed;
            int controlRate = Math.max(1, sampleRate / CONTROL_STRIDE);
            fastCoefficient = cutoffCoefficient(controlRate, 23f);
            slowCoefficient = cutoffCoefficient(controlRate, 0.63f);
            this.depth = depth;
            reset();
        }

        float next() {
            if (framesUntilUpdate <= 0) {
                float white = nextWhite();
                fast += (white - fast) * fastCoefficient;
                slow += (white - slow) * slowCoefficient;
                float target = clamp((fast - slow) * 3.1f, -1f, 1f) * depth;
                step = (target - current) / CONTROL_STRIDE;
                framesUntilUpdate = CONTROL_STRIDE;
            }
            current += step;
            framesUntilUpdate--;
            return current;
        }

        void reset() {
            state = initialState;
            fast = 0f;
            slow = 0f;
            current = 0f;
            step = 0f;
            framesUntilUpdate = 0;
        }

        private float nextWhite() {
            long x = state;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            state = x;
            return ((x >>> 40) / 8_388_607.5f) - 1f;
        }

        private static float clamp(float value, float minimum, float maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }

    private static final class StereoShelf {
        private final float coefficient;
        private final float highGain;
        private float lowLeft;
        private float lowRight;

        StereoShelf(int sampleRate, float corner, float highGain) {
            coefficient = cutoffCoefficient(sampleRate, corner);
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
}
