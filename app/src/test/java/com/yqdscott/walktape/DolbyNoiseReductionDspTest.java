package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DolbyNoiseReductionDspTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final double TWO_PI = Math.PI * 2.0;

    @Test
    public void profilesExposeDolbyOnlyWhereTheServiceManualDoes() {
        assertTrue(TapeMachineProfile.sonyWmD6cReference().supportsDolbyBC());
        assertTrue(TapeMachineProfile.aiwaHsJx707Reference().supportsDolbyBC());
        assertTrue(!TapeMachineProfile.sonyTpsL2Reference().supportsDolbyBC());
        assertTrue(!TapeMachineProfile.sonyWmF2015Reference().supportsDolbyBC());
        TapeMachineProfile d6cProfile = TapeMachineProfile.sonyWmD6cReference();
        assertEquals("54 dB  ·  TYPE I  ·  NR OFF", d6cProfile.noiseSpecValue(
                DolbyMode.OFF, TapeStockProfile.sonyChf1978()));
        assertEquals("65 dB  ·  TYPE II/IV  ·  NR B", d6cProfile.noiseSpecValue(
                DolbyMode.B, TapeStockProfile.tdkSa1988()));
        assertEquals("71 dB  ·  TYPE II/IV  ·  NR C", d6cProfile.noiseSpecValue(
                DolbyMode.C, TapeStockProfile.tdkMaX1990()));

        CassetteSignalChainDsp d6c = (CassetteSignalChainDsp) TapeMachineDspFactory.create(
                TapeMachineProfile.sonyWmD6cReference(), TapeStockProfile.tdkSa1988(),
                SAMPLE_RATE);
        d6c.setDolbyMode(DolbyMode.C);
        assertEquals(DolbyMode.C, d6c.dolbyMode());

        CassetteSignalChainDsp tps = (CassetteSignalChainDsp) TapeMachineDspFactory.create(
                TapeMachineProfile.sonyTpsL2Reference(), TapeStockProfile.sonyChf1978(),
                SAMPLE_RATE);
        tps.setDolbyMode(DolbyMode.C);
        assertEquals(DolbyMode.OFF, tps.dolbyMode());
    }

    @Test
    public void offIsAnExactSampleForSampleBypass() {
        DolbyNoiseReductionDsp renderer = new DolbyNoiseReductionDsp(SAMPLE_RATE, true);
        float[] source = programme(SAMPLE_RATE * 2);
        float[] rendered = source.clone();
        DolbyMode mode = renderer.beginBlock();
        renderer.encode(rendered, rendered.length / 2, mode);
        renderer.decode(rendered, rendered.length / 2, mode);
        for (int sample = 0; sample < source.length; sample++) {
            assertEquals(source[sample], rendered[sample], 0f);
        }
    }

    @Test
    public void bAndCComplementBackToTheOriginalWithoutTapeError() {
        assertComplementary(DolbyMode.B, 1.5e-5);
        assertComplementary(DolbyMode.C, 3.0e-5);
    }

    @Test
    public void lowLevelCurvesFollowThePublishedBAndCTargets() {
        assertEquals(3.0, encoderGainDb(DolbyMode.B, 600f), 0.65);
        assertEquals(6.0, encoderGainDb(DolbyMode.B, 1_200f), 0.65);
        assertEquals(8.5, encoderGainDb(DolbyMode.B, 2_400f), 0.65);
        assertEquals(9.6, encoderGainDb(DolbyMode.B, 5_000f), 0.55);

        assertEquals(4.1, encoderGainDb(DolbyMode.C, 100f), 0.85);
        assertEquals(9.5, encoderGainDb(DolbyMode.C, 200f), 0.85);
        assertEquals(16.6, encoderGainDb(DolbyMode.C, 500f), 0.9);
        assertEquals(18.9, encoderGainDb(DolbyMode.C, 1_000f), 0.9);
    }

    @Test
    public void replayExpanderActuallyReducesLowLevelTapeBandNoise() {
        double bReduction = decoderGainDb(DolbyMode.B, 5_000f);
        double cReduction = decoderGainDb(DolbyMode.C, 5_000f);
        assertTrue("B reduction: " + bReduction, bReduction < -8.5);
        assertTrue("C reduction: " + cReduction, cReduction < -15.0);
        assertTrue(cReduction < bReduction - 6.0);
    }

    @Test
    public void slidingBandsWithdrawTheBoostAtDolbyLevel() {
        double bGain = encoderGainDb(DolbyMode.B, 5_000f, 0.18f);
        double cGain = encoderGainDb(DolbyMode.C, 1_000f, 0.18f);
        assertTrue("B gain at Dolby level: " + bGain, bGain < 2.5);
        assertTrue("C gain at Dolby level: " + cGain, cGain < 3.0);
    }

    @Test
    public void productionChainsRemainFiniteAndBlockBoundaryDeterministic() {
        assertProductionSplit(TapeMachineProfile.sonyWmD6cReference(),
                TapeStockProfile.tdkSa1988(), DolbyMode.C);
        assertProductionSplit(TapeMachineProfile.aiwaHsJx707Reference(),
                TapeStockProfile.sonyChf1978(), DolbyMode.B);
    }

    private static void assertProductionSplit(TapeMachineProfile profile,
                                              TapeStockProfile tape,
                                              DolbyMode mode) {
        TapeMachineDsp wholeRenderer = TapeMachineDspFactory.create(profile, tape, SAMPLE_RATE);
        TapeMachineDsp splitRenderer = TapeMachineDspFactory.create(profile, tape, SAMPLE_RATE);
        wholeRenderer.setHighTape(tape.isHighPosition());
        splitRenderer.setHighTape(tape.isHighPosition());
        wholeRenderer.setDolbyMode(mode);
        splitRenderer.setDolbyMode(mode);
        wholeRenderer.reset();
        splitRenderer.reset();

        float[] source = programme(16_384);
        float[] whole = source.clone();
        wholeRenderer.process(whole, whole.length / 2);
        float[] first = new float[4_096 * 2];
        float[] second = new float[source.length - first.length];
        System.arraycopy(source, 0, first, 0, first.length);
        System.arraycopy(source, first.length, second, 0, second.length);
        splitRenderer.process(first, first.length / 2);
        splitRenderer.process(second, second.length / 2);

        for (int sample = 0; sample < whole.length; sample++) {
            float split = sample < first.length ? first[sample] : second[sample - first.length];
            assertTrue(Float.isFinite(whole[sample]));
            assertEquals(whole[sample], split, 0f);
        }
    }

    private static void assertComplementary(DolbyMode selected, double maximumRmsError) {
        DolbyNoiseReductionDsp renderer = new DolbyNoiseReductionDsp(SAMPLE_RATE, true);
        renderer.setMode(selected);
        DolbyMode mode = renderer.beginBlock();
        float[] source = programme(SAMPLE_RATE * 3);
        float[] rendered = source.clone();
        renderer.encode(rendered, rendered.length / 2, mode);
        renderer.decode(rendered, rendered.length / 2, mode);
        double squareError = 0.0;
        for (int sample = 0; sample < source.length; sample++) {
            assertTrue(Float.isFinite(rendered[sample]));
            double error = rendered[sample] - source[sample];
            squareError += error * error;
        }
        double rmsError = Math.sqrt(squareError / source.length);
        assertTrue(selected + " complementary error: " + rmsError,
                rmsError < maximumRmsError);
    }

    private static double encoderGainDb(DolbyMode mode, float frequency) {
        return encoderGainDb(mode, frequency, 0.000001f);
    }

    private static double encoderGainDb(DolbyMode mode,
                                        float frequency,
                                        float amplitude) {
        DolbyNoiseReductionDsp renderer = new DolbyNoiseReductionDsp(SAMPLE_RATE, true);
        renderer.setMode(mode);
        DolbyMode blockMode = renderer.beginBlock();
        float[] input = sine(frequency, amplitude, SAMPLE_RATE * 2);
        float inputRms = rms(input, SAMPLE_RATE);
        renderer.encode(input, input.length / 2, blockMode);
        return 20.0 * Math.log10(rms(input, SAMPLE_RATE) / inputRms);
    }

    private static double decoderGainDb(DolbyMode mode, float frequency) {
        DolbyNoiseReductionDsp renderer = new DolbyNoiseReductionDsp(SAMPLE_RATE, true);
        renderer.setMode(mode);
        DolbyMode blockMode = renderer.beginBlock();
        // Advance the matching encoder with silence, as it would before tape hiss is introduced.
        float[] input = sine(frequency, 0.000001f, SAMPLE_RATE * 2);
        float inputRms = rms(input, SAMPLE_RATE);
        renderer.decode(input, input.length / 2, blockMode);
        return 20.0 * Math.log10(rms(input, SAMPLE_RATE) / inputRms);
    }

    private static float[] programme(int frames) {
        float[] audio = new float[frames * 2];
        long state = 0x444f4c42594243L;
        for (int frame = 0; frame < frames; frame++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            float noise = ((state >>> 40) / 8_388_607.5f - 1f) * 0.012f;
            float envelope = frame < SAMPLE_RATE ? frame / (float) SAMPLE_RATE
                    : frame > SAMPLE_RATE * 2 ? (frames - frame) / (float) SAMPLE_RATE : 1f;
            float left = (float) (Math.sin(TWO_PI * 431f * frame / SAMPLE_RATE) * 0.18
                    + Math.sin(TWO_PI * 4_700f * frame / SAMPLE_RATE) * 0.025 + noise);
            float right = (float) (Math.sin(TWO_PI * 733f * frame / SAMPLE_RATE) * 0.14
                    + Math.sin(TWO_PI * 8_100f * frame / SAMPLE_RATE) * 0.018 - noise);
            audio[frame * 2] = left * envelope;
            audio[frame * 2 + 1] = right * envelope;
        }
        return audio;
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

    private static float rms(float[] stereo, int skipFrames) {
        double sum = 0.0;
        int count = 0;
        for (int frame = skipFrames; frame < stereo.length / 2; frame++) {
            float value = stereo[frame * 2];
            sum += value * value;
            count++;
        }
        return (float) Math.sqrt(sum / Math.max(1, count));
    }
}
