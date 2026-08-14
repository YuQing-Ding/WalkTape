package com.yqdscott.walktape;

import java.util.Arrays;

/**
 * Adjacent-winding print-through, with both the pre-echo and the post-echo a real pack produces.
 *
 * <p>A recorded layer magnetises the two layers it is wound against: the one outside it and the one
 * inside it. On replay that is heard as a copy of the programme one pack revolution <em>before</em>
 * the sound (pre-echo) and one revolution <em>after</em> it (post-echo). The two are not equal,
 * because one neighbour faces the coating and the other faces the base film; that geometry is also
 * why storing a tape tails-out trades the more noticeable pre-echo for the less noticeable
 * post-echo.</p>
 *
 * <p>Print-through is strongly wavelength dependent rather than flat. The printed amplitude follows
 * the demagnetising field of the recorded layer across the tape thickness {@code d}, giving a
 * response proportional to {@code u*exp(-u)} with {@code u = 2*pi*d*f/v}. It therefore peaks where
 * the recorded wavelength equals {@code 2*pi*d} and vanishes at both DC and high frequency. With
 * the IEC 60094-1 tape speed of 4.7625 cm/s and an 18 um C-60 ferric tape that peak lands close to
 * 420 Hz, which is why print-through is heard as a dull, wordless shadow rather than as a legible
 * echo.</p>
 *
 * <p>Producing a pre-echo requires knowing the programme one revolution ahead, so this stage is
 * deliberately latent by exactly one revolution and reports that latency through
 * {@link #latencyFrames()}. The caller discards an equal number of leading frames after a reset,
 * which restores sample-accurate alignment with the source timeline.</p>
 */
final class TpsL2TapeLayerBleed {

    /** IEC 60094-1 compact-cassette tape speed, 4.7625 cm/s. */
    static final double TAPE_SPEED_M_PER_S = 0.047625;

    /**
     * Total thickness of a C-60 ferric tape, base film plus coating. C-90 stock is about 12 um and
     * C-120 about 9 um; the thinner the tape, the higher the print-through peak moves.
     */
    static final double TAPE_THICKNESS_M = 18e-6;

    /** Print-through is strongest where the recorded wavelength equals 2*pi*d. */
    static final double PEAK_HERTZ = TAPE_SPEED_M_PER_S / (2.0 * Math.PI * TAPE_THICKNESS_M);

    private static final float PRINT_THROUGH_GAIN = 0.00110f; // about -59 dB, unchanged in total
    private static final float STEREO_COUPLING = 0.24f;

    // Engineering prior: the printed levels either side of a layer are split so that the neighbour
    // facing the coating carries about 2 dB more than the one facing the base film. Their powers
    // still sum to one, so the total print-through energy is exactly what it was before the echo
    // was split in two.
    private static final float PRE_ECHO_GAIN = 0.7899f;  // 0.62 of the power
    private static final float POST_ECHO_GAIN = 0.6132f; // 0.38 of the power

    /**
     * Low-pass poles following the high-pass section.
     *
     * <p>Four of them place the realised peak at {@code f/2} of the common corner and give the
     * steep upper skirt the physical {@code u*exp(-u)} law needs; the fit stays within about 4 dB
     * from below 100 Hz to beyond 3 kHz, on a shadow that is already 59 dB down.</p>
     */
    /**
     * Whether the pre-echo is rendered, at the cost of one revolution of look-ahead.
     *
     * <p>Hearing the layer that has not been played yet is only possible by holding the programme
     * back by a full pack revolution, which makes this stage latent by almost three seconds and
     * obliges the caller to discard a matching lead-in and to flush a matching tail. Turning it off
     * keeps the post-echo, the wavelength shaping and the pack-radius spacing, and makes the stage
     * sample aligned again, so it is the single switch that separates the print-through model from
     * every timing consequence it carries.</p>
     */
    static final boolean PRE_ECHO_ENABLED = true;

    private static final int SHAPER_POLES = 4;

    /** Common corner placing the realised maximum exactly on {@link #PEAK_HERTZ}. */
    private static final double SHAPER_CORNER_HERTZ = 2.0 * PEAK_HERTZ;

