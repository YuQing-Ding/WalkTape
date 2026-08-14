package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Gates the traced replay equaliser against the IEC characteristic.
 *
 * <p>This is the check that {@code docs/AIWA_HS_JX707_RECONSTRUCTION.md} calls the offline
 * reference model. It exists to reject wrong traces: a set of components that reproduces 120 us can
 * always be found by trying combinations, so the netlist is only worth trusting while it keeps
 * agreeing with the standard across the whole band.</p>
 *
 * <p>The netlist it gates was read off Aiwa's own SCHEMATIC DIAGRAM-1 in the service manual PDF at
 * up to 17x, junction dot by junction dot. The audio board layout could not settle it: at 2560 px
 * the copper is 3-4 px wide and split across both sides of the board, and labelling it as connected
 * components gives no answer that survives a threshold sweep.</p>
 */
public class AiwaHsJx707ReplayEqTest {

    /** Third-octave centres across the band Aiwa specifies, plus the extremes either side. */
    private static final double[] BAND = {
            20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
            1_000, 1_250, 1_600, 2_000, 2_500, 3_150, 4_000, 5_000, 6_300, 8_000, 10_000,
            12_500, 16_000
    };

    /** Aiwa's own frequency response spec starts at 63 Hz; below that C19 and R17 roll the bass off. */
    private static final double SPEC_LOW_HERTZ = 63.0;

    @Test
    public void oneNetworkRealisesBothStandardTimeConstants() {
        AiwaHsJx707ReplayEq eq = new AiwaHsJx707ReplayEq();
        assertEquals("R9 * C17 against the standard's 120 us",
                150e-6, eq.trebleTimeConstantSeconds(false), 0.5e-6);
        assertEquals("(R9 || R11) * C17 against the standard's 70 us",
                89.19e-6, eq.trebleTimeConstantSeconds(true), 0.5e-6);
        assertEquals("(R7 + R9) * C17 against the standard's 3180 us",
                3450e-6, eq.bassTimeConstantSeconds(false), 5e-6);
        assertEquals(3389e-6, eq.bassTimeConstantSeconds(true), 5e-6);

        // Both fall out of the same three components, which is what makes the trace a finding
        // rather than a fit. Each is within a quarter of the value Aiwa was aiming at.
        assertRelativelyWithin(AiwaHsJx707ReplayEq.IEC_BASS_SECONDS,
                eq.bassTimeConstantSeconds(false), 0.10, "bass turnover");
        assertRelativelyWithin(AiwaHsJx707ReplayEq.IEC_NORMAL_SECONDS,
                eq.trebleTimeConstantSeconds(false), 0.26, "normal treble");
        assertRelativelyWithin(AiwaHsJx707ReplayEq.IEC_METAL_SECONDS,
                eq.trebleTimeConstantSeconds(true), 0.28, "metal treble");
    }

    @Test
    public void switchingQ1ShortensTheTrebleConstantWithoutMovingTheBassTurnover() {
        AiwaHsJx707ReplayEq eq = new AiwaHsJx707ReplayEq();
        assertTrue("Q1 must shorten the treble constant, not lengthen it",
                eq.trebleTimeConstantSeconds(true) < eq.trebleTimeConstantSeconds(false));
        assertEquals("R11 shunts R9, so the bass turnover barely moves",
                eq.bassTimeConstantSeconds(false), eq.bassTimeConstantSeconds(true), 70e-6);
        assertEquals("R11 in parallel with R9", 8_919.0, eq.feedbackShuntResistance(true), 1.0);
    }

    @Test
    public void closedLoopTracksTheIecCharacteristicAcrossAiwasSpecifiedBand() {
        AiwaHsJx707ReplayEq eq = new AiwaHsJx707ReplayEq();
        assertEquals("normal", 1.29, worstErrorDb(eq, false, SPEC_LOW_HERTZ), 0.16);
        assertEquals("metal", 1.85, worstErrorDb(eq, true, SPEC_LOW_HERTZ), 0.16);
    }

    /**
     * Below the specified band the network deliberately gives up bass, and by a bounded amount.
     *
     * <p>C19's corner is 25.8 Hz and R17 backs it up. The residual is a real rolloff rather than a
     * tracing error, so it is pinned rather than excused.</p>
     */
    @Test
    public void theResidualBelowTheSpecifiedBandIsABoundedBassRolloff() {
        AiwaHsJx707ReplayEq eq = new AiwaHsJx707ReplayEq();
        assertEquals(25.84, eq.gainLegCornerHertz(), 0.05);
        for (boolean metal : new boolean[]{false, true}) {
            double tau = trebleFor(metal);
            for (double hertz : new double[]{20, 31.5, 50}) {
                double error = eq.relativeResponseDb(hertz, metal)
                        - AiwaHsJx707ReplayEq.relativeTargetDb(hertz, tau);
                assertTrue("Below 63 Hz the circuit must fall short of the target, not exceed it,"
                        + " at " + hertz + " Hz metal=" + metal, error < 0.0);
                assertTrue("Bass rolloff is deeper than the trace can account for at "
                        + hertz + " Hz metal=" + metal, error > -5.0);
            }
        }
    }

