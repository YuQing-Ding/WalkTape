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
        assertEquals(3, profiles.size());
        assertSame(MachineConditionProfile.calibrated(),
                MachineConditionProfile.forId("unknown"));
        assertSame(MachineConditionProfile.natural(),
                MachineConditionProfile.forId(MachineConditionProfile.NATURAL));
        assertSame(MachineConditionProfile.livedIn(),
                MachineConditionProfile.forId(MachineConditionProfile.LIVED_IN));
        assertTrue(MachineConditionProfile.calibrated().isCalibrated());
        assertFalse(MachineConditionProfile.livedIn().isCalibrated());
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
