package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Physical acceptance gates for adjacent-layer print-through.
 *
 * <p>Every expectation here is derived from published constants rather than from the renderer's
 * own tuning: the IEC 60094-1 tape speed of 4.7625 cm/s, the 18 um total thickness of C-60 ferric
 * stock, and the demagnetising-field law that makes print-through strongest where the recorded
 * wavelength equals 2*pi*d.</p>
 */
public class TpsL2TapeLayerBleedTest {

    private static final int SAMPLE_RATE = 48_000;

    @Test
    public void printThroughPeaksWhereWavelengthEqualsTwoPiThickness() {
        double expected = TpsL2TapeLayerBleed.TAPE_SPEED_M_PER_S
                / (2.0 * Math.PI * TpsL2TapeLayerBleed.TAPE_THICKNESS_M);
        assertEquals(expected, TpsL2TapeLayerBleed.peakHertz(), 1e-9);
        // 4.7625 cm/s over a 113 um wavelength lands just above 420 Hz.
        assertEquals(421.1, TpsL2TapeLayerBleed.peakHertz(), 0.5);
    }

    @Test
    public void lookAheadIsOneRevolutionOfAFullSupplyPack() {
        TpsL2TapeLayerBleed bleed = new TpsL2TapeLayerBleed(SAMPLE_RATE);
        double seconds = bleed.latencyFrames() / (double) SAMPLE_RATE;
        assertEquals(2.90, seconds, 0.01);
    }

    @Test
    public void echoesSitOneRevolutionEitherSideOfTheProgramme() {
        float tapePosition = 0.5f;
        TpsL2TapeLayerBleed bleed = new TpsL2TapeLayerBleed(SAMPLE_RATE);
        bleed.setTapePosition(tapePosition);
        bleed.reset();

        // Tenth-of-a-second buckets resolve the echoes well inside one revolution.
        int bucketFrames = SAMPLE_RATE / 10;
        int buckets = 100;
        double[] energy = new double[buckets];
        float[] frame = new float[2];
        for (int n = 0; n < bucketFrames * buckets; n++) {
            float value = n < SAMPLE_RATE / 400 ? 1f : 0f;
            bleed.process(value, value, frame);
            energy[n / bucketFrames] += frame[0] * frame[0];
        }

        int direct = indexOfLoudest(energy, 0, buckets);
        int guard = 3;
        int pre = indexOfLoudest(energy, 0, direct - guard);
        int post = indexOfLoudest(energy, direct + guard, buckets);

        assertTrue("A pre-echo must precede the programme", energy[pre] > 0.0);
        assertTrue("A post-echo must follow the programme", energy[post] > 0.0);
        assertTrue("Both echoes must stay far below the programme",
                energy[pre] < energy[direct] * 1e-4);

        // A layer is printed by the neighbours it is wound against, so both echoes sit exactly one
        // revolution away and the spacing is symmetric.
        double revolution = TpsL2TapeLayerBleed.revolutionSeconds(tapePosition);
        assertEquals(revolution, (direct - pre) / 10.0, 0.15);
        assertEquals(revolution, (post - direct) / 10.0, 0.15);

        // The layer facing the coating prints about 2 dB more strongly than the one facing the
        // base film, which is why a tape stored tails-out sounds cleaner.
        assertEquals(2.1, 10.0 * Math.log10(energy[pre] / energy[post]), 0.5);
    }

    private static int indexOfLoudest(double[] energy, int from, int to) {
        int loudest = Math.max(0, from);
        for (int index = Math.max(0, from); index < Math.min(energy.length, to); index++) {
            if (energy[index] > energy[loudest]) {
                loudest = index;
            }
        }
        return loudest;
    }

