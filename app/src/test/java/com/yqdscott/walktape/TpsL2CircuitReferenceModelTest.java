package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class TpsL2CircuitReferenceModelTest {
    private static final int SAMPLE_RATE = 48_000;

    @Test
    public void revisedSchematicHasCompleteTypedComponentCoverage() {
        Set<String> references = new HashSet<>();
        for (TpsL2Schematic.Part part : TpsL2Schematic.revisedModelParts()) {
            assertTrue("Duplicate component " + part.reference, references.add(part.reference));
            assertNotNull("Every part needs an evidence classification", part.evidence);
            assertNotNull("Every part needs a model role", part.role);
        }
        assertEquals(50, TpsL2Schematic.count(TpsL2Schematic.Kind.RESISTOR));
        assertEquals(42, TpsL2Schematic.count(TpsL2Schematic.Kind.CAPACITOR));
        assertEquals(5, TpsL2Schematic.count(TpsL2Schematic.Kind.TRANSISTOR));
        assertEquals(5, TpsL2Schematic.count(TpsL2Schematic.Kind.IC_MACRO));
        assertTrue("Revised schematic transcription unexpectedly small",
                TpsL2Schematic.revisedModelParts().size() >= 125);
    }

    @Test
    public void serviceManualComponentValuesRemainExact() {
        assertEquals(220e-6, TpsL2Schematic.part("C901").value, 1e-12);
        assertEquals(47e-6, TpsL2Schematic.part("C109").value, 1e-12);
        assertEquals(0.0022e-6, TpsL2Schematic.part("C112").value, 1e-15);
        assertEquals(3.9, TpsL2Schematic.part("R801").value, 0.0);
        assertEquals(35e-6, TpsL2Schematic.part("L601").value, 1e-12);
        assertEquals("2SC2458", TpsL2Schematic.part("Q101").device);
        assertEquals("CX183 FG servo", TpsL2Schematic.part("IC601").device);
        assertEquals(TpsL2Schematic.Evidence.ENGINEERING_PRIOR,
                TpsL2Schematic.part("P-FLYWHEEL-J").evidence);
        assertEquals(TpsL2Schematic.Evidence.ENGINEERING_PRIOR,
                TpsL2Schematic.part("P-Q-IS").evidence);
        assertEquals(TpsL2Schematic.Evidence.ENGINEERING_PRIOR,
                TpsL2Schematic.part("P-SERVO-KP").evidence);
        assertEquals(TpsL2Schematic.Evidence.ENGINEERING_PRIOR,
                TpsL2Schematic.part("P-CX184-RO").evidence);
    }

    @Test
    public void headphoneCouplingCornerFollowsC116AndR801() {
        assertEquals(18.6, TpsL2CircuitReferenceModel.outputCouplingCornerHertz(35.0), 0.25);
        assertEquals(4, TpsL2CircuitReferenceModel.oversampleFactor());
    }

    @Test
    public void componentReferenceIsFiniteAtSupportedRatesAndExtremeInputs() {
        int[] rates = {8_000, 44_100, 48_000, 96_000};
        for (int rate : rates) {
            TpsL2CircuitReferenceModel model = new TpsL2CircuitReferenceModel(rate);
            model.setHighTone(true);
            float[] audio = new float[4_096 * 2];
            for (int sample = 0; sample < audio.length; sample++) {
                audio[sample] = sample % 29 == 0 ? Float.NaN
                        : sample % 31 == 0 ? Float.NEGATIVE_INFINITY
                        : sample % 7 == 0 ? 8f : -8f;
            }
            model.process(audio, audio.length / 2);
            for (float value : audio) {
                assertTrue(Float.isFinite(value));
                assertTrue(Math.abs(value) <= 0.9951f);
            }
            assertTrue(model.preampRailVolts() >= 2.35f);
            assertTrue(model.preampRailVolts() <= 2.61f);
        }
    }

    @Test
    public void referenceIsIndependentOfDecoderBlockPartitioning() {
        TpsL2CircuitReferenceModel whole = new TpsL2CircuitReferenceModel(SAMPLE_RATE);
        TpsL2CircuitReferenceModel split = new TpsL2CircuitReferenceModel(SAMPLE_RATE);
        whole.setHighTone(true);
        split.setHighTone(true);
        float[] a = sine(713f, 0.13f, 16_384);
        float[] b = a.clone();
        whole.process(a, a.length / 2);
        int[] partitions = {1, 13, 128, 2_047, 4_096, 10_099};
        int offset = 0;
        for (int frames : partitions) {
            float[] part = new float[frames * 2];
            System.arraycopy(b, offset * 2, part, 0, part.length);
            split.process(part, frames);
            System.arraycopy(part, 0, b, offset * 2, part.length);
            offset += frames;
        }
        assertEquals(16_384, offset);
        for (int sample = 0; sample < a.length; sample++) {
            assertEquals(a[sample], b[sample], 0f);
        }
    }

    @Test
    public void componentReferenceStaysCalibratedToMeasuredSmallSignalTarget() {
        float[] frequencies = {40f, 100f, 1_000f, 5_000f, 10_000f, 12_500f};
        for (boolean high : new boolean[]{false, true}) {
            for (float frequency : frequencies) {
                TpsL2CircuitReferenceModel reference =
                        new TpsL2CircuitReferenceModel(SAMPLE_RATE);
                reference.setHighTone(high);
                TpsL2Dsp measured = new TpsL2Dsp(SAMPLE_RATE, 0x435243554954L,
                        false, false, false, false);
                measured.setHighTape(high);
                measured.reset();
                float[] a = sine(frequency, 0.006f, SAMPLE_RATE);
                float[] b = a.clone();
                reference.process(a, SAMPLE_RATE);
                measured.process(b, SAMPLE_RATE);
                double deltaDb = 20.0 * Math.log10(rms(a, SAMPLE_RATE / 4)
                        / rms(b, SAMPLE_RATE / 4));
                assertEquals("Reference/target delta at " + frequency + " Hz, high=" + high,
                        0.0, deltaDb, 0.45);
            }
        }
    }

    @Test
    public void hotProgrammeDrawsDownTheSharedPreampRail() {
        TpsL2CircuitReferenceModel quiet = new TpsL2CircuitReferenceModel(SAMPLE_RATE);
        TpsL2CircuitReferenceModel hot = new TpsL2CircuitReferenceModel(SAMPLE_RATE);
        float[] silence = new float[SAMPLE_RATE * 4];
        float[] loud = sine(997f, 0.95f, SAMPLE_RATE * 2);
        quiet.process(silence, silence.length / 2);
        hot.process(loud, loud.length / 2);
        assertTrue("Audio current must couple through the real shared supply",
                hot.preampRailVolts() < quiet.preampRailVolts());
    }

    @Test
    public void transportReferenceCouplesMotorBeltFlywheelAndFg() {
        TpsL2TransportReferenceModel transport = new TpsL2TransportReferenceModel(SAMPLE_RATE);
        transport.setTransportState(TapeTransportState.STARTING);
        transport.setTapePosition(0.5f);
        transport.reset();
        float initial = transport.speedFraction();
        transport.advanceFrames(SAMPLE_RATE * 3);
        assertTrue("Flywheel must accelerate from rest", transport.speedFraction() > initial + 0.75f);
        assertEquals("FG loop must settle close to nominal play speed",
                1f, transport.speedFraction(), 0.12f);
        assertTrue(Float.isFinite(transport.beltTorqueNewtonMetres()));
        assertTrue(Float.isFinite(transport.fgSignal()));
        assertTrue(transport.mainRailVolts() > 2.85f);
        assertTrue(transport.mainRailVolts() < 3.02f);
    }

    @Test
    public void transportReferenceRemainsBoundedAcrossPackAndModeExtremes() {
        float[] positions = {0f, 0.5f, 1f, Float.NaN};
        TapeTransportState[] states = {
                TapeTransportState.PLAYING,
                TapeTransportState.FAST_FORWARD,
                TapeTransportState.REWIND,
                TapeTransportState.PAUSED
        };
        for (float position : positions) {
            for (TapeTransportState state : states) {
                TpsL2TransportReferenceModel transport =
                        new TpsL2TransportReferenceModel(8_000);
                transport.setTapePosition(position);
                transport.setTransportState(state);
                transport.reset();
                transport.advanceFrames(8_000 / 2);
                assertTrue("Non-finite transport speed for " + state,
                        Float.isFinite(transport.speedFraction()));
                assertTrue(Float.isFinite(transport.motorCurrentMilliamps()));
                assertTrue(Math.abs(transport.speedFraction()) < 5.7f);
                assertTrue(transport.mainRailVolts() > 2.8f);
            }
        }
    }

    private static float[] sine(float frequency, float amplitude, int frames) {
        float[] result = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) Math.sin(Math.PI * 2.0 * frequency * frame / SAMPLE_RATE)
                    * amplitude;
            result[frame * 2] = value;
            result[frame * 2 + 1] = value;
        }
        return result;
    }

    private static double rms(float[] stereo, int skipFrames) {
        double sum = 0.0;
        int frames = stereo.length / 2;
        for (int frame = skipFrames; frame < frames; frame++) {
            float value = stereo[frame * 2];
            sum += value * value;
        }
        return Math.sqrt(sum / Math.max(1, frames - skipFrames));
    }
}
