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
        // Replay sensitivity lifts or lowers everything the stock puts out, hiss included. The
        // coating's own noise target is what is under test here, so take it back out.
        double chfDb = silenceRmsDb(TapeStockProfile.sonyChf1978())
                - TapeStockProfile.sonyChf1978().sensitivityDb;
        double saDb = silenceRmsDb(TapeStockProfile.tdkSa1988())
                - TapeStockProfile.tdkSa1988().sensitivityDb;
        double metalDb = silenceRmsDb(TapeStockProfile.tdkMaX1990())
                - TapeStockProfile.tdkMaX1990().sensitivityDb;

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
    public void metalReachesAHigherTenKilohertzCeilingThanEarlyFerric() {
        // The measured advantage of metal at 10 kHz is its saturation ceiling, not its behaviour
        // at one arbitrary hot level: TDK MA-X publishes SOL10k +0.5 dB against Sony CHF's
        // -1.4 dB, so the whole difference is 1.9 dB. This used to assert a much larger gap,
        // which only held while the tape stage had no saturation ceiling at all.
        double advantageDb = TapeStockProfile.tdkMaX1990().sol10kDb
                - TapeStockProfile.sonyChf1978().sol10kDb;
        double chfCeiling = ceilingAtTenKilohertz(TapeStockProfile.sonyChf1978());
        double metalCeiling = ceilingAtTenKilohertz(TapeStockProfile.tdkMaX1990());

        assertEquals("Rendered ceilings must reproduce the published SOL difference",
                advantageDb, metalCeiling - chfCeiling, 0.8);
    }

    /** Highest 10 kHz output the stock can be driven to, in dB relative to its own low-level gain. */
    private static double ceilingAtTenKilohertz(TapeStockProfile profile) {
        double reference = TapeStockProfile.REFERENCE_FLUX_LEVEL;
        double best = 0;
        for (double driveDb = 0; driveDb <= 40; driveDb += 1.0) {
            best = Math.max(best, renderedRms(profile, 10_000f,
                    (float) (reference * Math.pow(10.0, driveDb / 20.0))));
        }
        // Published SOL is referenced to recorded flux, so the stock's replay sensitivity comes
        // back out before the two ceilings are compared.
        return decibels(best / reference) - profile.sensitivityDb;
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
