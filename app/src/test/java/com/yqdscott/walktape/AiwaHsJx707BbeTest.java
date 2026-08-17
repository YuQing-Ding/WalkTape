package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds the BBE model to the seven licensed datasheets it was assembled from.
 *
 * <p>The XRC5484 Aiwa fitted is undocumented, so unlike the replay equaliser there is no netlist
 * to check against. What there is instead is seven parts built under licence to the same BBE
 * patents, and the constraint they impose together is tighter than any one of them alone. These
 * tests are what stops the model drifting into "a smiley EQ that sounded nice on the day".</p>
 *
 * <p>The load-bearing one is {@link #theFittedCornersPredictASettingTheyWereNotFittedOn}. Exactly
 * one number in the magnitude network is fitted rather than derived, and a fit that only reproduces
 * the points it was fitted to has proved nothing. That test refits the two gains to NJM2155's high
 * switch settings, leaves the corners alone, and requires the midband to land inside NJM2155's own
 * published window — which no part of the fit was told about.</p>
 */
public class AiwaHsJx707BbeTest {
    private static final int SAMPLE_RATE = 48_000;
    private static final double TWO_PI = Math.PI * 2.0;

    /** NJM2155's switch settings, with the min/typ/max windows its table prints. */
    private static final double NJM_LOW_CONTOUR_DB = 2.5;
    private static final double NJM_HIGH_CONTOUR_DB = 5.5;
    private static final double NJM_LOW_PROCESS_DB = 6.0;
    private static final double NJM_HIGH_PROCESS_DB = 9.0;
    private static final double NJM_MIDBAND_MIN_DB = 0.0;
    private static final double NJM_MIDBAND_MAX_DB = 1.2;

    /**
     * The two split frequencies, which four of the seven parts agree on to the last digit.
     *
     * <p>BH3868BFS prints the formula outright. NJM2155, NJW1146, NJW1147 and NJW1164 each fit
     * 33 nF and 3.3 nF against the same 21.5k the pins present, so they arrive at the same pair
     * without saying so. Agreement across four vendors' parts is what makes these family constants
     * rather than one chip's choice.</p>
     */
    @Test
    public void theSplitFrequenciesAreTheOnesFourDatasheetsAgreeOn() {
        AiwaHsJx707Bbe bbe = new AiwaHsJx707Bbe();
        assertEquals(224.3, bbe.lowSplitHertz(), 0.5);
        assertEquals(2_243.2, bbe.highSplitHertz(), 5.0);
        assertEquals(bbe.lowSplitHertz() * 10.0, bbe.highSplitHertz(), 1.0);

        // BH3868BFS quotes 224 Hz and 2.24 kHz from these exact capacitors.
        assertEquals(224.3, AiwaHsJx707Bbe.splitCornerHertz(33e-9), 0.5);
        assertEquals(2_243.2, AiwaHsJx707Bbe.splitCornerHertz(3.3e-9), 5.0);

        // The family's band edges, stated in words by BD3860K and BH3868BFS alike, bracket them.
        assertTrue("the low split must sit inside the 20..150 Hz bass band's upper reach",
                bbe.lowSplitHertz() > 150.0 && bbe.lowSplitHertz() < 400.0);
        assertTrue("the high split is the stated 2.4 kHz mid/treble edge",
                Math.abs(bbe.highSplitHertz() - 2_400.0) < 200.0);
    }

    /**
     * The upper limit of the process band is fixed inside the JRC parts and is not modelled.
     *
     * <p>Same treatment as the output stage's Zobel and chip coil: the omission is only defensible
     * while the corner really is out of band, so it is pinned instead of passed over.</p>
     */
    @Test
    public void theFixedUpperCornerIsFarOutsideEverySupportedSampleRate() {
        assertEquals(60_252.0, AiwaHsJx707Bbe.FIXED_UPPER_CORNER_HERTZ, 300.0);
        assertTrue("an upper corner inside the band would have to be modelled",
                AiwaHsJx707Bbe.FIXED_UPPER_CORNER_HERTZ > 96_000 / 2.0);
    }

    /**
     * NJM2155 at its low settings, which is the only complete constraint set of the seven.
     *
     * <p>It is the one sheet that publishes a window at a midband frequency as well as at both
     * band edges. Three constraints, three unknowns, so this test cannot fail while the fit is
     * intact — its job is to fail if anyone changes a constant without redoing the fit.</p>
     */
    @Test
    public void theMagnitudeNetworkLandsInsideNjm2155sPublishedWindows() {
        AiwaHsJx707Bbe bbe = new AiwaHsJx707Bbe(NJM_LOW_CONTOUR_DB, 20.0,
                NJM_LOW_PROCESS_DB, 20_000.0);
        assertInside("20 Hz, LO CONTOUR low", bbe.responseDb(20.0), 1.5, 3.5);
        assertInside("1 kHz", bbe.responseDb(1_000.0), NJM_MIDBAND_MIN_DB, NJM_MIDBAND_MAX_DB);
        assertInside("20 kHz, PROCESS low", bbe.responseDb(20_000.0), 5.0, 7.0);
        assertEquals(0.6, bbe.responseDb(1_000.0), 0.15);
    }

    /**
     * The check that the one fitted number is not circular.
     *
     * <p>The process corner was fitted on NJM2155's <em>low</em> switch settings. Here the two band
     * gains alone are refitted to its high settings, the corners staying exactly where they were,
     * and the midband is required to stay inside the same published window. Nothing in the original
     * fit knew about the high settings, so this is a prediction and it is allowed to fail.</p>
     */
    @Test
    public void theFittedCornersPredictASettingTheyWereNotFittedOn() {
        AiwaHsJx707Bbe low = new AiwaHsJx707Bbe(NJM_LOW_CONTOUR_DB, 20.0,
                NJM_LOW_PROCESS_DB, 20_000.0);
        AiwaHsJx707Bbe high = new AiwaHsJx707Bbe(NJM_HIGH_CONTOUR_DB, 20.0,
                NJM_HIGH_PROCESS_DB, 20_000.0);

        assertEquals("the corners must not move between settings",
                low.processCornerHertz(), high.processCornerHertz(), 1e-9);
        assertEquals("nor must the split",
                low.lowSplitHertz(), high.lowSplitHertz(), 1e-9);

        assertInside("20 Hz, LO CONTOUR high", high.responseDb(20.0), 4.5, 6.5);
        assertInside("20 kHz, PROCESS high", high.responseDb(20_000.0), 8.0, 10.0);
        assertInside("1 kHz at the high settings, predicted not fitted",
                high.responseDb(1_000.0), NJM_MIDBAND_MIN_DB, NJM_MIDBAND_MAX_DB);
    }

    /** The shipped machine: BD3860K's fixed lo contour and the declared process prior. */
    @Test
    public void theShippedSettingIsBd3860ksContourAndTheDeclaredProcessPrior() {
        AiwaHsJx707Bbe bbe = new AiwaHsJx707Bbe();
        assertEquals("BD3860K lo contour, 5 dB typ at 100 Hz",
                5.0, bbe.responseDb(100.0), 0.05);
        assertInside("inside BD3860K's own 3..7 dB window", bbe.responseDb(100.0), 3.0, 7.0);

        double prior = AiwaHsJx707Schematic.part("P-BBE-PROCESS-DB").value;
        assertEquals("the prior is what reaches 10 kHz", prior, bbe.responseDb(10_000.0), 0.05);
        assertEquals(6.0, prior, 1e-9);
        assertEquals(AiwaHsJx707Schematic.Evidence.ENGINEERING_PRIOR,
                AiwaHsJx707Schematic.part("P-BBE-PROCESS-DB").evidence);

        // Even with the larger contour the midband stays inside NJM2155's window.
        assertInside("1 kHz on the shipped setting",
                bbe.responseDb(1_000.0), NJM_MIDBAND_MIN_DB, NJM_MIDBAND_MAX_DB);
    }

    /**
     * The shape every published plot shows: a broad shallow floor between the two bands.
     *
     * <p>This is what rules out the second-order arrangement. Two second-order boost paths invert
     * in their stopbands and subtract, which digs the midband to about -1.9 dB — a hole no BBE
     * plot has. First-order paths meet the through path in quadrature, so the sum can approach
     * 0 dB between the bands but never dive under it.</p>
     */
    @Test
    public void theResponseFloorSitsBetweenTheBandsAndNeverGoesBelowUnity() {
        AiwaHsJx707Bbe bbe = new AiwaHsJx707Bbe();
        double floorDb = Double.MAX_VALUE;
        double floorHertz = 0.0;
        for (double hertz = 20.0; hertz <= 20_000.0; hertz *= 1.005) {
            double db = bbe.responseDb(hertz);
            assertTrue("BBE never cuts: " + hertz + " Hz gave " + db + " dB", db > -0.05);
            if (db < floorDb) {
                floorDb = db;
                floorHertz = hertz;
            }
        }
        assertTrue("the floor belongs between the bands, not at an edge: " + floorHertz + " Hz",
                floorHertz > 400.0 && floorHertz < 1_400.0);
        assertTrue("and it must come close to unity: " + floorDb + " dB", floorDb < 1.5);

        // Both bands really are boosted, so this is a V and not a tilt.
        assertTrue(bbe.responseDb(50.0) > 4.0);
        assertTrue(bbe.responseDb(16_000.0) > 5.0);
    }

    /**
     * The half of BBE that is not equalisation.
     *
     * <p>BD3860K and BH3868BFS both state it in words: 180 degrees between bass and middle, 360
     * between bass and treble. Two first-order all-pass sections at the two split frequencies
     * produce exactly that, and NJM2155's phase plot — which sweeps about 340 degrees across the
     * audio band — corroborates it independently.</p>
     */
    @Test
    public void thePhaseNetworkPutsTheTrebleAFullTurnBehindTheBass() {
        AiwaHsJx707Bbe bbe = new AiwaHsJx707Bbe();

        assertTrue("nothing has turned over yet at 20 Hz", bbe.phaseDegrees(20.0) > -20.0);

        // Halfway in log frequency between the splits is where the midrange sits.
        double midband = Math.sqrt(bbe.lowSplitHertz() * bbe.highSplitHertz());
        assertEquals("the mid must be half a turn behind the bass",
                -180.0, bbe.phaseDegrees(midband), 2.0);

        assertTrue("and the treble a full turn, less the sample-rate-free asymptote",
                bbe.phaseDegrees(20_000.0) < -340.0);
        assertTrue("but never past one full turn", bbe.phaseDegrees(200_000.0) > -360.0);
        assertEquals(-360.0, bbe.phaseDegrees(1e7), 1.0);

        // Each split contributes one half turn, which is what makes it two sections and not one.
        assertEquals(-90.0 - 2.0 * Math.toDegrees(Math.atan(
                        bbe.lowSplitHertz() / bbe.highSplitHertz())),
                bbe.phaseDegrees(bbe.lowSplitHertz()), 0.01);
    }

    /**
     * BBE II is level-dependent, and this is where the dBV axis is tied to full scale.
     *
     * <p>BD3860K states the threshold in words and its Fig 16 shows every process curve flat by
     * -20 dBV. Converting those to full scale needs the part's own maximum output, 2.5 Vrms, which
     * is why the conversion is derived rather than picked.</p>
     */
    @Test
    public void theLevelLawFollowsBd3860ksThresholdAndItsFigure16() {
        assertEquals(7.96, AiwaHsJx707Bbe.fullScaleDbv(), 0.02);
        assertEquals(-47.96, AiwaHsJx707Bbe.thresholdDbFs(), 0.02);
        assertEquals(-27.96, AiwaHsJx707Bbe.fullProcessDbFs(), 0.02);

        assertEquals("shut below the threshold", 0.0,
                AiwaHsJx707Bbe.processFraction(-60.0), 1e-9);
        assertEquals("still shut at the threshold", 0.0,
                AiwaHsJx707Bbe.processFraction(AiwaHsJx707Bbe.thresholdDbFs()), 1e-9);
        assertEquals("half open halfway, in decibels", 0.5,
                AiwaHsJx707Bbe.processFraction(-37.96), 1e-3);
        assertEquals("fully open by -20 dBV", 1.0,
                AiwaHsJx707Bbe.processFraction(AiwaHsJx707Bbe.fullProcessDbFs()), 1e-9);
        assertEquals("and no further", 1.0, AiwaHsJx707Bbe.processFraction(0.0), 1e-9);

        // With the VCA shut only the lo contour survives, which is BD3860K's stated behaviour:
        // the bass boost is internal and fixed, the treble boost is the part that is controlled.
        AiwaHsJx707Bbe bbe = new AiwaHsJx707Bbe();
        assertEquals(5.0, bbe.responseDb(100.0, 0.0), 0.05);
        assertEquals("no treble boost with the VCA shut",
                0.0, bbe.responseDb(10_000.0, 0.0), 0.05);
        assertTrue("and the full boost with it open",
                bbe.responseDb(10_000.0, 1.0) > 5.9);
    }

    /**
     * The realtime stage has to be the reference model, not a second implementation of it.
     *
     * <p>Measured as the difference between two runs of the whole renderer, so it also proves the
     * stage is wired into the chain rather than merely constructed. The tone is loud enough that
     * the detector is fully open throughout, which is the condition {@code responseDb} describes.
     * </p>
     */
    @Test
    public void theRealtimeStageReproducesTheAnalogueReference() {
        AiwaHsJx707Bbe reference = new AiwaHsJx707Bbe();
        double worst = 0.0;
        double worstHertz = 0.0;
        for (float hertz : new float[]{63f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f}) {
            double measured = bbeGainDb(hertz);
            double expected = reference.responseDb(hertz);
            double error = Math.abs(measured - expected);
            if (error > worst) {
                worst = error;
                worstHertz = hertz;
            }
        }
        assertTrue("realtime stage departs from its own reference by " + worst
                + " dB at " + worstHertz + " Hz", worst < 0.35);
    }

    /** Switched off is what the machine has always shipped as, so it stays the default. */
    @Test
    public void bbeIsOffUntilItIsSwitchedOn() {
        AiwaHsJx707Dsp renderer = new AiwaHsJx707Dsp(SAMPLE_RATE, 7L, false, false, false);
        assertFalse(renderer.isBbeEnabled());

        float[] off = sine(8_000f, 0.15f);
        renderer.reset();
        renderer.process(off, SAMPLE_RATE);

        renderer.setBbeEnabled(true);
        assertTrue(renderer.isBbeEnabled());
        float[] on = sine(8_000f, 0.15f);
        renderer.reset();
        renderer.process(on, SAMPLE_RATE);

        double delta = 20.0 * Math.log10(rms(on) / rms(off));
        assertTrue("switching BBE on has to be audible in the treble: " + delta + " dB",
                delta > 4.0);
    }

    /**
     * Quiet programme keeps the treble boost shut, which is the point of BBE II.
     *
     * <p>ROHM sell it as an S/N feature — "no process control in no signal". A model that boosted
     * the treble regardless of level would be a treble control, not BBE II.</p>
     */
    @Test
    public void theTrebleBoostStaysShutOnQuietProgramme() {
        double loud = bbeGainDb(8_000f, 0.15f);
        double quiet = bbeGainDb(8_000f, 0.0009f);
        assertTrue("loud programme must get the full boost: " + loud, loud > 5.0);
        assertTrue("quiet programme must get almost none: " + quiet, quiet < 1.5);
        assertTrue("and the two must differ by more than the detector's own ripple",
                loud - quiet > 3.5);
    }

    /**
     * What Aiwa's own capacitors would imply, recorded as a non-conclusion.
     *
     * <p>One of the two lands near a family band edge and the other does not land anywhere near
     * one, which is exactly why the JRC resistance is not carried over onto a different vendor's
     * part. Pinned so that a later pass does not rediscover the tempting half and act on it.</p>
     */
    @Test
    public void aiwasOwnCapacitorsAreReportedButNotUsed() {
        // C59 0.047u would put the bass/mid split at 157 Hz, tantalisingly near the stated 150 Hz.
        assertEquals(157.5, AiwaHsJx707Bbe.impliedCornerHertz("C59"), 1.0);
        // C53 820p would put the mid/treble split at 9 kHz, nowhere near the stated 2.4 kHz.
        assertEquals(9_027.0, AiwaHsJx707Bbe.impliedCornerHertz("C53"), 30.0);

        AiwaHsJx707Bbe bbe = new AiwaHsJx707Bbe();
        assertTrue("the implied high corner must not have been adopted",
                Math.abs(bbe.highSplitHertz() - AiwaHsJx707Bbe.impliedCornerHertz("C53")) > 5_000.0);
        assertEquals("the model uses the family's corner, not Aiwa's capacitor",
                AiwaHsJx707Bbe.splitCornerHertz(AiwaHsJx707Bbe.HIGH_SPLIT_FARADS),
                bbe.highSplitHertz(), 1e-9);
    }

    private static void assertInside(String what, double value, double min, double max) {
        assertTrue(what + " came out at " + value + " dB, outside " + min + ".." + max,
                value >= min && value <= max);
    }

    private static double bbeGainDb(float hertz) {
        return bbeGainDb(hertz, 0.15f);
    }

    /** Gain of the BBE block alone: the same renderer, the same tone, the switch the only change. */
    private static double bbeGainDb(float hertz, float amplitude) {
        return 20.0 * Math.log10(renderedRms(hertz, amplitude, true)
                / renderedRms(hertz, amplitude, false));
    }

    private static double renderedRms(float hertz, float amplitude, boolean bbeOn) {
        AiwaHsJx707Dsp renderer = new AiwaHsJx707Dsp(SAMPLE_RATE, 7L, false, false, false);
        renderer.setHighTape(true);
        renderer.setBbeEnabled(bbeOn);
        renderer.reset();
        float[] audio = sine(hertz, amplitude);
        renderer.process(audio, SAMPLE_RATE);
        return rms(audio);
    }

    private static float[] sine(float hertz, float amplitude) {
        float[] audio = new float[SAMPLE_RATE * 2];
        for (int frame = 0; frame < SAMPLE_RATE; frame++) {
            float value = (float) (Math.sin(TWO_PI * hertz * frame / SAMPLE_RATE) * amplitude);
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value;
        }
        return audio;
    }

    /** Second half only, so the detector and every filter have settled. */
    private static double rms(float[] stereo) {
        int frames = stereo.length / 2;
        double sum = 0.0;
        int count = 0;
        for (int frame = frames / 2; frame < frames; frame++) {
            sum += stereo[frame * 2] * (double) stereo[frame * 2];
            count++;
        }
        return Math.sqrt(sum / Math.max(1, count));
    }
}
