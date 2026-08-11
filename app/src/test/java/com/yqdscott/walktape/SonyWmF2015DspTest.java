package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SonyWmF2015DspTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final double TWO_PI = Math.PI * 2.0;

    @Test
    public void factoryServiceFactsAndInferredTransportTargetStaySeparated() {
        TapeMachineProfile profile = TapeMachineProfile.sonyWmF2015Reference();
        assertEquals(TapeMachineProfile.SONY_WM_F2015, profile.id);
        assertEquals("40 Hz — 11.6 kHz MODEL", profile.frequencySpec(false));
        assertEquals("40 Hz — 15 kHz", profile.frequencySpec(true));
        assertTrue(profile.calibrationBasis.contains("SONY SERVICE"));
        assertTrue(profile.transport.contains("DUAL BELT"));
        assertTrue(profile.usesTapeTypeSelector());
        assertEquals(0.340f, SonyWmF2015Dsp.nominalWowFlutterRmsPercent(), 0.001f);
        assertEquals(0.5f, SonyWmF2015Dsp.serviceTapeSpeedTolerancePercent(), 0f);
        assertEquals(1.5f,
                SonyWmF2015Dsp.serviceBeginningToEndDifferencePercent(), 0f);
        assertTrue("The UI must identify W&F as a model, not a published Sony measurement",
                profile.wowFlutterSpec.contains("MODEL"));
    }

    @Test
    public void manualTapeSelectorReachesTheDocumentedCatalogueBandwidth() {
        double normal40 = responseDb(40f, false);
        double chrome40 = responseDb(40f, true);
        double normal11k6 = responseDb(11_600f, false);
        double normal15k = responseDb(15_000f, false);
        double chrome15k = responseDb(15_000f, true);

        assertTrue("Normal 40 Hz endpoint: " + normal40,
                normal40 > -6.0 && normal40 < 1.0);
        assertTrue("Cr/Metal 40 Hz endpoint: " + chrome40,
                chrome40 > -6.0 && chrome40 < 1.0);
        assertTrue("Normal model endpoint: " + normal11k6,
                normal11k6 > -5.0 && normal11k6 < 0.5);
        assertTrue("Normal path should be rolled off by 15 kHz: " + normal15k,
                normal15k < -4.5);
        assertTrue("Cr/Metal 15 kHz endpoint: " + chrome15k,
                chrome15k > -5.0 && chrome15k < 0.5);
        assertTrue("The physical selector must extend useful treble",
                chrome15k - normal15k > 2.0);
    }

    @Test
    public void twoBeltTransportMakesPianoPitchAudibleWithoutBecomingAFault() {
        SonyWmF2015Dsp renderer = new SonyWmF2015Dsp(
                SAMPLE_RATE, 2015L, true, false);
        float[] tone = sine(1_000f, 0.08f, SAMPLE_RATE * 32);
        renderer.process(tone, SAMPLE_RATE * 32);

        double measured = pitchFlutterRms(tone, 0, 1_000f,
                SAMPLE_RATE * 10, SAMPLE_RATE * 18);
        assertEquals(SonyWmF2015Dsp.nominalWowFlutterRmsPercent() / 100.0,
                measured, 0.00055);
        double centsRms = measured * 1_200.0 / Math.log(2.0);
        assertTrue("Sustained piano needs audible healthy-transport motion: " + centsRms,
                centsRms > 4.8);
        assertTrue("Healthy motion must remain below the factory static speed tolerance",
                measured * 100.0 < SonyWmF2015Dsp.serviceTapeSpeedTolerancePercent());
    }

    @Test
    public void la4570MachineNoiseRemainsBelowTheSeparateTapeMedium() {
        SonyWmF2015Dsp renderer = new SonyWmF2015Dsp(
                SAMPLE_RATE, 0x4570L, false, true);
        float[] silence = new float[SAMPLE_RATE * 2 * 4];
        renderer.process(silence, SAMPLE_RATE * 4);
        double levelDb = 20.0 * Math.log10(rms(silence, 0, SAMPLE_RATE));

        assertEquals(SonyWmF2015Dsp.integratedMachineNoiseFloorDb(), levelDb, 1.5);
        assertTrue("Machine electronics must not duplicate the tape's stronger hiss",
                levelDb < TapeMediumDsp.renderedHissFloorDb(
                        TapeStockProfile.sonyChf1978()) - 7.0);
    }

    @Test
    public void selectorTransitionIsFiniteAndClickFree() {
        SonyWmF2015Dsp renderer = new SonyWmF2015Dsp(
                SAMPLE_RATE, 2015L, false, false);
        float[] first = sine(6_300f, 0.10f, 2_048);
        renderer.process(first, 2_048);
        renderer.setHighTape(true);
        float[] second = sineWithOffset(6_300f, 0.10f, 2_048, 2_048);
        renderer.process(second, 2_048);

        float previous = first[first.length - 2];
        float maximumStep = 0f;
        for (int frame = 0; frame < second.length / 2; frame++) {
            float value = second[frame * 2];
            assertTrue(Float.isFinite(value));
            maximumStep = Math.max(maximumStep, Math.abs(value - previous));
            previous = value;
        }
        assertTrue("Tape selector invented a click: " + maximumStep,
                maximumStep < 0.12f);
    }

    @Test
    public void rendererIsDeterministicAcrossBlockBoundaries() {
        SonyWmF2015Dsp first = new SonyWmF2015Dsp(
                SAMPLE_RATE, 1990L, true, true);
        SonyWmF2015Dsp second = new SonyWmF2015Dsp(
                SAMPLE_RATE, 1990L, true, true);
        first.setHighTape(true);
        second.setHighTape(true);
        first.reset();
        second.reset();

        float[] source = sine(440f, 0.20f, 16_384);
        float[] firstBlock = new float[4_096 * 2];
        float[] secondBlock = new float[(16_384 - 4_096) * 2];
        System.arraycopy(source, 0, firstBlock, 0, firstBlock.length);
        System.arraycopy(source, firstBlock.length,
                secondBlock, 0, secondBlock.length);
        first.process(firstBlock, 4_096);
        first.process(secondBlock, 16_384 - 4_096);
        float[] split = new float[source.length];
        System.arraycopy(firstBlock, 0, split, 0, firstBlock.length);
        System.arraycopy(secondBlock, 0, split,
                firstBlock.length, secondBlock.length);

        float[] whole = source.clone();
        second.process(whole, 16_384);
        for (int sample = 0; sample < split.length; sample++) {
            assertTrue(Float.isFinite(split[sample]));
            assertEquals(split[sample], whole[sample], 0f);
        }
    }

    @Test
    public void factoryCreatesTheDedicatedF2015RendererAndKeepsUnknownIdsSafe() {
        assertTrue(TapeMachineDspFactory.create(
                TapeMachineProfile.sonyWmF2015Reference(), SAMPLE_RATE)
                instanceof SonyWmF2015Dsp);
        assertEquals(TapeMachineProfile.SONY_WM_F2015,
                TapeMachineProfile.forId(TapeMachineProfile.SONY_WM_F2015).id);
        assertEquals(TapeMachineProfile.SONY_TPS_L2,
                TapeMachineProfile.forId("not-a-machine").id);
    }

    private static double responseDb(float frequency, boolean highTape) {
        double reference = renderedRms(1_000f, highTape);
        return 20.0 * Math.log10(renderedRms(frequency, highTape) / reference);
    }

    private static double renderedRms(float frequency, boolean highTape) {
        SonyWmF2015Dsp renderer = new SonyWmF2015Dsp(
                SAMPLE_RATE, 7L, false, false);
        renderer.setHighTape(highTape);
        renderer.reset();
        float[] audio = sine(frequency, 0.05f, SAMPLE_RATE);
        renderer.process(audio, SAMPLE_RATE);
        return rms(audio, 0, SAMPLE_RATE / 4);
    }

    private static float[] sine(float frequency, float amplitude, int frames) {
        return sineWithOffset(frequency, amplitude, frames, 0);
    }

    private static float[] sineWithOffset(float frequency,
                                          float amplitude,
                                          int frames,
                                          int offsetFrames) {
        float[] audio = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) (Math.sin(TWO_PI * frequency
                    * (frame + offsetFrames) / SAMPLE_RATE) * amplitude);
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value;
        }
        return audio;
    }

    private static double rms(float[] stereo, int channel, int skipFrames) {
        int frames = stereo.length / 2;
        double sum = 0.0;
        int count = 0;
        for (int frame = Math.min(skipFrames, frames); frame < frames; frame++) {
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
