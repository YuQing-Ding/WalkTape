package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TapeMediumDspTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final double TWO_PI = Math.PI * 2.0;

    @Test
    public void namedStocksEncodeTheirReferenceEqualisationAndHeadroom() {
        TapeStockProfile chf = TapeStockProfile.sonyChf1978();
        TapeStockProfile sa = TapeStockProfile.tdkSa1988();
        TapeStockProfile metal = TapeStockProfile.tdkMaX1990();

        assertEquals(120, chf.replayEqMicroseconds);
        assertEquals(2.1f, chf.mol315Db, 0.001f);
        assertEquals(-1.4f, chf.sol10kDb, 0.001f);
        assertEquals(70, sa.replayEqMicroseconds);
        assertEquals(4.5f, sa.mol315Db, 0.001f);
        assertEquals(-60.5f, sa.biasNoiseDb, 0.001f);
        assertEquals(70, metal.replayEqMicroseconds);
        assertEquals(6.0f, metal.mol315Db, 0.001f);
        assertEquals(0.5f, metal.sol10kDb, 0.001f);
        assertTrue(!chf.isHighPosition());
        assertTrue(sa.isHighPosition());
        assertTrue(metal.isHighPosition());
    }

    @Test
    public void renderedHissMatchesEachStocksTargetAndFerricIsClearlyGrainier() {
        double chfDb = silenceRmsDb(TapeStockProfile.sonyChf1978());
        double saDb = silenceRmsDb(TapeStockProfile.tdkSa1988());
        double metalDb = silenceRmsDb(TapeStockProfile.tdkMaX1990());

        assertEquals(TapeMediumDsp.renderedHissFloorDb(TapeStockProfile.sonyChf1978()),
                chfDb, 0.8);
        assertEquals(TapeMediumDsp.renderedHissFloorDb(TapeStockProfile.tdkSa1988()),
                saDb, 0.8);
        assertEquals(TapeMediumDsp.renderedHissFloorDb(TapeStockProfile.tdkMaX1990()),
                metalDb, 0.8);
        assertTrue("CHF should have materially more audible particle noise",
                chfDb > saDb + 3.5);
        assertTrue("SA was the quieter of these two high-position references",
                metalDb > saDb);
    }

    @Test
    public void ferricCompressesHotProgrammeEarlierThanMetal() {
        double chfQuiet = renderedGain(TapeStockProfile.sonyChf1978(), 400f, 0.08f);
        double chfHot = renderedGain(TapeStockProfile.sonyChf1978(), 400f, 0.78f);
        double metalQuiet = renderedGain(TapeStockProfile.tdkMaX1990(), 400f, 0.08f);
        double metalHot = renderedGain(TapeStockProfile.tdkMaX1990(), 400f, 0.78f);

        double chfCompressionDb = decibels(chfHot / chfQuiet);
        double metalCompressionDb = decibels(metalHot / metalQuiet);
        assertTrue("CHF needs an obvious magnetic knee: " + chfCompressionDb,
                chfCompressionDb < -1.0);
        assertTrue("Metal stock must retain more peak headroom",
                metalCompressionDb > chfCompressionDb + 0.7);
    }

    @Test
    public void metalCarriesHotTenKilohertzEnergyBetterThanEarlyFerric() {
        double chf = renderedRms(TapeStockProfile.sonyChf1978(), 10_000f, 0.52f);
        double metal = renderedRms(TapeStockProfile.tdkMaX1990(), 10_000f, 0.52f);
        double chfReference = renderedRms(TapeStockProfile.sonyChf1978(), 1_000f, 0.52f);
        double metalReference = renderedRms(TapeStockProfile.tdkMaX1990(), 1_000f, 0.52f);
        double advantageDb = decibels((metal / metalReference) / (chf / chfReference));

        assertTrue("Type IV SOL should preserve substantially more hot treble: " + advantageDb,
                advantageDb > 2.5);
    }

    @Test
    public void processingIsDeterministicAndIndependentOfBlockBoundaries() {
        TapeMediumDsp oneBlock = new TapeMediumDsp(SAMPLE_RATE,
                TapeStockProfile.tdkSa1988(), 88L);
        TapeMediumDsp splitBlocks = new TapeMediumDsp(SAMPLE_RATE,
                TapeStockProfile.tdkSa1988(), 88L);
        float[] source = sine(997f, 0.31f, 18_000);
        float[] expected = source.clone();
        oneBlock.process(expected, 18_000);

        float[] first = new float[3_113 * 2];
        float[] second = new float[source.length - first.length];
        System.arraycopy(source, 0, first, 0, first.length);
        System.arraycopy(source, first.length, second, 0, second.length);
        splitBlocks.process(first, first.length / 2);
        splitBlocks.process(second, second.length / 2);

        for (int index = 0; index < expected.length; index++) {
            float actual = index < first.length ? first[index] : second[index - first.length];
            assertTrue(Float.isFinite(actual));
            assertEquals(expected[index], actual, 0f);
        }
    }

    @Test
    public void productionFactoryBuildsTapeThenMachineWithoutChangingLegacyFactory() {
        TapeMachineDsp production = TapeMachineDspFactory.create(
                TapeMachineProfile.aiwaHsJx707Reference(),
                TapeStockProfile.tdkSa1988(), SAMPLE_RATE);
        assertTrue(production instanceof CassetteSignalChainDsp);
        CassetteSignalChainDsp chain = (CassetteSignalChainDsp) production;
        assertEquals(TapeStockProfile.TDK_SA_1988, chain.tapeProfile().id);
        assertTrue(chain.machineRenderer() instanceof AiwaHsJx707Dsp);
        assertTrue(TapeMachineDspFactory.create(
                TapeMachineProfile.sonyTpsL2Reference(), SAMPLE_RATE) instanceof TpsL2Dsp);
    }

    private static double silenceRmsDb(TapeStockProfile profile) {
        TapeMediumDsp renderer = new TapeMediumDsp(SAMPLE_RATE, profile, 42L);
        float[] silence = new float[SAMPLE_RATE * 2 * 3];
        renderer.process(silence, SAMPLE_RATE * 3);
        return 20.0 * Math.log10(rms(silence, SAMPLE_RATE));
    }

    private static double renderedGain(TapeStockProfile profile,
                                       float frequency,
                                       float amplitude) {
        return renderedRms(profile, frequency, amplitude) / (amplitude / Math.sqrt(2.0));
    }

    private static double renderedRms(TapeStockProfile profile,
                                      float frequency,
                                      float amplitude) {
        TapeMediumDsp renderer = new TapeMediumDsp(SAMPLE_RATE, profile, 17L);
        float[] audio = sine(frequency, amplitude, SAMPLE_RATE * 2);
        renderer.process(audio, SAMPLE_RATE * 2);
        return rms(audio, SAMPLE_RATE);
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

    private static double rms(float[] stereo, int skipFrames) {
        double sum = 0.0;
        int count = 0;
        for (int frame = skipFrames; frame < stereo.length / 2; frame++) {
            float value = stereo[frame * 2];
            sum += value * value;
            count++;
        }
        return Math.sqrt(sum / Math.max(1, count));
    }

    private static double decibels(double amplitudeRatio) {
        return 20.0 * Math.log10(amplitudeRatio);
    }
}
