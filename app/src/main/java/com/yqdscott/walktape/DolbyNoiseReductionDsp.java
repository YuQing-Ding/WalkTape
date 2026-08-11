package com.yqdscott.walktape;

/**
 * Realtime complementary Dolby B/C record-and-replay model.
 *
 * <p>The topology follows Ray Dolby's published dual-path papers: B uses one 10 dB
 * sliding-band side chain, while C cascades two staggered 10 dB stages in reverse order on
 * replay.  C also includes the published 20 kHz/Q=1 spectral-skew network and the 50/70 us
 * antisaturation network.  Each replay stage is driven from its decoded output (the feedback
 * arrangement in the original block diagrams), rather than being a static EQ preset.</p>
 *
 * <p>All state and coefficient tables are allocated by the constructor.  Processing performs no
 * allocation and OFF bypasses the processor sample-for-sample.</p>
 */
final class DolbyNoiseReductionDsp {
    private static final double TWO_PI = Math.PI * 2.0;
    private static final float SIDE_GAIN = 2.1622776602f; // 10 dB total at the band limit.
    private static final float DIGITAL_DOLBY_LEVEL = 0.12589254f; // -18 dBFS alignment.
    private static final int CONTROL_TABLE_STEPS = 256;
    private static final float CONTROL_CUTOFF_RANGE = 255f;

    private final boolean supported;
    private final DynamicStage bLeft;
    private final DynamicStage bRight;
    private final DynamicStage cHighLeft;
    private final DynamicStage cHighRight;
    private final DynamicStage cLowLeft;
    private final DynamicStage cLowRight;
    private final ComplementaryBiquad cSkewLeft;
    private final ComplementaryBiquad cSkewRight;
    private final ComplementaryFirstOrder cAntisaturationLeft;
    private final ComplementaryFirstOrder cAntisaturationRight;

    private volatile DolbyMode requestedMode = DolbyMode.OFF;
    private DolbyMode activeMode = DolbyMode.OFF;

    DolbyNoiseReductionDsp(int sampleRate, boolean supported) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        this.supported = supported;

        // The 1.70 kHz effective turnover reproduces the B paper's sub-threshold curve:
        // approximately 3/6/8.5/9.6 dB at 0.6/1.2/2.4/5 kHz.
        bLeft = new DynamicStage(sampleRate, 1_700f, DIGITAL_DOLBY_LEVEL,
                false, 0.0060f, 0.075f);
        bRight = new DynamicStage(sampleRate, 1_700f, DIGITAL_DOLBY_LEVEL,
                false, 0.0060f, 0.075f);

        // C's two stages use the published 375 Hz turnover.  Their action is staggered by
        // 20 dB: the low-level stage reaches its control region first.
        cHighLeft = new DynamicStage(sampleRate, 375f, DIGITAL_DOLBY_LEVEL,
                true, 0.0030f, 0.0375f);
        cHighRight = new DynamicStage(sampleRate, 375f, DIGITAL_DOLBY_LEVEL,
                true, 0.0030f, 0.0375f);
        cLowLeft = new DynamicStage(sampleRate, 375f, DIGITAL_DOLBY_LEVEL * 0.1f,
                true, 0.0030f, 0.0375f);
        cLowRight = new DynamicStage(sampleRate, 375f, DIGITAL_DOLBY_LEVEL * 0.1f,
                true, 0.0030f, 0.0375f);

