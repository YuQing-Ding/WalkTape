package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Holds the HS-JX707 transcription to the same standard as the TPS-L2 one.
 *
 * <p>These values come from Aiwa's own main audio circuit diagram and electrical main parts list.
 * The point of pinning them in a test is that a future re-voicing has to change the renderer's
 * reductions rather than quietly edit what the manual says.</p>
 */
public class AiwaHsJx707SchematicTest {

    @Test
    public void everyTranscribedPartIsTypedAndAttributed() {
        Set<String> references = new HashSet<>();
        for (AiwaHsJx707Schematic.Part part : AiwaHsJx707Schematic.audioPathParts()) {
            assertTrue("Duplicate component " + part.reference, references.add(part.reference));
            assertNotNull("Every part needs an evidence classification", part.evidence);
            assertNotNull("Every part needs a model role", part.role);
        }
    }

    @Test
    public void integratedCircuitTypeNumbersMatchTheServiceManual() {
        assertEquals("TA8155FN pre/rec amp",
                AiwaHsJx707Schematic.part("IC1").device);
        assertEquals("NJM2065AM Dolby amp R",
                AiwaHsJx707Schematic.part("IC2").device);
        assertEquals("NJM2065AM Dolby amp L",
                AiwaHsJx707Schematic.part("IC3").device);
        assertEquals("XRC5484 BBE/DSL amp",
                AiwaHsJx707Schematic.part("IC4").device);
        assertEquals("TA7688F(S) main amp",
                AiwaHsJx707Schematic.part("IC5").device);
        assertEquals("CXA1405AM 2.0 remote comparator",
                AiwaHsJx707Schematic.part("IC6").device);
        assertEquals("TB2003-003FN mecha com",
                AiwaHsJx707Schematic.part("IC7").device);
        assertEquals("TPIC326ADB motor governor",
                AiwaHsJx707Schematic.part("IC8").device);
    }

    /**
     * The switched replay equalisation is the heart of this machine's tape-type behaviour.
     *
     * <p>Two 2SK880 FETs shunt the metal-position leg of an otherwise shared network, which is how
     * one preamp serves both the 120 us and 70 us curves.</p>
     */
    @Test
    public void switchedReplayEqualisationNetworkIsExact() {
        assertEquals(330e3, AiwaHsJx707Schematic.part("R7").value, 1.0);
        assertEquals(330e3, AiwaHsJx707Schematic.part("R8").value, 1.0);
        assertEquals(15e3, AiwaHsJx707Schematic.part("R9").value, 1.0);
        assertEquals(15e3, AiwaHsJx707Schematic.part("R10").value, 1.0);
        assertEquals(22e3, AiwaHsJx707Schematic.part("R11").value, 1.0);
        assertEquals(22e3, AiwaHsJx707Schematic.part("R12").value, 1.0);
        assertEquals(560.0, AiwaHsJx707Schematic.part("R13").value, 0.5);
        assertEquals(560.0, AiwaHsJx707Schematic.part("R16").value, 0.5);
        assertEquals(18e3, AiwaHsJx707Schematic.part("R17").value, 1.0);
        assertEquals(18e3, AiwaHsJx707Schematic.part("R18").value, 1.0);
        assertEquals(1000e-12, AiwaHsJx707Schematic.part("C13").value, 1e-15);
        assertEquals(390e-12, AiwaHsJx707Schematic.part("C15").value, 1e-15);
        assertEquals(0.01e-6, AiwaHsJx707Schematic.part("C17").value, 1e-12);
        assertEquals("2SK880(Y) EQ switch L", AiwaHsJx707Schematic.part("Q1").device);
        assertEquals("2SK880(Y) EQ switch R", AiwaHsJx707Schematic.part("Q2").device);
    }

    /**
     * The Dolby network is identical on both channels, which was checked rather than assumed.
     *
     * <p>Reading only the left channel and mirroring it would have carried a misread of R34 as
     * 5.1k into the model; the right channel says 1.1k, matching R33.</p>
     */
    @Test
    public void dolbyNetworkIsIdenticalOnBothChannels() {
        int[] left = {19, 21, 23, 25, 27, 29, 31, 33, 35, 37, 39};
        for (int reference : left) {
            assertEquals("R" + reference + " and R" + (reference + 1) + " must match",
                    AiwaHsJx707Schematic.part("R" + reference).value,
                    AiwaHsJx707Schematic.part("R" + (reference + 1)).value, 1e-9);
        }
        int[] leftCaps = {23, 25, 27, 29, 31, 33, 35, 37, 39, 41, 43, 45, 47, 49};
        for (int reference : leftCaps) {
            assertEquals("C" + reference + " and C" + (reference + 1) + " must match",
                    AiwaHsJx707Schematic.part("C" + reference).value,
                    AiwaHsJx707Schematic.part("C" + (reference + 1)).value, 1e-18);
        }
        assertEquals(1.1e3, AiwaHsJx707Schematic.part("R33").value, 1.0);
        assertEquals(1.1e3, AiwaHsJx707Schematic.part("R34").value, 1.0);
        assertEquals(510e3, AiwaHsJx707Schematic.part("R23").value, 1.0);
        assertEquals(6800e-12, AiwaHsJx707Schematic.part("C35").value, 1e-15);
        assertEquals(6800e-12, AiwaHsJx707Schematic.part("C36").value, 1e-15);
    }