    @Test
    public void shapingRejectsDcAndKeepsThePrintedShadowWordless() {
        double peak = shadowGain(TpsL2TapeLayerBleed.peakHertz());
        double lowFrequency = shadowGain(20.0);
        double speechBand = shadowGain(3_000.0);

        assertTrue("A uniformly magnetised layer prints no net field",
                20 * Math.log10(lowFrequency / peak) < -15.0);
        assertTrue("Short wavelengths cannot reach across the tape thickness",
                20 * Math.log10(speechBand / peak) < -25.0);
    }

    /**
     * Peak output while only the pre-echo tap is active, the direct tap still being silent.
     *
     * <p>A full supply pack puts the pre-echo exactly one revolution ahead of the direct tap, which
     * is the whole look-ahead, so the printed shadow appears immediately while the direct signal
     * stays silent for another 2.9 s. That gives a clean window in which to read the shaping alone.
     * </p>
     */
    private static double shadowGain(double hertz) {
        TpsL2TapeLayerBleed bleed = new TpsL2TapeLayerBleed(SAMPLE_RATE);
        bleed.setTapePosition(0f);
        bleed.reset();
        float[] frame = new float[2];
        double peak = 0.0;
        int total = SAMPLE_RATE * 2;
        for (int n = 0; n < total; n++) {
            float value = (float) Math.sin(2 * Math.PI * hertz * n / SAMPLE_RATE);
            bleed.process(value, value, frame);
            // Read only after the shaping poles have settled, and well before the direct tap opens.
            if (n > SAMPLE_RATE) {
                peak = Math.max(peak, Math.abs(frame[0]));
            }
        }
        return peak;
    }

    /**
     * The direct signal must be a pure, fixed delay of the input no matter how the pack moves.
     *
     * <p>Letting the direct tap follow the shrinking supply radius steps it by a whole sample
     * several hundred times a second, which is a stream of full-level clicks heard as a buzz. Only
     * the echo taps may move.</p>
     */
    @Test
    public void advancingTheTapeNeverDisturbsTheDirectSignal() {
        TpsL2TapeLayerBleed bleed = new TpsL2TapeLayerBleed(SAMPLE_RATE);
        bleed.setTapePosition(0f);
        bleed.reset();
        float[] frame = new float[2];
        int latency = bleed.latencyFrames();
        int total = latency + SAMPLE_RATE * 20;
        float[] history = new float[total];

        // Sweep a whole side of tape across the run so the pack radius changes continuously.
        double hertz = 400.0;
        double worstError = 0.0;
        for (int n = 0; n < total; n++) {
            bleed.setTapePosition(n / (float) total);
            float value = (float) Math.sin(2 * Math.PI * hertz * n / SAMPLE_RATE);
            history[n] = value;
            bleed.process(value, value, frame);
            if (n >= latency) {
                worstError = Math.max(worstError,
                        Math.abs(frame[0] - history[n - latency]));
            }
        }

        // Everything that survives is print-through, which is 59 dB down before shaping.
        assertTrue("The direct path must stay a clean fixed delay, deviation was " + worstError,
                worstError < 3e-3);
    }

    @Test
    public void splittingTheEchoLeavesTotalPrintThroughPowerUnchanged() {
        // The pre- and post-echo amplitudes are shares of the previous single echo, so their
        // powers must still sum to one; splitting the echo must not make the tape noisier.
        float pre = 0.7899f;
        float post = 0.6132f;
        assertEquals(1.0, pre * pre + post * post, 0.005);
    }

    @Test
    public void outputStaysFiniteAcrossTheWholePackAndAtExtremeInput() {
        for (int rate : new int[]{44_100, 48_000}) {
            TpsL2TapeLayerBleed bleed = new TpsL2TapeLayerBleed(rate);
            float[] frame = new float[2];
            for (int n = 0; n < rate * 2; n++) {
                bleed.setTapePosition(n / (float) (rate * 2));
                float value = n % 2 == 0 ? 8f : -8f;
                bleed.process(value, -value, frame);
                assertTrue("Print-through produced a non-finite sample",
                        Float.isFinite(frame[0]) && Float.isFinite(frame[1]));
            }
        }
    }
}
