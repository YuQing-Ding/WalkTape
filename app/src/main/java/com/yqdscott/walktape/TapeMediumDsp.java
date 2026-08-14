package com.yqdscott.walktape;

import java.util.Arrays;

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
    private final OversampledCoating leftCoating;
    private final OversampledCoating rightCoating;
    private final TapeNoise leftNoise;
    private final TapeNoise rightNoise;
    private final CoatingWander coatingWander;
    private final float replaySensitivity;
    /**
     * Record gain and its exact inverse.
     *
     * <p>They default to unity so a directly constructed renderer measures the coating's own
     * published behaviour; the production factory sets the level the user chose.</p>
     */
    private volatile float recordGain = 1f;
    private volatile float replayMakeUp = 1f;
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
        // Use the instance as given. Routing it back through forId() silently replaced it with the
        // catalogue singleton, so any profile carrying different renderer constants — a
        // calibration run, a regression fixture — was rendered as the stock one instead.
        // TapeStockProfile cannot be constructed from outside the class, so a non-null instance is
        // already a valid one and needs no normalisation here.
        profile = requestedProfile == null ? TapeStockProfile.sonyChf1978() : requestedProfile;

        float safeTop = sampleRate * 0.43f;
        float replayCorner = (float) (1.0 / (TWO_PI
                * profile.replayEqMicroseconds * 0.000001));
        replayCorner = Math.min(replayCorner, safeTop);
        float recordGain = dbToLinear(profile.recordTrebleGainDb);
        recordEqualisation = new StereoShelf(sampleRate, replayCorner, recordGain);
        replayEqualisation = new StereoShelf(sampleRate, replayCorner, 1f / recordGain);

        MagnetisationCurve curve = new MagnetisationCurve(profile.magneticKnee);
        leftCoating = new OversampledCoating(sampleRate, profile, curve, 0.030f);
        rightCoating = new OversampledCoating(sampleRate, profile, curve, -0.024f);
        leftNoise = new TapeNoise(sampleRate, seed ^ 0x4c4546544348414eL, profile);
        rightNoise = new TapeNoise(sampleRate, seed ^ 0x524748544348414eL, profile);
        coatingWander = new CoatingWander(sampleRate, seed ^ 0x434f4154494e4757L,
                profile.coatingWanderDepth);
        replaySensitivity = dbToLinear(profile.sensitivityDb);
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

    /**
     * Sets where the recordist put the level control for this tape.
     *
     * <p>Passing null restores unity, which is the coating measured on its own terms.</p>
     */
    @Override
    public void setRecordLevel(RecordLevelProfile level) {
        float gainDb = level == null ? 0f : level.recordGainDb();
        if (Float.isNaN(gainDb) || Float.isInfinite(gainDb)) {
            gainDb = 0f;
        }
        float gain = (float) Math.pow(10.0, Math.max(-30f, Math.min(6f, gainDb)) / 20.0);
        recordGain = gain;
        replayMakeUp = 1f / gain;
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
        float recordGain = this.recordGain;
        float replayMakeUp = this.replayMakeUp;
        for (int frame = 0; frame < frameCount; frame++) {
            int sample = frame * 2;
            // The record level control, ahead of everything. A coating has one fixed maximum
            // output level; where the recordist set this knob is what decides how much of the
            // music sits under it and how much is pressed against it.
            float left = recordEqualisation.processLeft(stereo[sample] * recordGain);
            float right = recordEqualisation.processRight(stereo[sample + 1] * recordGain);

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

            // Replay sensitivity is what this stock puts out for a given recorded flux compared
            // with the reference tape for its type. A deck aligned to the reference therefore
            // plays different stock back at slightly different levels; it applies equally to
            // programme and to hiss, so it leaves every measured ratio untouched.
            left *= replaySensitivity;
            right *= replaySensitivity;

            // Undo the record gain so choosing a record level changes how hard the tape was
            // driven rather than how loud playback is, which is what adjusting volume to suit
            // the tape amounts to. Hiss is lifted along with programme, exactly as it is on a
            // quietly recorded tape played loud.
            left *= replayMakeUp;
            right *= replayMakeUp;

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

    /**
     * Magnetisation curve sampled onto a table, shared by both channels.
     *
     * <p>The curve is {@code v / (1 + |v|^k)^(1/k)}: unity slope at the origin and a genuine
     * ceiling of one, with {@code k} setting how abruptly the knee arrives. A real coating holds a
     * finite remanence, so it must asymptote to a constant. The previous rational curve approached
     * a straight line of slope {@code n/d} instead, which is why the rendered stock had compression
     * but no saturation output level at all.</p>
     *
     * <p>Evaluating {@code Math.pow} twice per sample would cost more than the rest of the tape
     * stage put together, so the shape is baked into a table once and read with one interpolation.
     * </p>
     */
    private static final class MagnetisationCurve {
        private static final int POINTS = 4_096;
        private static final float RANGE = 8f;
        private static final float SCALE = POINTS / RANGE;

        private final float[] table = new float[POINTS + 2];

        MagnetisationCurve(float knee) {
            float bounded = Math.max(1.05f, Math.min(8f, knee));
            for (int index = 0; index <= POINTS + 1; index++) {
                double v = index / (double) SCALE;
                table[index] = (float) (v / Math.pow(1.0 + Math.pow(v, bounded), 1.0 / bounded));
            }
        }

        float value(float v) {
            float magnitude = Math.abs(v);
            if (magnitude >= RANGE) {
                return Math.copySign(table[POINTS], v);
            }
            float scaled = magnitude * SCALE;
            int index = (int) scaled;
            float fraction = scaled - index;
            float interpolated = table[index]
                    + (table[index + 1] - table[index]) * fraction;
            return Math.copySign(interpolated, v);
        }
    }

    /**
     * Runs a magnetic channel at twice the sample rate so its saturation does not alias.
     *
     * <p>Saturating a 7 kHz tone at 44.1 kHz throws harmonics well past Nyquist, and they reflect
     * back to frequencies that are not multiples of anything in the music — measured at only 29 dB
     * below the tone at full level, and heard as a gritty fizz on cymbals rather than as tape
     * distortion. Doubling the rate moves the first reflection from 22 kHz to 44 kHz, so only very
     * high harmonics, already far down, can fold at all.</p>
     *
     * <p>The interpolation and decimation share one symmetric half-band kernel, applied
     * polyphase: the even phase of a half-band filter is a single centre tap, so upsampling costs
     * one multiply-accumulate pass rather than two.</p>
     */
    private static final class OversampledCoating {
        private static final int OVERSAMPLE = 2;
        private static final int TAPS = 31;
        private static final int CENTRE = TAPS / 2;

        private final float[] kernel = new float[TAPS];
        private final float[] upHistory = new float[TAPS];
        private final float[] downHistory = new float[TAPS];
        private final MagneticChannel channel;
        private int upCursor;
        private int downCursor;

        OversampledCoating(int sampleRate,
                           TapeStockProfile profile,
                           MagnetisationCurve curve,
                           float channelAsymmetry) {
            channel = new MagneticChannel(sampleRate * OVERSAMPLE, profile, curve,
                    channelAsymmetry);
            // Windowed sinc cutting at a quarter of the doubled rate, which is Nyquist of the
            // original rate. Normalised to unity gain at DC so the pair is transparent.
            double sum = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                int offset = tap - CENTRE;
                double sinc = offset == 0 ? 0.5
                        : Math.sin(Math.PI * offset * 0.5) / (Math.PI * offset);
                double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * tap / (TAPS - 1.0));
                kernel[tap] = (float) (sinc * window);
                sum += kernel[tap];
            }
            for (int tap = 0; tap < TAPS; tap++) {
                kernel[tap] /= (float) sum;
            }
        }

        float process(float input, float envelope) {
            // Zero-stuff, then interpolate. The doubled stream is built explicitly rather than
            // by a polyphase shortcut: the arithmetic saved is not worth the chance of getting
            // the phase or the gain quietly wrong.
            float first = channel.process(filterUp(input * OVERSAMPLE), envelope);
            float second = channel.process(filterUp(0f), envelope);
            filterDown(first);
            return filterDown(second);
        }

        private float filterUp(float sample) {
            upHistory[upCursor] = sample;
            float sum = 0f;
            int index = upCursor;
            for (int tap = 0; tap < TAPS; tap++) {
                sum += kernel[tap] * upHistory[index];
                index--;
                if (index < 0) {
                    index = TAPS - 1;
                }
            }
            upCursor++;
            if (upCursor >= TAPS) {
                upCursor = 0;
            }
            return sum;
        }

        /** Filters at the doubled rate; the caller keeps only the second of each pair. */
        private float filterDown(float sample) {
            downHistory[downCursor] = sample;
            float sum = 0f;
            int index = downCursor;
            for (int tap = 0; tap < TAPS; tap++) {
                sum += kernel[tap] * downHistory[index];
                index--;
                if (index < 0) {
                    index = TAPS - 1;
                }
            }
            downCursor++;
            if (downCursor >= TAPS) {
                downCursor = 0;
            }
            return sum;
        }

        void reset() {
            Arrays.fill(upHistory, 0f);
            Arrays.fill(downHistory, 0f);
            upCursor = 0;
            downCursor = 0;
            channel.reset();
        }
    }

    /** Stateful asymmetric hysteresis approximation with level-dependent HF saturation. */
    private static final class MagneticChannel {
        private final float drive;
        private final float inverseDrive;
        private final float asymmetry;
        private final MagnetisationCurve curve;
        private final float hysteresisRate;
        private final float hysteresisDepth;
        private final float baseBandwidthCoefficient;
        private final float maximumDynamicLoss;
        private float previousInput;
        private float magnetisation;
        private float coatingState;
        private float dcInput;
        private float dcOutput;

        MagneticChannel(int sampleRate,
                        TapeStockProfile profile,
                        MagnetisationCurve curve,
                        float channelAsymmetry) {
            drive = profile.magneticDrive;
            inverseDrive = 1f / drive;
            asymmetry = channelAsymmetry * (profile.iecType == 1 ? 1f : 0.72f);
            this.curve = curve;
            if (profile.iecType == 1) {
                hysteresisDepth = 0.205f;
            } else if (profile.iecType == 2) {
                hysteresisDepth = 0.155f;
            } else {
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
            // Nothing bypasses the coating. Every path to the replay head has been through the
            // magnetisation curve, which is what gives the stock a real maximum output level.
            float output = (curve.value(headIntegrated * drive + offset)
                    - curve.value(offset)) * inverseDrive;

            // Short wavelengths self-demagnetise, so the coating pole falls with programme level.
            // This works with the record pre-emphasis to place the saturation output level well
            // below the long-wavelength maximum output level, exactly as measured stock does.
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