    @Test
    public void bbeAndDslNetworkIsIdenticalOnBothChannels() {
        int[] leftBbe = {51, 53, 55, 57, 59, 61, 63, 65, 67, 69, 71};
        for (int reference : leftBbe) {
            assertEquals("C" + reference + " and C" + (reference + 1) + " must match",
                    AiwaHsJx707Schematic.part("C" + reference).value,
                    AiwaHsJx707Schematic.part("C" + (reference + 1)).value, 1e-18);
        }
        assertEquals(820e-12, AiwaHsJx707Schematic.part("C53").value, 1e-15);
        assertEquals(0.33e-6, AiwaHsJx707Schematic.part("C65").value, 1e-12);
        assertEquals(6.8e3, AiwaHsJx707Schematic.part("R41").value, 1.0);
    }

    @Test
    public void theWholeSignalChainIsPresent() {
        for (AiwaHsJx707Schematic.Role role : new AiwaHsJx707Schematic.Role[]{
                AiwaHsJx707Schematic.Role.HEAD_INPUT,
                AiwaHsJx707Schematic.Role.PREAMP,
                AiwaHsJx707Schematic.Role.PLAYBACK_EQ,
                AiwaHsJx707Schematic.Role.EQ_SWITCH,
                AiwaHsJx707Schematic.Role.DOLBY,
                AiwaHsJx707Schematic.Role.BBE_DSL,
                AiwaHsJx707Schematic.Role.PLSS,
                AiwaHsJx707Schematic.Role.BUFFER,
                AiwaHsJx707Schematic.Role.MUTING,
                AiwaHsJx707Schematic.Role.VOLUME,
                AiwaHsJx707Schematic.Role.POWER_AMPLIFIER,
                AiwaHsJx707Schematic.Role.HEADPHONE_OUTPUT,
                AiwaHsJx707Schematic.Role.MOTOR_GOVERNOR,
                AiwaHsJx707Schematic.Role.TRANSPORT}) {
            assertTrue("No component carries the role " + role,
                    AiwaHsJx707Schematic.countByRole(role) > 0);
        }
        assertTrue("Transcription unexpectedly small",
                AiwaHsJx707Schematic.audioPathParts().size() >= 130);
        assertEquals(8, AiwaHsJx707Schematic.count(AiwaHsJx707Schematic.Kind.IC_MACRO));
        assertEquals(3, AiwaHsJx707Schematic.count(AiwaHsJx707Schematic.Kind.FET));
    }

    @Test
    public void userControlsCarryTheirManualValues() {
        assertEquals(20e3, AiwaHsJx707Schematic.part("VR1").value, 1.0);
        assertEquals(3e3, AiwaHsJx707Schematic.part("SFR1").value, 1.0);
        assertEquals("photo sensor 5164K-F1-Q2", AiwaHsJx707Schematic.part("CP1").device);
        assertEquals("slide SW (BBE)", AiwaHsJx707Schematic.part("S5").device);
        assertEquals("slide SW (DSL)", AiwaHsJx707Schematic.part("S4").device);
        assertEquals("slide SW (PLSS)", AiwaHsJx707Schematic.part("S6").device);
    }

    /**
     * Anything Aiwa did not publish has to be visibly separate from anything it did.
     */
    @Test
    public void engineeringPriorsAreNamedApartFromManualValues() {
        int priors = 0;
        for (AiwaHsJx707Schematic.Part part : AiwaHsJx707Schematic.audioPathParts()) {
            if (part.evidence == AiwaHsJx707Schematic.Evidence.ENGINEERING_PRIOR) {
                priors++;
                assertTrue("A prior must be named P*: " + part.reference,
                        part.reference.startsWith("P-"));
            } else {
                assertTrue("A manual value must not be named P*: " + part.reference,
                        !part.reference.startsWith("P-"));
            }
        }
        assertTrue("The transcription should still be mostly manual values",
                priors * 4 < AiwaHsJx707Schematic.audioPathParts().size());
    }

    @Test
    public void unknownReferencesFailLoudlyRatherThanReturningZero() {
        try {
            AiwaHsJx707Schematic.part("R9999");
            throw new AssertionError("Expected an unknown component to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("R9999"));
        }
    }
}
