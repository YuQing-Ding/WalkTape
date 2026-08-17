package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds the Dolby model to the NJM2065A that Aiwa actually fitted.
 *
 * <p>JRC's datasheet does not publish the chip's internal topology, so the 32 external components
 * around IC2/IC3 stay underivable. What it does publish is the part's own encode transfer at five
 * levels and frequencies per mode, with min/max windows — a calibration target the published Dolby
 * papers alone cannot supply.</p>
 *
 * <p>These two sources constrain different things and must both be honoured. The papers fix the
 * <em>sub-threshold asymptote</em>, which is what {@code DolbyNoiseReductionDspTest} gates, and that
 * in turn pins the base turnover and the side-chain gain. The datasheet fixes how the band retreats
 * as level rises, which is what the cutoff law and the side-gain knee control. Tuning against the
 * datasheet points alone once produced a model that hit all five and was 7 dB wrong at 600 Hz, so
 * neither test may be relaxed to satisfy the other.</p>
 */
public class DolbyDatasheetTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final float DOLBY_LEVEL = 0.12589254f;

    /**
     * Where the two sources genuinely disagree, and by how much.
     *
     * <p>At 5 kHz the papers put the sub-threshold asymptote at 9.6 dB while the datasheet's -40 dB
     * point wants at least 9.8 dB. A one-pole side chain cannot be above its own asymptote, so about
     * 0.2 dB of the window cannot be reached without breaking the paper curve. The conflict is far
     * inside both sources' own tolerances and is allowed for explicitly rather than hidden by a
     * loose bound.</p>
     */
    private static final double SOURCE_CONFLICT_DB = 0.25;

    @Test
    public void bEncodeMatchesTheDatasheetAcrossLevelAndFrequency() {
        assertWindow(DolbyMode.B, 5_000, 0, -1.2, 1.8);
        assertWindow(DolbyMode.B, 1_400, -15, 0.8, 3.8);
        assertWindow(DolbyMode.B, 1_000, -25, 4.2, 7.2);
        assertWindow(DolbyMode.B, 5_000, -30, 6.7, 9.7);
        assertWindow(DolbyMode.B, 5_000, -40, 9.8, 11.8);
    }

    /**
     * The midrange at low level is where the old fixed-gain tuning was worst and where a wrong
     * Dolby is audible: too little action there is what makes a decoded tape sound dull.
     */
    @Test
    public void theMidrangeAtLowLevelIsNoLongerStarvedOfDolbyAction() {
        double at1k = encodeGainDb(DolbyMode.B, 1_000, -25);
        assertTrue("1 kHz at -25 dB was " + at1k + " dB; the part gives 5.7", at1k > 4.2);
        assertTrue("and must not overshoot into the next window either", at1k < 7.2);
    }

    /**
     * Whatever the absolute curve, encode followed by decode has to give the signal back. This is
     * the property the renderer actually depends on, and it is independent of how the datasheet's
     * decode rows are level-referenced.
     */
    @Test
    public void encodeThenDecodeReturnsTheProgramme() {
        for (DolbyMode mode : new DolbyMode[]{DolbyMode.B, DolbyMode.C}) {
            for (double level : new double[]{0, -20, -40}) {
                float[] audio = tone(1_000, level, SAMPLE_RATE);
                float[] original = audio.clone();
                DolbyNoiseReductionDsp dsp = new DolbyNoiseReductionDsp(SAMPLE_RATE, true);
                dsp.setMode(mode);
                dsp.reset();
                dsp.encode(audio, SAMPLE_RATE, mode);
                dsp.decode(audio, SAMPLE_RATE, mode);
                double error = 20.0 * Math.log10(
                        rms(audio, SAMPLE_RATE / 2) / rms(original, SAMPLE_RATE / 2));
                assertEquals("Complementarity broken for " + mode + " at " + level + " dB",
                        0.0, error, 0.75);
            }
        }
    }

    private static void assertWindow(DolbyMode mode, double hertz, double levelDb,
                                     double min, double max) {
        double gain = encodeGainDb(mode, hertz, levelDb);
        assertTrue(mode + " encode at " + hertz + " Hz, " + levelDb + " dB was " + gain
                        + ", below the datasheet window [" + min + ", " + max + "]",
                gain >= min - SOURCE_CONFLICT_DB);
        assertTrue(mode + " encode at " + hertz + " Hz, " + levelDb + " dB was " + gain
                        + ", above the datasheet window [" + min + ", " + max + "]",
                gain <= max + SOURCE_CONFLICT_DB);
    }

    private static double encodeGainDb(DolbyMode mode, double hertz, double levelDb) {
        DolbyNoiseReductionDsp dsp = new DolbyNoiseReductionDsp(SAMPLE_RATE, true);
        dsp.setMode(mode);
        dsp.reset();
        float[] audio = tone(hertz, levelDb, SAMPLE_RATE * 2);
        double in = rms(audio, SAMPLE_RATE);
        dsp.encode(audio, SAMPLE_RATE * 2, mode);
        return 20.0 * Math.log10(rms(audio, SAMPLE_RATE) / in);
    }

    private static float[] tone(double hertz, double levelDb, int frames) {
        float amplitude = (float) (DOLBY_LEVEL * Math.pow(10.0, levelDb / 20.0));
        float[] audio = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) (Math.sin(2 * Math.PI * hertz * frame / SAMPLE_RATE)
                    * amplitude);
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value;
        }
        return audio;
    }

    private static double rms(float[] stereo, int skipFrames) {
        int frames = stereo.length / 2;
        double sum = 0.0;
        for (int frame = skipFrames; frame < frames; frame++) {
            sum += stereo[frame * 2] * (double) stereo[frame * 2];
        }
        return Math.sqrt(sum / Math.max(1, frames - skipFrames));
    }
}
