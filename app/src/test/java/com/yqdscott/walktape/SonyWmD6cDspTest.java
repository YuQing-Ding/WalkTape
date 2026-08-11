package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SonyWmD6cDspTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final double TWO_PI = Math.PI * 2.0;

    @Test
    public void profilePreservesThePublishedProfessionalTargets() {
        TapeMachineProfile profile = TapeMachineProfile.sonyWmD6cReference();
        assertEquals(TapeMachineProfile.SONY_WM_D6C, profile.id);
        assertEquals("40 Hz — 15 kHz", profile.frequencySpec(false));
        assertEquals("40 Hz — 15 kHz", profile.frequencySpec(true));
        assertTrue(profile.transport.contains("QUARTZ"));
        assertTrue(profile.calibrationBasis.contains("SONY SERVICE"));
        assertTrue(profile.usesTapeTypeSelector());
        assertEquals(0.040f, SonyWmD6cDsp.nominalWowFlutterRmsPercent(), 0.0001f);
        assertEquals(0.14f, SonyWmD6cDsp.dinWowFlutterPercent(), 0f);
        assertEquals(0.30f, SonyWmD6cDsp.speedCalibrationTolerancePercent(), 0f);
    }

    @Test
    public void neutralReplayPathMeetsBothPublishedEndpoints() {
        double low = responseDb(40f, true, true);
        double high = responseDb(15_000f, true, true);
        assertTrue("40 Hz endpoint: " + low, low > -3.8 && low < -1.8);
        assertTrue("15 kHz endpoint: " + high, high > -3.8 && high < -2.0);
    }

    @Test
    public void wrongManualSelectorProducesThePhysicalSeventyVsOneTwentyUsMismatch() {
        double correct = responseDb(10_000f, true, true);
        double wrong = responseDb(10_000f, false, true);
        assertTrue("Normal selector on a high-position tape must darken treble: "
                + (wrong - correct), wrong - correct < -3.0);
    }

    @Test
    public void quartzTransportKeepsPianoPitchInsideThePublishedWrmsTarget() {
        SonyWmD6cDsp renderer = new SonyWmD6cDsp(
                SAMPLE_RATE, 1984L, true, false, true);
        float[] tone = sine(1_000f, 0.08f, SAMPLE_RATE * 30, 0);
        renderer.process(tone, SAMPLE_RATE * 30);
        double measured = pitchFlutterRms(tone, 0, 1_000f,
                SAMPLE_RATE * 8, SAMPLE_RATE * 18);
        assertEquals(SonyWmD6cDsp.nominalWowFlutterRmsPercent() / 100.0,
                measured, 0.00016);
        assertTrue(measured * 100.0 < SonyWmD6cDsp.speedCalibrationTolerancePercent());
    }

    @Test
    public void machineNoiseStaysWellBelowTheSeparateTapeStock() {
        SonyWmD6cDsp renderer = new SonyWmD6cDsp(
                SAMPLE_RATE, 6L, false, true, true);
        float[] silence = new float[SAMPLE_RATE * 2 * 4];
        renderer.process(silence, SAMPLE_RATE * 4);
        double levelDb = 20.0 * Math.log10(rms(silence, 0, SAMPLE_RATE));
        assertEquals(SonyWmD6cDsp.machineNoiseFloorDb(), levelDb, 1.2);
        assertTrue(levelDb < TapeMediumDsp.renderedHissFloorDb(
                TapeStockProfile.tdkMaX1990()) - 12.0);
    }

    @Test
    public void rendererIsDeterministicAcrossBlockBoundariesAndFactoryDedicated() {
        SonyWmD6cDsp splitRenderer = new SonyWmD6cDsp(
                SAMPLE_RATE, 84L, true, true, true);
        SonyWmD6cDsp wholeRenderer = new SonyWmD6cDsp(
                SAMPLE_RATE, 84L, true, true, true);
        float[] source = sine(440f, 0.2f, 16_384, 0);
        float[] first = new float[4_096 * 2];
        float[] second = new float[source.length - first.length];
        System.arraycopy(source, 0, first, 0, first.length);
        System.arraycopy(source, first.length, second, 0, second.length);
        splitRenderer.process(first, first.length / 2);
        splitRenderer.process(second, second.length / 2);
        float[] split = new float[source.length];
        System.arraycopy(first, 0, split, 0, first.length);
        System.arraycopy(second, 0, split, first.length, second.length);
        float[] whole = source.clone();
        wholeRenderer.process(whole, whole.length / 2);
        for (int sample = 0; sample < whole.length; sample++) {
            assertTrue(Float.isFinite(split[sample]));
            assertEquals(split[sample], whole[sample], 0f);
        }
        assertTrue(TapeMachineDspFactory.create(
                TapeMachineProfile.sonyWmD6cReference(), SAMPLE_RATE)
                instanceof SonyWmD6cDsp);
    }

    private static double responseDb(float frequency,
                                     boolean selectorHigh,
                                     boolean tapeStockHigh) {
        double reference = renderedRms(1_000f, selectorHigh, tapeStockHigh);
        return 20.0 * Math.log10(renderedRms(frequency, selectorHigh, tapeStockHigh)
                / reference);
    }

    private static double renderedRms(float frequency,
                                      boolean selectorHigh,
                                      boolean tapeStockHigh) {
        SonyWmD6cDsp renderer = new SonyWmD6cDsp(
                SAMPLE_RATE, 7L, false, false, tapeStockHigh);
        renderer.setHighTape(selectorHigh);
        renderer.reset();
        float[] audio = sine(frequency, 0.05f, SAMPLE_RATE, 0);
        renderer.process(audio, SAMPLE_RATE);
        return rms(audio, 0, SAMPLE_RATE / 4);
    }

    private static float[] sine(float frequency, float amplitude, int frames, int offset) {
        float[] audio = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) (Math.sin(TWO_PI * frequency
                    * (frame + offset) / SAMPLE_RATE) * amplitude);
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value;
        }
        return audio;
    }

    private static double rms(float[] stereo, int channel, int skipFrames) {
        double sum = 0.0;
        int count = 0;
        for (int frame = Math.min(skipFrames, stereo.length / 2);
             frame < stereo.length / 2; frame++) {
            float value = stereo[frame * 2 + channel];
            sum += value * value;
            count++;
        }
        return Math.sqrt(sum / Math.max(1, count));
    }

    private static double pitchFlutterRms(float[] stereo,
                                          int channel,
                                          float toneFrequency,
                                          int startFrame,
                                          int windowFrames) {
        int end = Math.min(stereo.length / 2, startFrame + windowFrames);
        double nominalPeriod = SAMPLE_RATE / toneFrequency;
        double previousCrossing = Double.NaN;
        double mean = 0.0;
        double squaredDeviations = 0.0;
        int periods = 0;
        float previous = stereo[startFrame * 2 + channel];
        for (int frame = startFrame + 1; frame < end; frame++) {
            float current = stereo[frame * 2 + channel];
            if (previous <= 0f && current > 0f) {
                double fraction = -previous / (current - previous);
                double crossing = frame - 1 + fraction;
                if (!Double.isNaN(previousCrossing)) {
                    double period = crossing - previousCrossing;
                    double pitchError = nominalPeriod / period - 1.0;
                    periods++;
                    double delta = pitchError - mean;
                    mean += delta / periods;
                    squaredDeviations += delta * (pitchError - mean);
                }
                previousCrossing = crossing;
            }
            previous = current;
        }
        return Math.sqrt(squaredDeviations / Math.max(1, periods));
    }
}
