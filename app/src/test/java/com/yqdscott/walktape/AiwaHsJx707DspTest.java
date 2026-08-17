package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AiwaHsJx707DspTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final double TWO_PI = Math.PI * 2.0;

    @Test
    public void serviceEnvelopeAndModelTargetAreExplicitlySeparated() {
        assertEquals(0.320f, AiwaHsJx707Dsp.nominalWowFlutterRmsPercent(), 0.001f);
        assertEquals(0.45f, AiwaHsJx707Dsp.serviceLimitWowFlutterRmsPercent(), 0.0001f);
        assertTrue(AiwaHsJx707Dsp.nominalWowFlutterRmsPercent()
                < AiwaHsJx707Dsp.serviceLimitWowFlutterRmsPercent());
        TapeMachineProfile profile = TapeMachineProfile.aiwaHsJx707Reference();
        assertEquals("63 Hz — 8 kHz", profile.frequencySpec(false));
        assertEquals("63 Hz — 12.5 kHz", profile.frequencySpec(true));
        assertTrue(profile.calibrationBasis.contains("FACTORY SERVICE"));
    }

    @Test
    public void normalAndChromeMetalStayInsidePublishedBandwidths() {
        double normal63 = responseDb(63f, false);
        double normal8k = responseDb(8_000f, false);
        double normal12k5 = responseDb(12_500f, false);
        double metal63 = responseDb(63f, true);
        double metal8k = responseDb(8_000f, true);
        double metal12k5 = responseDb(12_500f, true);

        assertTrue("Normal 63 Hz endpoint: " + normal63,
                normal63 > -4.5 && normal63 < 1.5);
        assertTrue("Normal 8 kHz endpoint: " + normal8k,
                normal8k > -4.5 && normal8k < 1.5);
        assertTrue("Normal should be substantially down by 12.5 kHz: " + normal12k5,
                normal12k5 < -7.0);
        assertTrue("Cr/Metal 63 Hz endpoint: " + metal63,
                metal63 > -4.5 && metal63 < 1.5);
        assertTrue("Cr/Metal should retain 8 kHz: " + metal8k,
                metal8k > -2.5);
        assertTrue("Cr/Metal 12.5 kHz endpoint: " + metal12k5,
                metal12k5 > -4.5 && metal12k5 < 0.5);
        assertTrue("The manual selector must audibly extend treble",
                metal12k5 - normal12k5 > 4.0);
    }

    /**
     * No spec-derived high-pass survives anywhere in the chain.
     *
     * <p>A 63 Hz Butterworth, which is what used to shape this end before the replay equaliser and
     * the output coupling were both derived, would be roughly 20 dB down at 20 Hz. Two real
     * first-order rolloffs are not: they cost about 8 dB together. This is the guard against
     * someone re-adding a shaping filter on top of the components.</p>
     */
    @Test
    public void noSpecShapedHighPassRemainsInTheChain() {
        for (boolean metal : new boolean[]{false, true}) {
            assertTrue("20 Hz is far deeper than two first-order rolloffs can account for",
                    responseDb(20f, metal) > -11.0);
            assertTrue("63 Hz must stay essentially intact",
                    responseDb(63f, metal) > -2.5);
        }
    }

    /**
     * The subsonic end is now two derived rolloffs in series, not one.
     *
     * <p>The replay equaliser's own C19/R17 turnover and the C86 output coupling into the rated
     * load are separate stages of the real machine, so they add. Below 40 Hz the head contour has
     * almost no say, which makes this a direct check that both are present and neither is being
     * counted twice.</p>
     */
    @Test
    public void theSubsonicEndIsTheReplayTurnoverPlusTheOutputCoupling() {
        AiwaHsJx707ReplayEq replay = new AiwaHsJx707ReplayEq();
        AiwaHsJx707OutputStage output = new AiwaHsJx707OutputStage();
        for (boolean metal : new boolean[]{false, true}) {
            double treble = metal ? AiwaHsJx707ReplayEq.IEC_METAL_SECONDS
                    : AiwaHsJx707ReplayEq.IEC_NORMAL_SECONDS;
            for (double frequency : new double[]{20.0, 25.0, 31.5}) {
                double expected = replay.relativeResponseDb(frequency, metal)
                        - AiwaHsJx707ReplayEq.relativeTargetDb(frequency, treble)
                        + output.relativeResponseDb(frequency);
                assertEquals("Both derived rolloffs must appear at " + frequency
                                + " Hz, metal=" + metal,
                        expected, responseDb((float) frequency, metal), 0.35);
            }
        }
        assertTrue("The coupling capacitor has to cost real subsonic level",
                responseDb(20f, false) < -6.5);
    }

    /**
     * Through the midband the record pre-emphasis and its inverse cancel, leaving only the small
     * derived error. Anything larger would mean the equaliser pair had stopped complementing.
     */
    @Test
    public void theMidbandCarriesOnlyTheDerivedReplayError() {
        AiwaHsJx707OutputStage output = new AiwaHsJx707OutputStage();
        for (boolean metal : new boolean[]{false, true}) {
            for (float frequency : new float[]{63f, 100f, 200f, 400f, 800f, 2_000f}) {
                // Take the output coupling back out; what is left should be only the small
                // replay error and the head contour prior.
                double response = responseDb(frequency, metal)
                        - output.relativeResponseDb(frequency);
                assertTrue("Midband deviation at " + frequency + " Hz, metal=" + metal
                        + " was " + response, Math.abs(response) < 1.2);
            }
        }
        assertTrue("Cr/Metal must hold more treble than Normal once past the shelf",
                responseDb(4_000f, true) > responseDb(4_000f, false));
    }

    @Test
    public void renderedPitchMotionIsAudibleButRemainsBelowServiceLimit() {
        AiwaHsJx707Dsp renderer = new AiwaHsJx707Dsp(SAMPLE_RATE, 707L,
                true, false, false);
        float[] tone = sine(1_000f, 0.08f, SAMPLE_RATE * 32);
        renderer.process(tone, SAMPLE_RATE * 32);

        double measured = pitchFlutterRms(tone, 0, 1_000f,
                SAMPLE_RATE * 12, SAMPLE_RATE * 18);
        assertEquals(AiwaHsJx707Dsp.nominalWowFlutterRmsPercent() / 100.0,
                measured, 0.00045);
        assertTrue("Piano sustain needs clearly audible pitch instability",
                measured * 1_200.0 / Math.log(2.0) > 4.5);
        assertTrue(measured * 100.0
                < AiwaHsJx707Dsp.serviceLimitWowFlutterRmsPercent());
    }

    @Test
    public void noDolbyHissStillExceedsTheFactorySignalToNoiseRequirement() {
        AiwaHsJx707Dsp renderer = new AiwaHsJx707Dsp(SAMPLE_RATE, 707L,
                false, true, false);
        renderer.setHighTape(true);
        renderer.reset();
        float[] silence = new float[SAMPLE_RATE * 2 * 4];
        renderer.process(silence, SAMPLE_RATE * 4);

        double levelDb = 20.0 * Math.log10(rms(silence, 0, SAMPLE_RATE));
        assertEquals(AiwaHsJx707Dsp.integratedHissFloorDb(), levelDb, 1.0);
        assertTrue("Hiss should remain analogue-audible", levelDb > -60.0);
        assertTrue("Full-scale reference S/N must clear the >45 dB service requirement",
                levelDb < -45.0);
    }

    @Test
    public void rendererIsDeterministicAndFiniteAcrossBlockBoundaries() {
        AiwaHsJx707Dsp first = new AiwaHsJx707Dsp(SAMPLE_RATE, 1992L,
                true, true, true);
        AiwaHsJx707Dsp second = new AiwaHsJx707Dsp(SAMPLE_RATE, 1992L,
                true, true, true);
        first.setHighTape(true);
        second.setHighTape(true);
        first.reset();
        second.reset();
        float[] source = sine(440f, 0.2f, 16_384);
        float[] a = new float[source.length];
        float[] firstBlock = new float[4_096 * 2];
        float[] secondBlock = new float[(16_384 - 4_096) * 2];
        System.arraycopy(source, 0, firstBlock, 0, firstBlock.length);
        System.arraycopy(source, firstBlock.length, secondBlock, 0, secondBlock.length);
        first.process(firstBlock, 4_096);
        first.process(secondBlock, 16_384 - 4_096);
        System.arraycopy(firstBlock, 0, a, 0, firstBlock.length);
        System.arraycopy(secondBlock, 0, a, firstBlock.length, secondBlock.length);
        float[] b = source.clone();
        second.process(b, 16_384);

        for (int index = 0; index < a.length; index++) {
            assertTrue(Float.isFinite(a[index]));
            assertEquals(a[index], b[index], 0f);
        }
    }

    @Test
    public void factoryCreatesTheDedicatedRendererWithoutChangingSonyDefault() {
        assertTrue(TapeMachineDspFactory.create(
                TapeMachineProfile.sonyTpsL2Reference(), SAMPLE_RATE) instanceof TpsL2Dsp);
        assertTrue(TapeMachineDspFactory.create(
                TapeMachineProfile.aiwaHsJx707Reference(), SAMPLE_RATE)
                instanceof AiwaHsJx707Dsp);
        assertEquals(TapeMachineProfile.SONY_TPS_L2,
                TapeMachineProfile.forId("not-a-real-machine").id);
    }

    private static double responseDb(float frequency, boolean highTape) {
        double reference = renderedRms(1_000f, highTape);
        return 20.0 * Math.log10(renderedRms(frequency, highTape) / reference);
    }

    private static double renderedRms(float frequency, boolean highTape) {
        AiwaHsJx707Dsp renderer = new AiwaHsJx707Dsp(SAMPLE_RATE, 7L,
                false, false, false);
        renderer.setHighTape(highTape);
        renderer.reset();
        float[] audio = sine(frequency, 0.05f, SAMPLE_RATE);
        renderer.process(audio, SAMPLE_RATE);
        return rms(audio, 0, SAMPLE_RATE / 4);
    }

    private static float[] sine(float frequency, float amplitude, int frames) {
        float[] audio = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) (Math.sin(TWO_PI * frequency * frame / SAMPLE_RATE)
                    * amplitude);
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