        float skewCentre = Math.min(20_000f, sampleRate * 0.40f);
        cSkewLeft = ComplementaryBiquad.peaking(sampleRate, skewCentre, 1.0f, -12f);
        cSkewRight = ComplementaryBiquad.peaking(sampleRate, skewCentre, 1.0f, -12f);
        cAntisaturationLeft = ComplementaryFirstOrder.analogueShelf(
                sampleRate, 50e-6f, 70e-6f);
        cAntisaturationRight = ComplementaryFirstOrder.analogueShelf(
                sampleRate, 50e-6f, 70e-6f);
        resetState();
    }

    void setMode(DolbyMode mode) {
        requestedMode = supported && mode != null ? mode : DolbyMode.OFF;
    }

    DolbyMode mode() {
        return requestedMode;
    }

    /** Captures one mode for the whole record/tape/replay block. */
    DolbyMode beginBlock() {
        DolbyMode selected = requestedMode;
        if (selected != activeMode) {
            resetState();
            activeMode = selected;
        }
        return activeMode;
    }

    void encode(float[] stereo, int frameCount, DolbyMode mode) {
        validate(stereo, frameCount);
        if (mode == DolbyMode.OFF) {
            return;
        }
        encodeChannel(stereo, frameCount, mode, 0);
        encodeChannel(stereo, frameCount, mode, 1);
    }

    void decode(float[] stereo, int frameCount, DolbyMode mode) {
        validate(stereo, frameCount);
        if (mode == DolbyMode.OFF) {
            return;
        }
        decodeChannel(stereo, frameCount, mode, 0);
        decodeChannel(stereo, frameCount, mode, 1);
    }

    private void encodeChannel(float[] stereo, int frameCount, DolbyMode mode, int channel) {
        if (mode == DolbyMode.B) {
            (channel == 0 ? bLeft : bRight).encodeChannel(stereo, frameCount, channel);
            return;
        }
        ComplementaryBiquad skew = channel == 0 ? cSkewLeft : cSkewRight;
        DynamicStage high = channel == 0 ? cHighLeft : cHighRight;
        ComplementaryFirstOrder antisaturation = channel == 0
                ? cAntisaturationLeft : cAntisaturationRight;
        DynamicStage low = channel == 0 ? cLowLeft : cLowRight;
        skew.encodeChannel(stereo, frameCount, channel);
        high.encodeChannel(stereo, frameCount, channel);
        antisaturation.encodeChannel(stereo, frameCount, channel);
        low.encodeChannel(stereo, frameCount, channel);
    }

    private void decodeChannel(float[] stereo, int frameCount, DolbyMode mode, int channel) {
        if (mode == DolbyMode.B) {
            (channel == 0 ? bLeft : bRight).decodeChannel(stereo, frameCount, channel);
            return;
        }
        DynamicStage low = channel == 0 ? cLowLeft : cLowRight;
        ComplementaryFirstOrder antisaturation = channel == 0
                ? cAntisaturationLeft : cAntisaturationRight;
        DynamicStage high = channel == 0 ? cHighLeft : cHighRight;
        ComplementaryBiquad skew = channel == 0 ? cSkewLeft : cSkewRight;
        low.decodeChannel(stereo, frameCount, channel);
        antisaturation.decodeChannel(stereo, frameCount, channel);
        high.decodeChannel(stereo, frameCount, channel);
        skew.decodeChannel(stereo, frameCount, channel);
    }

    void reset() {
        resetState();
        activeMode = requestedMode;
    }

    private void resetState() {
        bLeft.reset();
        bRight.reset();
        cHighLeft.reset();
        cHighRight.reset();
        cLowLeft.reset();
        cLowRight.reset();
        cSkewLeft.reset();
        cSkewRight.reset();
        cAntisaturationLeft.reset();
        cAntisaturationRight.reset();
    }

    private static void validate(float[] stereo, int frameCount) {
        if (stereo == null || frameCount < 0 || frameCount * 2 > stereo.length) {
            throw new IllegalArgumentException("Invalid stereo frame count");
        }
    }

    /** One complementary sliding-band compressor/expander stage. */
    private static final class DynamicStage {
        private final float[] highPassFeed = new float[CONTROL_TABLE_STEPS + 1];
        private final float[] highPassFeedback = new float[CONTROL_TABLE_STEPS + 1];
        private final float[] highPassFeedDelta = new float[CONTROL_TABLE_STEPS];
        private final float[] highPassFeedbackDelta = new float[CONTROL_TABLE_STEPS];
        private final float inverseReference;
        private final boolean fullWaveDetector;
        private final float attack;
        private final float release;
        private final float sideLimit;

        private float encoderPreviousInput;
        private float encoderHighPass;
        private float decoderPreviousInput;
        private float decoderHighPass;
        private float encoderEnvelope;
        private float decoderEnvelope;

        DynamicStage(int sampleRate,
                     float baseTurnoverHz,
                     float reference,
                     boolean fullWaveDetector,
                     float attackSeconds,
                     float releaseSeconds) {
            inverseReference = 1f / reference;
            this.fullWaveDetector = fullWaveDetector;
            attack = timeCoefficient(sampleRate, attackSeconds);
            release = timeCoefficient(sampleRate, releaseSeconds);
            sideLimit = reference * 0.75f;
            float safeTop = sampleRate * 0.49f;
            for (int index = 0; index <= CONTROL_TABLE_STEPS; index++) {
                float normalised = index / (float) CONTROL_TABLE_STEPS;
                float corner = baseTurnoverHz
                        * (1f + CONTROL_CUTOFF_RANGE * normalised * normalised);
                // Bilinear/prewarped one-pole high pass. Unlike an exponential pole, this can
                // slide all the way towards Nyquist and make boost genuinely negligible at
                // Dolby level while retaining unity band-limit gain.
                double warped = Math.tan(Math.PI * Math.min(safeTop, corner) / sampleRate);
                highPassFeed[index] = (float) (1.0 / (1.0 + warped));
                highPassFeedback[index] = (float) ((warped - 1.0) / (warped + 1.0));
            }
            for (int index = 0; index < CONTROL_TABLE_STEPS; index++) {
                highPassFeedDelta[index] = highPassFeed[index + 1] - highPassFeed[index];
                highPassFeedbackDelta[index] = highPassFeedback[index + 1]
                        - highPassFeedback[index];
            }
        }

        void encodeChannel(float[] stereo, int frameCount, int channel) {
            float previousInput = encoderPreviousInput;
            float previousHighPass = encoderHighPass;
            float envelope = encoderEnvelope;
            float[] feeds = highPassFeed;
            float[] feedbacks = highPassFeedback;
            float[] feedDeltas = highPassFeedDelta;
            float[] feedbackDeltas = highPassFeedbackDelta;
            float localInverseReference = inverseReference;
            float localAttack = attack;
            float localRelease = release;
            float localSideLimit = sideLimit;
            boolean localFullWaveDetector = fullWaveDetector;
            int end = frameCount * 2;
            for (int sample = channel; sample < end; sample += 2) {
                float input = stereo[sample];
                float normalised = envelope * localInverseReference;
                float position = normalised < 1f
                        ? normalised * CONTROL_TABLE_STEPS : CONTROL_TABLE_STEPS;
                int lower = (int) position;
                if (lower >= CONTROL_TABLE_STEPS) {
                    lower = CONTROL_TABLE_STEPS - 1;
                }
                float fraction = position - lower;
                float feed = feeds[lower] + feedDeltas[lower] * fraction;
                float feedback = feedbacks[lower] + feedbackDeltas[lower] * fraction;
                float highPass = feed * (input - previousInput)
                        - feedback * previousHighPass;
                float side = SIDE_GAIN * highPass;
                if (side > localSideLimit) {
                    side = localSideLimit;
                } else if (side < -localSideLimit) {
                    side = -localSideLimit;
                }
                float detector = localFullWaveDetector
                        ? Math.abs(input) : (input > 0f ? input * 2f : 0f);
                envelope += (detector - envelope)
                        * (detector > envelope ? localAttack : localRelease);
                previousInput = input;
                previousHighPass = highPass;
                stereo[sample] = input + side;
            }
            encoderPreviousInput = previousInput;
            encoderHighPass = previousHighPass;
            encoderEnvelope = envelope;
        }

        void decodeChannel(float[] stereo, int frameCount, int channel) {
            float previousInput = decoderPreviousInput;
            float previousHighPass = decoderHighPass;
            float envelope = decoderEnvelope;
            float[] feeds = highPassFeed;
            float[] feedbacks = highPassFeedback;
            float[] feedDeltas = highPassFeedDelta;
            float[] feedbackDeltas = highPassFeedbackDelta;
            float localInverseReference = inverseReference;
            float localAttack = attack;
            float localRelease = release;
            float localSideLimit = sideLimit;
            boolean localFullWaveDetector = fullWaveDetector;
            int end = frameCount * 2;
            for (int sample = channel; sample < end; sample += 2) {
                float encoded = stereo[sample];
                float normalised = envelope * localInverseReference;
                float position = normalised < 1f
                        ? normalised * CONTROL_TABLE_STEPS : CONTROL_TABLE_STEPS;
                int lower = (int) position;
                if (lower >= CONTROL_TABLE_STEPS) {
                    lower = CONTROL_TABLE_STEPS - 1;
                }
                float fraction = position - lower;
                float feed = feeds[lower] + feedDeltas[lower] * fraction;
                float feedback = feedbacks[lower] + feedbackDeltas[lower] * fraction;
                float highPassOffset = -feed * previousInput
                        - feedback * previousHighPass;
                float decoded = (encoded - SIDE_GAIN * highPassOffset)
                        / (1f + SIDE_GAIN * feed);
                float unclippedSide = SIDE_GAIN * (feed * decoded + highPassOffset);
                if (unclippedSide > localSideLimit) {
                    decoded = encoded - localSideLimit;
                } else if (unclippedSide < -localSideLimit) {
                    decoded = encoded + localSideLimit;
                }
                float detector = localFullWaveDetector
                        ? Math.abs(decoded) : (decoded > 0f ? decoded * 2f : 0f);
                envelope += (detector - envelope)
                        * (detector > envelope ? localAttack : localRelease);
                previousInput = decoded;
                previousHighPass = feed * decoded + highPassOffset;
                stereo[sample] = decoded;
            }
            decoderPreviousInput = previousInput;
            decoderHighPass = previousHighPass;
            decoderEnvelope = envelope;
        }

        void reset() {
            encoderPreviousInput = 0f;
            encoderHighPass = 0f;
            decoderPreviousInput = 0f;
            decoderHighPass = 0f;
            encoderEnvelope = 0f;
            decoderEnvelope = 0f;
        }
    }

    /** Exact digital complement of the C-type 50/70 us antisaturation shelf. */
    private static final class ComplementaryFirstOrder {
        private final float b0;
        private final float b1;
        private final float a1;
        private final float inverseB0;
        private float encoderInput1;
        private float encoderOutput1;
        private float decoderInput1;
        private float decoderOutput1;

        private ComplementaryFirstOrder(float b0, float b1, float a1) {
            this.b0 = b0;
            this.b1 = b1;
            this.a1 = a1;
            inverseB0 = 1f / b0;
        }

        static ComplementaryFirstOrder analogueShelf(int sampleRate,
                                                      float zeroSeconds,
                                                      float poleSeconds) {
            float k = 2f * sampleRate;
            float denominator = 1f + k * poleSeconds;
            return new ComplementaryFirstOrder(
                    (1f + k * zeroSeconds) / denominator,
                    (1f - k * zeroSeconds) / denominator,
                    (1f - k * poleSeconds) / denominator);
        }

        void encodeChannel(float[] stereo, int frameCount, int channel) {
            float input1 = encoderInput1;
            float output1 = encoderOutput1;
            float localB0 = b0;
            float localB1 = b1;
            float localA1 = a1;
            int end = frameCount * 2;
            for (int sample = channel; sample < end; sample += 2) {
                float input = stereo[sample];
                float output = localB0 * input + localB1 * input1 - localA1 * output1;
                input1 = input;
                output1 = output;
                stereo[sample] = output;
            }
            encoderInput1 = input1;
            encoderOutput1 = output1;
        }

        void decodeChannel(float[] stereo, int frameCount, int channel) {
            float input1 = decoderInput1;
            float output1 = decoderOutput1;
            float localB1 = b1;
            float localA1 = a1;
            float localInverseB0 = inverseB0;
            int end = frameCount * 2;
            for (int sample = channel; sample < end; sample += 2) {
                float input = stereo[sample];
                float output = (input - localB1 * output1 + localA1 * input1)
                        * localInverseB0;
                output1 = output;
                input1 = input;
                stereo[sample] = output;
            }
            decoderOutput1 = output1;
            decoderInput1 = input1;
        }

        void reset() {
            encoderInput1 = 0f;
            encoderOutput1 = 0f;
            decoderInput1 = 0f;
            decoderOutput1 = 0f;
        }
    }

    /** Minimum-phase 12 dB/Q=1 C-type skew notch with an exact inverse replay section. */
    private static final class ComplementaryBiquad {
        private final float b0;
        private final float b1;
        private final float b2;
        private final float a1;
        private final float a2;
        private final float inverseB0;
        private float encoderInput1;
        private float encoderInput2;
        private float encoderOutput1;
        private float encoderOutput2;
        private float decoderInput1;
        private float decoderInput2;
        private float decoderOutput1;
        private float decoderOutput2;

        private ComplementaryBiquad(float b0, float b1, float b2, float a1, float a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
            inverseB0 = 1f / b0;
        }

        static ComplementaryBiquad peaking(int sampleRate,
                                           float frequency,
                                           float q,
                                           float gainDb) {
            double amplitude = Math.pow(10.0, gainDb / 40.0);
            double omega = TWO_PI * frequency / sampleRate;
            double alpha = Math.sin(omega) / (2.0 * q);
            double a0 = 1.0 + alpha / amplitude;
            return new ComplementaryBiquad(
                    (float) ((1.0 + alpha * amplitude) / a0),
                    (float) (-2.0 * Math.cos(omega) / a0),
                    (float) ((1.0 - alpha * amplitude) / a0),
                    (float) (-2.0 * Math.cos(omega) / a0),
                    (float) ((1.0 - alpha / amplitude) / a0));
        }

        void encodeChannel(float[] stereo, int frameCount, int channel) {
            float input1 = encoderInput1;
            float input2 = encoderInput2;
            float output1 = encoderOutput1;
            float output2 = encoderOutput2;
            float localB0 = b0;
            float localB1 = b1;
            float localB2 = b2;
            float localA1 = a1;
            float localA2 = a2;
            int end = frameCount * 2;
            for (int sample = channel; sample < end; sample += 2) {
                float input = stereo[sample];
                float output = localB0 * input + localB1 * input1 + localB2 * input2
                        - localA1 * output1 - localA2 * output2;
                input2 = input1;
                input1 = input;
                output2 = output1;
                output1 = output;
                stereo[sample] = output;
            }
            encoderInput1 = input1;
            encoderInput2 = input2;
            encoderOutput1 = output1;
            encoderOutput2 = output2;
        }

        void decodeChannel(float[] stereo, int frameCount, int channel) {
            float input1 = decoderInput1;
            float input2 = decoderInput2;
            float output1 = decoderOutput1;
            float output2 = decoderOutput2;
            float localB1 = b1;
            float localB2 = b2;
            float localA1 = a1;
            float localA2 = a2;
            float localInverseB0 = inverseB0;
            int end = frameCount * 2;
            for (int sample = channel; sample < end; sample += 2) {
                float input = stereo[sample];
                float output = (input - localB1 * output1 - localB2 * output2
                        + localA1 * input1 + localA2 * input2) * localInverseB0;
                output2 = output1;
                output1 = output;
                input2 = input1;
                input1 = input;
                stereo[sample] = output;
            }
            decoderOutput1 = output1;
            decoderOutput2 = output2;
            decoderInput1 = input1;
            decoderInput2 = input2;
        }

        void reset() {
            encoderInput1 = 0f;
            encoderInput2 = 0f;
            encoderOutput1 = 0f;
            encoderOutput2 = 0f;
            decoderInput1 = 0f;
            decoderInput2 = 0f;
            decoderOutput1 = 0f;
            decoderOutput2 = 0f;
        }
    }

    private static float timeCoefficient(int sampleRate, float seconds) {
        return 1f - (float) Math.exp(-1.0 / (sampleRate * seconds));
    }

}