    /**
     * Through the specified band the response falls, as a replay equaliser's must.
     *
     * <p>It is not monotonic all the way down: C19 and R17 turn the bass boost over below about
     * 31.5 Hz, so the response peaks there and drops again towards 20 Hz. That turnover is the
     * deliberate rolloff, and it is asserted rather than sidestepped.</p>
     */
    @Test
    public void theResponseFallsThroughTheBandAndTurnsOverBelowIt() {
        AiwaHsJx707ReplayEq eq = new AiwaHsJx707ReplayEq();
        for (boolean metal : new boolean[]{false, true}) {
            for (int index = 1; index < BAND.length; index++) {
                if (BAND[index - 1] < 40.0) {
                    continue;
                }
                double previous = eq.closedLoopMagnitude(BAND[index - 1], metal);
                double current = eq.closedLoopMagnitude(BAND[index], metal);
                assertTrue("Gain rose from " + BAND[index - 1] + " Hz to " + BAND[index]
                        + " Hz, metal=" + metal, current <= previous);
            }
            assertTrue("C19 and R17 must turn the bass over below the specified band, metal="
                            + metal,
                    eq.closedLoopMagnitude(20.0, metal) < eq.closedLoopMagnitude(31.5, metal));
            assertTrue("The turnover must stay below Aiwa's 63 Hz limit, metal=" + metal,
                    eq.closedLoopMagnitude(SPEC_LOW_HERTZ, metal)
                            < eq.closedLoopMagnitude(31.5, metal));
            assertTrue("Metal must sit below normal at 10 kHz",
                    eq.relativeResponseDb(10_000, true) < eq.relativeResponseDb(10_000, false));
        }
    }

    /**
     * The right channel is the same network, read from its own crop rather than mirrored.
     */
    @Test
    public void bothChannelsSolveIdentically() {
        AiwaHsJx707ReplayEq left = new AiwaHsJx707ReplayEq();
        AiwaHsJx707ReplayEq right = new AiwaHsJx707ReplayEq(
                "R8", "R10", "R12", "R18", "R14", "R16", "C18", "C20");
        for (boolean metal : new boolean[]{false, true}) {
            for (double hertz : BAND) {
                assertEquals("Channels diverge at " + hertz + " Hz, metal=" + metal,
                        left.closedLoopMagnitude(hertz, metal),
                        right.closedLoopMagnitude(hertz, metal), 1e-9);
            }
        }
    }

    /**
     * Regression guard for the mistake that once made this correct trace look falsified.
     *
     * <p>{@link AiwaHsJx707ReplayEq#iecFluxMagnitude} is the flux on the tape. The head
     * differentiates flux into EMF, so the amplifier's target is the reciprocal of that curve with
     * the head's jw undone, not the curve itself. Checking the closed loop against the raw flux
     * curve drops a factor of w and inverts the shape of the answer by more than 16 dB. If someone
     * ever "simplifies" the target back to the flux curve, this fails instead of the netlist
     * getting blamed again.</p>
     */
    @Test
    public void theFluxCurveIsNotTheAmplifierTarget() {
        AiwaHsJx707ReplayEq eq = new AiwaHsJx707ReplayEq();
        for (boolean metal : new boolean[]{false, true}) {
            double tau = trebleFor(metal);
            double worst = 0.0;
            for (double hertz : BAND) {
                double flux = 20.0 * Math.log10(
                        AiwaHsJx707ReplayEq.iecFluxMagnitude(hertz, tau)
                                / AiwaHsJx707ReplayEq.iecFluxMagnitude(1_000.0, tau));
                worst = Math.max(worst, Math.abs(eq.relativeResponseDb(hertz, metal) - flux));
            }
            assertTrue("The flux curve must not be mistakable for the amplifier target,"
                    + " metal=" + metal, worst > 10.0);
        }
        // The two targets differ by exactly the head's differentiation: at every frequency the
        // amplifier target and the flux curve, both taken relative to 1 kHz, must add up to
        // -20*log10(f/1kHz), which is 6 dB per octave.
        for (double hertz : BAND) {
            double amplifier = AiwaHsJx707ReplayEq.relativeTargetDb(hertz, 120e-6);
            double flux = 20.0 * Math.log10(AiwaHsJx707ReplayEq.iecFluxMagnitude(hertz, 120e-6)
                    / AiwaHsJx707ReplayEq.iecFluxMagnitude(1_000.0, 120e-6));
            assertEquals("The head's jw is the whole difference between the two targets at "
                            + hertz + " Hz",
                    -20.0 * Math.log10(hertz / 1_000.0), amplifier + flux, 1e-9);
        }
    }

    private static double worstErrorDb(AiwaHsJx707ReplayEq eq, boolean metal, double fromHertz) {
        double tau = trebleFor(metal);
        double worst = 0.0;
        for (double hertz : BAND) {
            if (hertz < fromHertz) {
                continue;
            }
            worst = Math.max(worst, Math.abs(eq.relativeResponseDb(hertz, metal)
                    - AiwaHsJx707ReplayEq.relativeTargetDb(hertz, tau)));
        }
        return worst;
    }

    private static double trebleFor(boolean metal) {
        return metal ? AiwaHsJx707ReplayEq.IEC_METAL_SECONDS
                : AiwaHsJx707ReplayEq.IEC_NORMAL_SECONDS;
    }

    private static void assertRelativelyWithin(double standard, double realised, double fraction,
                                               String what) {
        double error = Math.abs(realised - standard) / standard;
        assertTrue(what + " drifted to " + Math.round(error * 100) + "% from the standard",
                error <= fraction);
    }
}
