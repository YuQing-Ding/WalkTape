package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Gates for the record level control.
 *
 * <p>A coating has one fixed maximum output level, so where the recordist set the level decides
 * how much of the music sits under it. Without this stage the renderer fed the source file's own
 * level straight at the coating: a master peaking at 0 dBFS lands 18 dB above reference flux,
 * where Sony CHF measures 28 per cent distortion and nothing is left of the tape's dynamic range.
 * </p>
 */
public class RecordLevelTest {

    private static final int RATE = 48_000;
    private static final double REFERENCE = TapeStockProfile.REFERENCE_FLUX_LEVEL;

    @Test
    public void everyLevelTurnsFullScaleDownOntoTheTape() {
        for (RecordLevelProfile level : RecordLevelProfile.availableProfiles()) {
            assertTrue(level.name + " must attenuate; a tape was never recorded at source level",
                    level.recordGainDb() < 0f);
            // Reference flux stands at -18 dBFS, so peaks land where the label says they do.
            assertEquals(level.name + " peak placement",
                    level.peakOverReferenceDb, level.recordGainDb() + 18f, 0.001f);
        }
    }

    @Test
    public void standardIsTheDefaultAndUnknownIdsFallBackToIt() {
        assertEquals(RecordLevelProfile.STANDARD, RecordLevelProfile.standard().id);
        assertEquals(RecordLevelProfile.STANDARD, RecordLevelProfile.forId("nonsense").id);
        assertEquals(RecordLevelProfile.STANDARD, RecordLevelProfile.forId(null).id);
        assertEquals(6f, RecordLevelProfile.standard().peakOverReferenceDb, 0.001f);
    }

    @Test
    public void recordLevelChangesHowHardTheTapeIsDrivenNotHowLoudItPlays() {
        // Well below the knee at every setting, and far enough above the hiss floor that only
        // programme is being compared.
        double moderate = REFERENCE * 0.5;
        double conservative =
                rms(RecordLevelProfile.forId(RecordLevelProfile.CONSERVATIVE), moderate);
        double saturated = rms(RecordLevelProfile.forId(RecordLevelProfile.SATURATED), moderate);
        assertEquals("Programme below the knee must play back at one level, whatever the setting",
                0.0, 20 * Math.log10(saturated / conservative), 0.5);
    }

    /**
     * Hiss follows the record level, which is the real trade the control makes.
     *
     * <p>A tape recorded quietly has to be played louder, and that lifts the coating's own noise
     * with it. Recording hot buys a better signal-to-noise ratio and pays for it in headroom;
     * that trade is the whole reason the control exists.</p>
     */
    @Test
    public void aQuietlyRecordedTapePlayedLoudCarriesMoreAudibleHiss() {
        double conservative = silenceRms(RecordLevelProfile.forId(RecordLevelProfile.CONSERVATIVE));
        double saturated = silenceRms(RecordLevelProfile.forId(RecordLevelProfile.SATURATED));
        double differenceDb = 20 * Math.log10(conservative / saturated);
        // The two settings are 11 dB apart in record gain, so their replay make-up differs by 11.
        assertEquals(11.0, differenceDb, 1.0);
    }

    private static double silenceRms(RecordLevelProfile level) {
        TapeMediumDsp tape = new TapeMediumDsp(RATE, TapeStockProfile.sonyChf1978(), 71L);
        tape.setRecordLevel(level);
        float[] silence = new float[RATE * 2];
        tape.process(silence, RATE);
        return rms(silence);
    }

    @Test
    public void aHotterRecordingCompressesLoudProgrammeMore() {
        double loud = REFERENCE * 4.0; // +12 dB, an ordinary peak in a modern master
        double conservative = rms(RecordLevelProfile.forId(RecordLevelProfile.CONSERVATIVE), loud);
        double standard = rms(RecordLevelProfile.forId(RecordLevelProfile.STANDARD), loud);
        double saturated = rms(RecordLevelProfile.forId(RecordLevelProfile.SATURATED), loud);

        assertTrue("A hotter recording must squash peaks harder", standard < conservative);
        assertTrue("The overdriven setting must squash them hardest", saturated < standard);
    }

    @Test
    public void defaultSettingKeepsAModernMasterOutOfGrossDistortion() {
        // Peaks at 0 dBFS. Straight into the coating this measured 28 per cent distortion; with
        // the level control in its default position the tape stays in the range a real recording
        // occupied.
        TapeStockProfile chf = TapeStockProfile.sonyChf1978();
        double thd = thd(RecordLevelProfile.standard(), chf, 1.0);
        assertTrue("Distortion at a 0 dBFS peak was " + (thd * 100) + " per cent",
                thd < 0.12);
    }

    @Test
    public void unsetRecordLevelLeavesTheCoatingMeasuredOnItsOwnTerms() {
        // The published MOL, SOL and bias-noise gates construct the medium directly, so the
        // default has to be unity or every one of those measurements would shift.
        TapeMediumDsp tape = new TapeMediumDsp(RATE, TapeStockProfile.sonyChf1978(), 5L);
        float[] audio = sine(400f, (float) REFERENCE, RATE);
        tape.process(audio, RATE / 2);
        double withoutLevel = rms(audio);

        TapeMediumDsp explicit = new TapeMediumDsp(RATE, TapeStockProfile.sonyChf1978(), 5L);
        explicit.setRecordLevel(null);
        float[] second = sine(400f, (float) REFERENCE, RATE);
        explicit.process(second, RATE / 2);
        assertEquals(withoutLevel, rms(second), withoutLevel * 0.001);
    }

    private static double thd(RecordLevelProfile level, TapeStockProfile stock, double amplitude) {
        TapeMediumDsp tape = new TapeMediumDsp(RATE, stock, 11L);
        tape.setRecordLevel(level);
        int window = RATE / 4;
        float[] audio = sine(400f, (float) amplitude, window * 3);
        tape.process(audio, window * 3);

        double[] harmonic = new double[6];
        for (int h = 1; h <= 5; h++) {
            double real = 0;
            double imaginary = 0;
            double omega = 2 * Math.PI * 400 * h / RATE;
            for (int n = 0; n < window; n++) {
                float value = audio[(window + n) * 2];
                real += value * Math.cos(omega * n);
                imaginary += value * Math.sin(omega * n);
            }
            harmonic[h] = 2 * Math.hypot(real, imaginary) / window;
        }
        double total = 0;
        for (int h = 2; h <= 5; h++) {
            total += harmonic[h] * harmonic[h];
        }
        return Math.sqrt(total) / Math.max(1e-12, harmonic[1]);
    }

    private static double rms(RecordLevelProfile level, double amplitude) {
        TapeMediumDsp tape = new TapeMediumDsp(RATE, TapeStockProfile.sonyChf1978(), 23L);
        tape.setRecordLevel(level);
        float[] audio = sine(400f, (float) amplitude, RATE);
        tape.process(audio, RATE);
        return rms(audio);
    }

    private static double rms(float[] stereo) {
        double sum = 0;
        int frames = stereo.length / 2;
        int from = frames / 2;
        for (int frame = from; frame < frames; frame++) {
            sum += stereo[frame * 2] * stereo[frame * 2];
        }
        return Math.sqrt(sum / (frames - from));
    }

    private static float[] sine(float hertz, float amplitude, int frames) {
        float[] audio = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) (amplitude * Math.sin(2 * Math.PI * hertz * frame / RATE));
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value;
        }
        return audio;
    }
}