    private final float[] leftDelay;
    private final float[] rightDelay;
    private final int size;
    private final int sampleRate;
    private final int latencyFrames;
    private final PrintThroughShaper leftShaper;
    private final PrintThroughShaper rightShaper;

    private volatile float requestedPosition = 0.5f;
    private float position;
    private int writeIndex;

    TpsL2TapeLayerBleed(int sampleRate) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        // The post-echo tap sits two revolutions behind the write head, because the direct signal
        // is itself held back by one. A plain conditional wrap keeps the buffer exactly as long as
        // the physics needs instead of rounding up to a power of two.
        latencyFrames = PRE_ECHO_ENABLED ? wrapSamples(0f) : 0;
        size = wrapSamples(0f) * 2 + 8;
        leftDelay = new float[size];
        rightDelay = new float[size];
        leftShaper = new PrintThroughShaper(sampleRate);
        rightShaper = new PrintThroughShaper(sampleRate);
        reset();
    }

    /**
     * Frames of look-ahead this stage consumes before its first aligned output sample.
     *
     * <p>Equal to one revolution of a full supply pack, which is the largest value
     * {@link #wrapSamples(float)} can return.</p>
     */
    int latencyFrames() {
        return latencyFrames;
    }

    void setTapePosition(float tapePosition) {
        if (Float.isNaN(tapePosition) || Float.isInfinite(tapePosition)) {
            requestedPosition = 0.5f;
        } else {
            requestedPosition = Math.max(0f, Math.min(1f, tapePosition));
        }
    }

    void reset() {
        Arrays.fill(leftDelay, 0f);
        Arrays.fill(rightDelay, 0f);
        leftShaper.reset();
        rightShaper.reset();
        position = requestedPosition;
        writeIndex = 0;
    }

    long memoryBytes() {
        return (long) (leftDelay.length + rightDelay.length) * Float.BYTES;
    }

    static double peakHertz() {
        return PEAK_HERTZ;
    }

    /**
     * Seconds for one revolution of the supply pack at a given position, which is the spacing
     * between the programme and each of its printed neighbours.
     */
    static float revolutionSeconds(float tapePosition) {
        float bounded = Math.max(0f, Math.min(1f, tapePosition));
        float supplyRadius = (float) Math.sqrt(0.17f + (1f - bounded) * 0.83f);
        return 1.08f + supplyRadius * 1.82f;
    }

    /**
     * Returns the direct signal one revolution old, plus the printed neighbours either side of it.
     *
     * <p>Both channels are computed in one call because the pre- and post-echo taps mix the two
     * sides of the head before a single shaping filter, which is exact for a linear print-through
     * response and costs one filter per channel instead of two.</p>
     */
    void process(float left, float right, float[] output) {
        position += (requestedPosition - position) * 0.0000208f;

        int wrap = wrapSamples(position);
        leftDelay[writeIndex] = left;
        rightDelay[writeIndex] = right;

        // The direct tap is held at the fixed look-ahead and never moves. Its delay is an artifact
        // of needing to see one revolution ahead, not a physical quantity, and letting it track the
        // shrinking supply pack would step it by a sample several hundred times a second: a stream
        // of full-level clicks heard as a buzz. Only the echo taps follow the pack radius, and a
        // sample step 59 dB down is inaudible.
        int direct = index(writeIndex - latencyFrames);
        int post = index(writeIndex - (latencyFrames + wrap));

        // Without look-ahead there is no layer ahead of the programme to read, so the pre-echo
        // simply does not sound; the post-echo and the shaping are unaffected.
        float preLeft = 0f;
        float preRight = 0f;
        if (PRE_ECHO_ENABLED) {
            int pre = index(writeIndex - (latencyFrames - wrap));
            preLeft = leftDelay[pre];
            preRight = rightDelay[pre];
        }
        float postLeft = leftDelay[post];
        float postRight = rightDelay[post];

        float bleedLeft = PRE_ECHO_GAIN * (preLeft * (1f - STEREO_COUPLING)
                + preRight * STEREO_COUPLING)
                + POST_ECHO_GAIN * (postLeft * (1f - STEREO_COUPLING)
                + postRight * STEREO_COUPLING);
        float bleedRight = PRE_ECHO_GAIN * (preRight * (1f - STEREO_COUPLING)
                + preLeft * STEREO_COUPLING)
                + POST_ECHO_GAIN * (postRight * (1f - STEREO_COUPLING)
                + postLeft * STEREO_COUPLING);

        output[0] = leftDelay[direct] + leftShaper.process(bleedLeft) * PRINT_THROUGH_GAIN;
        output[1] = rightDelay[direct] + rightShaper.process(bleedRight) * PRINT_THROUGH_GAIN;

        writeIndex++;
        if (writeIndex >= size) {
            writeIndex = 0;
        }
    }

    private int index(int raw) {
        int wrapped = raw % size;
        return wrapped < 0 ? wrapped + size : wrapped;
    }

    /**
     * One revolution of the supply pack, in samples.
     *
     * <p>The pack radius follows the square root of wound area, and one revolution takes
     * {@code 2*pi*r/v}. Position 0 is a full supply pack and therefore the longest echo spacing.
     * The constants reproduce the calibrated 1.83 s to 2.90 s span of a compact cassette.</p>
     */
    private int wrapSamples(float tapePosition) {
        return Math.max(1, Math.round(revolutionSeconds(tapePosition) * sampleRate));
    }

    /**
     * Shaping filter for the printed signal.
     *
     * <p>The magnitude target is the physical print-through response {@code e*u*exp(-u)} with
     * {@code u = f / PEAK_HERTZ}: zero at DC because a uniformly magnetised layer prints no net
     * field, a maximum where the recorded wavelength equals {@code 2*pi*d}, and a fast decay above
     * it because shorter wavelengths cannot reach across the tape thickness.</p>
     *
     * <p>It is realised as one high-pass pole and four low-pass poles at a common corner rather
     * than as a linear-phase FIR. Resolving a 421 Hz maximum against a DC null demands a window of
     * roughly ten milliseconds, which is some five hundred taps at 48 kHz — far more arithmetic
     * than a shadow 59 dB down can justify. The pole cascade tracks the target within about 4 dB
     * across the whole print-through band for ten multiplies per channel.</p>
     */
    private static final class PrintThroughShaper {
        private final float highPassPole;
        private final float lowPassRate;
        private final float normalisation;
        private final float[] lowPassState = new float[SHAPER_POLES];
        private float previousInput;
        private float highPassState;

        PrintThroughShaper(int sampleRate) {
            double corner = Math.min(SHAPER_CORNER_HERTZ, sampleRate * 0.40);
            highPassPole = (float) Math.exp(-2.0 * Math.PI * corner / sampleRate);
            lowPassRate = (float) (1.0 - Math.exp(-2.0 * Math.PI * corner / sampleRate));
            normalisation = (float) (1.0 / Math.max(1e-9, peakMagnitude(sampleRate)));
        }

        /** Exact magnitude of the realised cascade at the print-through peak. */
        private double peakMagnitude(int sampleRate) {
            double omega = 2.0 * Math.PI * PEAK_HERTZ / sampleRate;
            double cosine = Math.cos(omega);
            double sine = Math.sin(omega);

            // High pass: H(z) = a*(1 - z^-1) / (1 - a*z^-1).
            double a = highPassPole;
            double numerator = a * Math.hypot(1.0 - cosine, sine);
            double denominator = Math.hypot(1.0 - a * cosine, a * sine);
            double magnitude = numerator / Math.max(1e-12, denominator);

            // Low pass: H(z) = k / (1 - (1-k)*z^-1), applied SHAPER_POLES times.
            double k = lowPassRate;
            double pole = 1.0 - k;
            double lowPass = k / Math.max(1e-12,
                    Math.hypot(1.0 - pole * cosine, pole * sine));
            for (int stage = 0; stage < SHAPER_POLES; stage++) {
                magnitude *= lowPass;
            }
            return magnitude;
        }

        void reset() {
            Arrays.fill(lowPassState, 0f);
            previousInput = 0f;
            highPassState = 0f;
        }

        float process(float input) {
            highPassState = highPassPole * (highPassState + input - previousInput);
            previousInput = input;
            float value = highPassState;
            for (int stage = 0; stage < SHAPER_POLES; stage++) {
                lowPassState[stage] += (value - lowPassState[stage]) * lowPassRate;
                value = lowPassState[stage];
            }
            return value * normalisation;
        }
    }
}
