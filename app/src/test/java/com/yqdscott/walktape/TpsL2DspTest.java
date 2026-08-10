package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TpsL2DspTest {

    private static final int SAMPLE_RATE = 48_000;

    @Test
    public void publishedCalibrationTargetsAreEncodedExactly() {
        assertEquals(0.219f, TpsL2Dsp.nominalWowFlutterRmsPercent(), 0.0005f);
        assertEquals(-67f, TpsL2Dsp.referenceNoiseFloorDb(), 0.01f);
        assertEquals(1.2f, TpsL2Dsp.startupSpeedDeficitPercent(), 0.001f);
        assertEquals(15f, TpsL2Dsp.startupSettledSeconds(), 0.001f);
        assertEquals(2.78f, TpsL2Dsp.camWowPeriodSeconds(), 0.02f);
    }

    @Test
    public void rendererKeepsLeftAndRightChannelsIndependent() {
        TpsL2Dsp renderer = cleanRenderer(false);
        float[] audio = sine(1_000f, 0.12f, SAMPLE_RATE);
        for (int frame = 0; frame < SAMPLE_RATE; frame++) {
            audio[frame * 2 + 1] = 0f;
        }

        renderer.process(audio, SAMPLE_RATE);

        assertTrue(rms(audio, 0, SAMPLE_RATE / 4) > 0.05);
        assertTrue(rms(audio, 1, SAMPLE_RATE / 4) < 0.000001);
    }

    @Test
    public void lowPositionTracksDigitisedArchiveResponse() {
        assertResponseDb(20f, -11.2, 1.3);
        assertResponseDb(40f, -3.1, 0.7);
        assertResponseDb(100f, 1.4, 0.55);
        assertResponseDb(3_150f, -1.2, 0.55);
        assertResponseDb(8_000f, -1.7, 0.7);
        assertResponseDb(10_000f, -4.4, 0.7);
        assertResponseDb(12_500f, -10.3, 1.1);
        assertResponseDb(16_000f, -20.3, 1.1);
    }

    @Test
    public void highPositionTracksMeasuredBroadShelfRatherThanBellBoost() {
        assertHighDeltaDb(100f, 0.0, 0.15);
        assertHighDeltaDb(1_000f, 1.4, 0.35);
        assertHighDeltaDb(2_000f, 3.2, 0.4);
        assertHighDeltaDb(5_000f, 5.8, 0.4);
        assertHighDeltaDb(8_000f, 5.9, 0.3);
    }

    @Test
    public void noiseSpectrumHasMeasuredEightToNineKilohertzMound() {
        TpsL2Dsp renderer = new TpsL2Dsp(SAMPLE_RATE, 42L,
                false, true, false);
        float[] silence = new float[SAMPLE_RATE * 2 * 3];

        renderer.process(silence, SAMPLE_RATE * 3);

        double middle = spectralBandPower(silence, 0, 2_000f, 800f, 16, SAMPLE_RATE);
        double mound = spectralBandPower(silence, 0, 8_600f, 1_200f, 16, SAMPLE_RATE);
        double upper = spectralBandPower(silence, 0, 15_000f, 1_600f, 16, SAMPLE_RATE);
        assertTrue("8-9 kHz hiss mound should rise above the midrange",
                decibels(mound / middle) > 3.0);
        assertTrue("Machine bandwidth should roll hiss down above the mound",
                decibels(mound / upper) > 2.0);
    }

    @Test
    public void transportStartsSlowThenSettlesWithoutGainTremolo() {
        TpsL2Dsp renderer = new TpsL2Dsp(SAMPLE_RATE, 1980L,
                true, false, false);
        float[] tone = sine(1_000f, 0.08f, SAMPLE_RATE * 17);

        renderer.process(tone, SAMPLE_RATE * 17);

        double earlyHz = zeroCrossingFrequency(tone, 0,
                SAMPLE_RATE / 2, SAMPLE_RATE * 2);
        double settledHz = zeroCrossingFrequency(tone, 0,
                SAMPLE_RATE * 13, SAMPLE_RATE * 3);
        assertTrue("Reported TPS-L2 servo should audibly rise to speed",
                settledHz - earlyHz > 4.0);
        assertEquals(1_000.0, settledHz, 2.0);
    }

    @Test
    public void integratedNoDolbyHissIsStrongerThanTheSpectralReferenceLine() {
        TpsL2Dsp renderer = new TpsL2Dsp(SAMPLE_RATE, 42L,
                false, true, false);
        float[] silence = new float[SAMPLE_RATE * 2 * 3];

        renderer.process(silence, SAMPLE_RATE * 3);

        double measuredRms = rms(silence, 0, SAMPLE_RATE);
        double measuredDb = 20.0 * Math.log10(measuredRms);
        assertEquals(TpsL2Dsp.integratedHissFloorDb(), measuredDb, 0.8);
        assertTrue("Integrated hiss must remain safely below music level", measuredDb < -55.0);
        assertTrue("The old -67 dB full-band rendering was too digitally clean", measuredDb > -60.0);
    }

    @Test
    public void magneticStageAddsHarmonicsAndCompressesHotPeaks() {
        float[] quiet = renderSaturatedTone(1_000f, 0.08f);
        float[] hot = renderSaturatedTone(1_000f, 0.72f);

        double quietGain = rms(quiet, 0, SAMPLE_RATE) / (0.08 / Math.sqrt(2.0));
        double hotGain = rms(hot, 0, SAMPLE_RATE) / (0.72 / Math.sqrt(2.0));
        assertTrue("Hot tape should have less incremental gain than quiet tape",
                hotGain < quietGain * 0.88);

        double fundamental = toneAmplitude(hot, 0, 1_000f, SAMPLE_RATE);
        double harmonicPower = 0.0;
        for (int harmonic = 2; harmonic <= 8; harmonic++) {
            double amplitude = toneAmplitude(hot, 0, 1_000f * harmonic, SAMPLE_RATE);
            harmonicPower += amplitude * amplitude;
        }
        double thd = Math.sqrt(harmonicPower) / fundamental;
        assertTrue("Tape character should be clearly measurable", thd > 0.008);
        assertTrue("Tape saturation must stay musical rather than turn into fuzz", thd < 0.20);

        assertTrue("Analogue output stage must avoid a digital full-scale edge", peak(hot) < 0.92);
    }

    @Test
    public void transportAndHissAreDeterministicForRepeatableCalibration() {
        TpsL2Dsp first = new TpsL2Dsp(SAMPLE_RATE, 1980L,
                true, true, true);
        TpsL2Dsp second = new TpsL2Dsp(SAMPLE_RATE, 1980L,
                true, true, true);
        float[] a = sine(440f, 0.1f, 8_192);
        float[] b = a.clone();

        first.process(a, 8_192);
        second.process(b, 8_192);

        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], 0f);
        }
    }

    private static TpsL2Dsp cleanRenderer(boolean highTape) {
        TpsL2Dsp renderer = new TpsL2Dsp(SAMPLE_RATE, 7L,
                false, false, false);
        renderer.setHighTape(highTape);
        renderer.reset();
        return renderer;
    }

    private static double renderedRms(float frequency, boolean highTape) {
        TpsL2Dsp renderer = cleanRenderer(highTape);
        float[] audio = sine(frequency, 0.05f, SAMPLE_RATE);
        renderer.process(audio, SAMPLE_RATE);
        return rms(audio, 0, SAMPLE_RATE / 4);
    }

    private static void assertResponseDb(float frequency, double targetDb, double toleranceDb) {
        double reference = renderedRms(1_000f, false);
        double measured = renderedRms(frequency, false);
        assertEquals("LOW response at " + frequency + " Hz", targetDb,
                20.0 * Math.log10(measured / reference), toleranceDb);
    }

    private static void assertHighDeltaDb(float frequency,
                                          double targetDb,
                                          double toleranceDb) {
        double low = renderedRms(frequency, false);
        double high = renderedRms(frequency, true);
        assertEquals("HIGH-minus-LOW at " + frequency + " Hz", targetDb,
                20.0 * Math.log10(high / low), toleranceDb);
    }

    private static float[] renderSaturatedTone(float frequency, float amplitude) {
        TpsL2Dsp renderer = new TpsL2Dsp(SAMPLE_RATE, 17L,
                false, false, true);
        float[] audio = sine(frequency, amplitude, SAMPLE_RATE * 2);
        renderer.process(audio, SAMPLE_RATE * 2);
        return audio;
    }

    private static float[] sine(float frequency, float amplitude, int frameCount) {
        float[] audio = new float[frameCount * 2];
        for (int frame = 0; frame < frameCount; frame++) {
            float value = (float) (Math.sin(frame * Math.PI * 2.0 * frequency / SAMPLE_RATE)
                    * amplitude);
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value;
        }
        return audio;
    }

    private static double rms(float[] stereo, int channel, int skipFrames) {
        double sum = 0.0;
        int frameCount = stereo.length / 2;
        int samples = 0;
        for (int frame = Math.min(skipFrames, frameCount); frame < frameCount; frame++) {
            float value = stereo[frame * 2 + channel];
            sum += value * value;
            samples++;
        }
        return Math.sqrt(sum / Math.max(1, samples));
    }

    private static double toneAmplitude(float[] stereo,
                                        int channel,
                                        float frequency,
                                        int skipFrames) {
        int frameCount = stereo.length / 2;
        int start = Math.min(skipFrames, frameCount);
        double sine = 0.0;
        double cosine = 0.0;
        for (int frame = start; frame < frameCount; frame++) {
            double phase = TWO_PI_FOR_TEST * frequency * frame / SAMPLE_RATE;
            float value = stereo[frame * 2 + channel];
            sine += value * Math.sin(phase);
            cosine += value * Math.cos(phase);
        }
        return 2.0 * Math.hypot(sine, cosine) / Math.max(1, frameCount - start);
    }

    private static double spectralBandPower(float[] stereo,
                                            int channel,
                                            float centreFrequency,
                                            float width,
                                            int bins,
                                            int skipFrames) {
        int frameCount = stereo.length / 2;
        int start = Math.min(skipFrames, frameCount);
        double sum = 0.0;
        for (int bin = 0; bin < bins; bin++) {
            double frequency = centreFrequency - width * 0.5
                    + width * (bin + 0.5) / bins;
            double coefficient = 2.0 * Math.cos(TWO_PI_FOR_TEST * frequency / SAMPLE_RATE);
            double previous = 0.0;
            double previousTwo = 0.0;
            for (int frame = start; frame < frameCount; frame++) {
                double current = stereo[frame * 2 + channel]
                        + coefficient * previous - previousTwo;
                previousTwo = previous;
                previous = current;
            }
            sum += previous * previous + previousTwo * previousTwo
                    - coefficient * previous * previousTwo;
        }
        return sum / bins;
    }

    private static double zeroCrossingFrequency(float[] stereo,
                                                int channel,
                                                int startFrame,
                                                int windowFrames) {
        int end = Math.min(stereo.length / 2, startFrame + windowFrames);
        double firstCrossing = Double.NaN;
        double lastCrossing = Double.NaN;
        int crossings = 0;
        float previous = stereo[startFrame * 2 + channel];
        for (int frame = startFrame + 1; frame < end; frame++) {
            float current = stereo[frame * 2 + channel];
            if (previous <= 0f && current > 0f) {
                double fraction = -previous / (current - previous);
                double crossing = frame - 1 + fraction;
                if (crossings == 0) {
                    firstCrossing = crossing;
                }
                lastCrossing = crossing;
                crossings++;
            }
            previous = current;
        }
        return (crossings - 1) * SAMPLE_RATE / (lastCrossing - firstCrossing);
    }

    private static double decibels(double powerRatio) {
        return 10.0 * Math.log10(powerRatio);
    }

    private static double peak(float[] stereo) {
        double peak = 0.0;
        for (float value : stereo) {
            peak = Math.max(peak, Math.abs(value));
        }
        return peak;
    }

    private static final double TWO_PI_FOR_TEST = Math.PI * 2.0;
}
