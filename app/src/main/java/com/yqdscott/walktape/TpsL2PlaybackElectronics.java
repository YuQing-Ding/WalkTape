package com.yqdscott.walktape;

/**
 * Revised-model TPS-L2 head, Q101/Q201, CX182 and CX184 signal path.
 *
 * <p>The measured whole-machine response remains the calibration authority in {@link TpsL2Dsp};
 * this stage models only behaviours not contained in a small-signal magnitude trace: transistor
 * asymmetry, finite preamp/output headroom, the 3.9 ohm headphone resistors, common rail residue
 * and programme-dependent battery current. It is intentionally unity at small signal.</p>
 */
final class TpsL2PlaybackElectronics {
    private static final float HEADPHONE_SERIES_OHMS = TpsL2Schematic.value("R801");
    private static final float REFERENCE_HEADPHONE_OHMS = TpsL2Schematic.value("P-HP-LOAD");
    private static final float LOAD_NORMALISATION = (REFERENCE_HEADPHONE_OHMS
            + HEADPHONE_SERIES_OHMS) / REFERENCE_HEADPHONE_OHMS;

    private final Channel left;
    private final Channel right;
    private final float envelopeAttack;
    private final float envelopeRelease;
    private float programmeEnvelope;

    TpsL2PlaybackElectronics(int sampleRate) {
        left = new Channel(sampleRate, 0.0075f);
        right = new Channel(sampleRate, -0.0062f);
        envelopeAttack = timeCoefficient(sampleRate, 0.0018f);
        envelopeRelease = timeCoefficient(sampleRate, 0.072f);
        reset();
    }

    void beginFrame(float leftInput, float rightInput) {
        updateEnvelope(Math.max(Math.abs(leftInput), Math.abs(rightInput)));
    }

    float processLeft(float input, float railRipple, float railScale) {
        return left.process(input, railRipple, railScale);
    }

    float processRight(float input, float railRipple, float railScale) {
        return right.process(input, railRipple * 0.91f, railScale);
    }

    float programmePowerLoad() {
        float bounded = Math.min(1f, programmeEnvelope * 1.35f);
        return bounded * bounded;
    }

    void reset() {
        left.reset();
        right.reset();
        programmeEnvelope = 0f;
    }

    private void updateEnvelope(float input) {
        float magnitude = input;
        float rate = magnitude > programmeEnvelope ? envelopeAttack : envelopeRelease;
        programmeEnvelope += (magnitude - programmeEnvelope) * rate;
    }

    private static float timeCoefficient(int sampleRate, float seconds) {
        return 1f - (float) Math.exp(-1.0 / Math.max(1.0, sampleRate * seconds));
    }

    private static final class Channel {
        private final float asymmetry;
        private final float dcPole;
        private float dcInput;
        private float dcOutput;

        Channel(int sampleRate, float asymmetry) {
            this.asymmetry = asymmetry;
            dcPole = (float) Math.exp(-Math.PI * 2.0 * 7.5 / sampleRate);
        }

        float process(float input, float railRipple, float railScale) {
            // Revised units place a 2SC2458 in front of CX182. The even-order term is small, as a
            // correctly biased 0.7 V transistor stage is very nearly linear at playback level.
            float squared = input * input;
            float preamp = input + asymmetry * squared - input * squared * 0.012f;
            float blocked = preamp - dcInput + dcPole * dcOutput;
            dcInput = preamp;
            dcOutput = blocked;

            // CX184 drives each jack through 3.9 ohms. Normalising the documented 35 ohm test
            // load makes small-signal gain unity while retaining rail-dependent peak headroom.
            float loaded = (blocked + railRipple) * (REFERENCE_HEADPHONE_OHMS
                    / (REFERENCE_HEADPHONE_OHMS + HEADPHONE_SERIES_OHMS))
                    * LOAD_NORMALISATION;
            float headroom = 0.90f * railScale;
            float magnitude = Math.abs(loaded);
            if (magnitude <= headroom * 0.76f) {
                return loaded;
            }
            float knee = headroom * 0.76f;
            float excess = magnitude - knee;
            float available = Math.max(0.025f, headroom - knee);
            float softened = knee + excess / (1f + excess / available);
            return Math.copySign(Math.min(headroom, softened), loaded);
        }

        void reset() {
            dcInput = 0f;
            dcOutput = 0f;
        }
    }
}
