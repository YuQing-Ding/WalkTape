package com.yqdscott.walktape;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MachineImperfectionDspTest {
    private static final int SAMPLE_RATE = 48_000;

    @Test
    public void profilesAreStableAndUnknownIdsFailSafeToCalibrated() {
        List<MachineConditionProfile> profiles =
                MachineConditionProfile.availableProfiles();
        assertEquals(4, profiles.size());
        assertSame(MachineConditionProfile.calibrated(),
                MachineConditionProfile.forId("unknown"));
        assertSame(MachineConditionProfile.natural(),
                MachineConditionProfile.forId(MachineConditionProfile.NATURAL));
        assertSame(MachineConditionProfile.livedIn(),
                MachineConditionProfile.forId(MachineConditionProfile.LIVED_IN));
        assertSame(MachineConditionProfile.extraLivedIn(),
                MachineConditionProfile.forId(MachineConditionProfile.EXTRA_LIVED_IN));
        assertTrue(MachineConditionProfile.calibrated().isCalibrated());
        assertFalse(MachineConditionProfile.livedIn().isCalibrated());
        assertFalse(MachineConditionProfile.extraLivedIn().isCalibrated());
    }

    /**
     * The presets have to read as one axis of wear, not four unrelated settings.
     *
     * <p>Every term rises monotonically from CALIBRATED through EXTRA LIVED-IN, so picking a more
     * worn unit can never make some aspect of the machine measure better.</p>
     */
    @Test
    public void everyToleranceTermRisesMonotonicallyWithWear() {
        List<MachineConditionProfile> profiles =
                MachineConditionProfile.availableProfiles();
        for (int index = 1; index < profiles.size(); index++) {
            MachineConditionProfile previous = profiles.get(index - 1);
            MachineConditionProfile current = profiles.get(index);
            String what = previous.name + " -> " + current.name;
            assertTrue("Transport " + what, current.transportScale > previous.transportScale);
            assertTrue("Balance " + what, current.channelBalanceDb > previous.channelBalanceDb);
            assertTrue("Azimuth " + what,
                    current.azimuthMicroseconds > previous.azimuthMicroseconds);
            assertTrue("HF mismatch " + what,
                    current.highFrequencyMismatchDb > previous.highFrequencyMismatchDb);
            assertTrue("Crosstalk " + what, current.extraCrosstalk > previous.extraCrosstalk);
        }
    }

    /**
     * EXTRA LIVED-IN must still be a working machine, not a faulty one.
     *
     * <p>The class contract excludes drop-outs and gross speed error, so the added speed variation
     * stays far inside the tightest service limit this app models: the TPS-L2 is specified at
     * 0.219% RMS and the JX707 at 0.45% maximum.</p>
     */
    @Test
    public void extraLivedInStaysWellInsideEveryModelledServiceLimit() {
        double sum = 0.0;
        for (double peak : MachineImperfectionDsp.FULL_SPEED_PEAK) {
            double scaled = peak * MachineConditionProfile.extraLivedIn().transportScale;
            sum += scaled * scaled * 0.5;
        }
        double addedPercent = Math.sqrt(sum) * 100.0;
        assertEquals("Preset advertises +0.052% W&F", 0.052, addedPercent, 0.001);
        assertTrue("Added wow must stay a small fraction of the service limit",
                addedPercent < 0.45 * 0.2);

        // The head terms are where the age is meant to be heard, not the transport.
        assertTrue("Azimuth should carry the character",
                MachineConditionProfile.extraLivedIn().azimuthMicroseconds
                        > MachineConditionProfile.livedIn().azimuthMicroseconds * 1.6f);
    }

    @Test
    public void extraLivedInUnitStaysContinuousFiniteAndFreeOfFaultLikeSpikes() {
        int frames = SAMPLE_RATE * 4;
        float[] audio = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float time = frame / (float) SAMPLE_RATE;
            float value = 0.48f * (float) Math.sin(2.0 * Math.PI * 440.0 * time)
                    + 0.17f * (float) Math.sin(2.0 * Math.PI * 3_173.0 * time);
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value;
        }
        MachineImperfectionDsp renderer = new MachineImperfectionDsp(
                SAMPLE_RATE, MachineConditionProfile.extraLivedIn(), 0x534f4e59L);
        renderer.process(audio, frames);

        double energy = 0.0;
        float peak = 0f;
        int start = SAMPLE_RATE / 5 * 2;
        for (int sample = 0; sample < audio.length; sample++) {
            assertTrue(Float.isFinite(audio[sample]));
            peak = Math.max(peak, Math.abs(audio[sample]));
            if (sample >= start) {
                energy += audio[sample] * audio[sample];
            }
        }
        double rms = Math.sqrt(energy / (audio.length - start));
        assertTrue("A worn unit still has to pass the programme: " + rms, rms > 0.30);
        assertTrue("A worn unit is not a broken one: " + peak, peak < 0.78f);
    }

    @Test
    public void calibratedProductionChainRemainsTheExistingBitPath() {
        CassetteSignalChainDsp chain = (CassetteSignalChainDsp) TapeMachineDspFactory.create(
                TapeMachineProfile.sonyTpsL2Reference(),
                TapeStockProfile.sonyChf1978(),
                MachineConditionProfile.calibrated(), SAMPLE_RATE);
        assertNull(chain.imperfectionRenderer());
    }

    @Test
    public void naturalAndLivedInProfilesInsertTheHealthyToleranceStage() {
        CassetteSignalChainDsp natural = (CassetteSignalChainDsp) TapeMachineDspFactory.create(
                TapeMachineProfile.sonyTpsL2Reference(),
                TapeStockProfile.sonyChf1978(),
                MachineConditionProfile.natural(), SAMPLE_RATE);
        CassetteSignalChainDsp lived = (CassetteSignalChainDsp) TapeMachineDspFactory.create(
                TapeMachineProfile.aiwaHsJx707Reference(),
                TapeStockProfile.tdkSa1988(),
                MachineConditionProfile.livedIn(), SAMPLE_RATE);
        CassetteSignalChainDsp f2015 = (CassetteSignalChainDsp) TapeMachineDspFactory.create(
                TapeMachineProfile.sonyWmF2015Reference(),
                TapeStockProfile.tdkSa1988(),
                MachineConditionProfile.livedIn(), SAMPLE_RATE);
        assertSame(MachineConditionProfile.natural(),
                natural.imperfectionRenderer().profile());
        assertSame(MachineConditionProfile.livedIn(),
                lived.imperfectionRenderer().profile());
        assertNull("The F2015 must not cascade a second physical transport",
                f2015.imperfectionRenderer());
        assertSame(MachineConditionProfile.livedIn(),
                ((SonyWmF2015Dsp) f2015.machineRenderer()).conditionProfile());
    }

    @Test
    public void resetIsSampleDeterministic() {
        MachineImperfectionDsp renderer = new MachineImperfectionDsp(
                SAMPLE_RATE, MachineConditionProfile.livedIn(), 0x12345678L);
        float[] source = stereoTone(2_048, 997f, 0.57f);
        float[] first = source.clone();
        float[] second = source.clone();
        renderer.process(first, first.length / 2);
        renderer.reset();
        renderer.process(second, second.length / 2);
        assertArrayEquals(first, second, 0f);
    }

    @Test
    public void livedInUnitStaysContinuousFiniteAndInsideHealthyGainBounds() {
        int frames = SAMPLE_RATE * 4;
        float[] audio = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float time = frame / (float) SAMPLE_RATE;
            float value = 0.48f * (float) Math.sin(2.0 * Math.PI * 440.0 * time)
                    + 0.17f * (float) Math.sin(2.0 * Math.PI * 3_173.0 * time);
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value;
        }
        MachineImperfectionDsp renderer = new MachineImperfectionDsp(
                SAMPLE_RATE, MachineConditionProfile.livedIn(), 0x534f4e59L);
        renderer.process(audio, frames);

        double energy = 0.0;
        float peak = 0f;
        int start = SAMPLE_RATE / 5 * 2;
        for (int sample = 0; sample < audio.length; sample++) {
            assertTrue(Float.isFinite(audio[sample]));
            peak = Math.max(peak, Math.abs(audio[sample]));
            if (sample >= start) {
                energy += audio[sample] * audio[sample];
            }
        }
        double rms = Math.sqrt(energy / (audio.length - start));
        assertTrue("Healthy tolerance unexpectedly muted the programme", rms > 0.30);
        assertTrue("Healthy tolerance produced a fault-like spike", peak < 0.78f);
    }

    @Test
    public void headToleranceIsSubtleAtMidbandAndMoreVisibleAtTreble() {
        double midDifference = channelDifferenceDb(1_000f);
        double trebleDifference = channelDifferenceDb(10_000f);
        assertTrue("Midband balance tolerance was missing: " + midDifference,
                midDifference > 0.18 && midDifference < 0.55);
        assertTrue("Azimuth/head sensitivity should remain frequency-shaped: mid="
                        + midDifference + " treble=" + trebleDifference,
                trebleDifference > midDifference + 0.08 && trebleDifference < 1.2);
    }

    private static double channelDifferenceDb(float frequency) {
        int frames = SAMPLE_RATE * 2;
        float[] audio = stereoTone(frames, frequency, 0.5f);
        MachineImperfectionDsp renderer = new MachineImperfectionDsp(
                SAMPLE_RATE, MachineConditionProfile.livedIn(), 0L);
        renderer.process(audio, frames);
        double left = 0.0;
        double right = 0.0;
        int startFrame = SAMPLE_RATE / 4;
        for (int frame = startFrame; frame < frames; frame++) {
            float l = audio[frame * 2];
            float r = audio[frame * 2 + 1];
            left += l * l;
            right += r * r;
        }
        return Math.abs(10.0 * Math.log10(left / right));
    }

    private static float[] stereoTone(int frames, float frequency, float amplitude) {
        float[] result = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = amplitude * (float) Math.sin(
                    2.0 * Math.PI * frequency * frame / SAMPLE_RATE);
            result[frame * 2] = value;
            result[frame * 2 + 1] = value;
        }
        return result;
    }
}
