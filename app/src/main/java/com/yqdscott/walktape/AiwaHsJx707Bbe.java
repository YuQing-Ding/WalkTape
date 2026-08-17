package com.yqdscott.walktape;

/**
 * The HS-JX707's BBE processor, modelled from the licensed BBE family rather than from IC4.
 *
 * <p>Aiwa fits an <b>XRC5484</b> for BBE and DSL. The service manual carries no block diagram for
 * it, its external parts land on unnamed pins, and no datasheet for it is in circulation — the
 * reconstruction notes list it as underivable and that has not changed. What <em>has</em> changed
 * is that seven datasheets for other BBE-licensed parts are now in hand, and because every one of
 * them is built under licence to the same BBE Sound patents, the behaviour they agree on is a
 * property of BBE rather than of any one vendor's silicon. That is the same evidence class as
 * {@link DolbyNoiseReductionDsp}: published behaviour, not a traced topology.</p>
 *
 * <h2>Which part the XRC5484 most resembles</h2>
 *
 * <p>ROHM's <b>BD3860K</b>, on four independent grounds:</p>
 *
 * <ul>
 *   <li>It is the only <b>BBE II</b> part of the seven, the generation contemporary with this
 *       1992 machine.</li>
 *   <li>Its process path is built round a capacitor pair — 0.047u from VCA to MIX and a small cap
 *       from MIX to BBOUT — and <em>both</em> values appear in Aiwa's BBE kit, 0.047u and 820p
 *       against ROHM's 0.047u and 470p.</li>
 *   <li>Its lo contour is fixed internally and "cannot be controlled externally", matching a
 *       machine whose only BBE control is the S5 on/off slide switch.</li>
 *   <li>It is level-dependent, with a detector and a VCA on the treble band. The static JRC parts
 *       have no use for the per-channel electrolytics Aiwa fits here; a detector does.</li>
 * </ul>
 *
 * <p>BD3860K alone is not enough, though, because it never states its band-split frequencies as
 * numbers. So this model is assembled from what each sheet is best at:</p>
 *
 * <table>
 *   <caption>Where each constant comes from</caption>
 *   <tr><th>Quantity</th><th>Source</th></tr>
 *   <tr><td>Band edges 20 / 150 / 2.4k / 20k Hz</td>
 *       <td>BD3860K p21 and BH3868BFS p26, word for word the same</td></tr>
 *   <tr><td>Split corners 224 Hz and 2.24 kHz</td>
 *       <td>fc = 1/(2*pi*21.5k*C), with C = 33n and 3.3n. BH3868BFS prints the formula;
 *           NJM2155, NJW1146, NJW1147 and NJW1164 all fit the same two capacitors against the
 *           same 21.5k input resistance, so four parts converge on it</td></tr>
 *   <tr><td>Phase: mid -180 deg, treble -360 deg against bass</td>
 *       <td>BD3860K p21 and BH3868BFS p26; corroborated by NJM2155's phase plot, which sweeps
 *           about 340 deg across the audio band</td></tr>
 *   <tr><td>Process boost corner 4.64 kHz</td>
 *       <td>fitted to NJM2155's three published transfer points, see below</td></tr>
 *   <tr><td>Lo contour 5 dB at 100 Hz</td>
 *       <td>BD3860K, typ of a 3..7 dB window, internally fixed</td></tr>
 *   <tr><td>Level law: threshold -40 dBV, full boost by -20 dBV</td>
 *       <td>BD3860K's text for the threshold, its Fig 16 for where the curves flatten</td></tr>
 *   <tr><td>Detector attack 20 ms, release 1 s</td>
 *       <td>BD3860K Fig 15, internal 20k, against its own Fig 16 test values C = 1u, R2 = 1M</td></tr>
 * </table>
 *
 * <h2>Magnitude and phase are separate networks here, on purpose</h2>
 *
 * <p>A three-band split that applies the published per-band phase offsets and then sums does not
 * work: the bands cancel at their own crossovers, a 36 dB notch at 224 Hz with every band gain at
 * unity. Real BBE plainly does not null its own crossover, so the 180/360 degree figures describe
 * a delay network in the common path rather than differential all-passes across a summing
 * crossover. Modelling them separately also makes each half independently checkable, which is why
 * {@code AiwaHsJx707BbeTest} can gate the magnitude against the datasheet windows and the phase
 * against the published statement without either masking an error in the other.</p>
 *
 * <p>The magnitude network is unity plus a first-order low-boost path and a first-order high-boost
 * path in parallel. First order matters: two second-order paths invert in their stopbands and
 * subtract, which drives the midband to -1.9 dB, well under the floor every published plot shows.
 * First-order paths meet the through path in quadrature instead, so the response can approach 0 dB
 * between the bands but not dive below it. That is exactly the shape BD3860K Fig 14, NJM2155 and
 * NJW1164 all plot.</p>
 *
 * <h2>What is fitted, and against what</h2>
 *
 * <p>Only the process corner. NJM2155 is the one sheet of the seven that publishes a min/typ/max
 * window at a <em>midband</em> frequency as well as at both band edges, so it is the only complete
 * constraint set available: 2.5 dB at 20 Hz, 0.6 dB at 1 kHz, 6.0 dB at 20 kHz with both switches
 * low. The low corner is pinned at the family's 224 Hz rather than fitted, which leaves exactly
 * three unknowns for three constraints and nothing free.</p>
 *
 * <p>The fit is not circular, and the check is this: the corner pair fitted on the low switch
 * settings then <em>predicts</em> the high settings. Refitting only the two gains to 5.5 dB at
 * 20 Hz and 9.0 dB at 20 kHz puts 1 kHz at 1.17 dB, still inside NJM2155's own 0.0..1.2 dB window,
 * which no part of the fit was told about.</p>
 *
 * <h2>The one free choice</h2>
 *
 * <p>How much process boost this machine runs. The JX707 has no BBE level control, so it is a
 * fixed setting, and nothing in Aiwa's documentation says which. The family offers 3, 6, 7, 9, 11
 * and 12 dB across its switchable parts; this model takes 6 dB, near the middle. It is named
 * {@code P-BBE-PROCESS-DB} in the schematic so it can never be quoted as something Aiwa or BBE
 * published.</p>
 */
final class AiwaHsJx707Bbe {

    /**
     * Input resistance the JRC and ROHM parts present at their BBE filter pins.
     *
     * <p>BH3868BFS prints it in the fc formula; NJM2155's terminal description draws the same
     * 21.5k on pins 2, 4, 21 and 23. It is what turns the family's filter capacitors into
     * frequencies.</p>
     */
    static final double SPLIT_RESISTANCE_OHMS = 21.5e3;

    /** Bass/mid split capacitor. 33 nF in BH3868BFS, NJM2155, NJW1146, NJW1147 and NJW1164. */
    static final double LOW_SPLIT_FARADS = 33e-9;

    /** Mid/treble split capacitor. 3.3 nF in the same five parts. */
    static final double HIGH_SPLIT_FARADS = 3.3e-9;

    /**
     * Upper limit of the process band, fixed inside the JRC parts at 1/(2*pi*56.2k*47p).
     *
     * <p>60.3 kHz is above half of every sample rate this renderer runs at, so it is deliberately
     * not modelled — the same treatment the output stage gives its Zobel and chip coil, and the
     * test pins the corner rather than letting the omission pass silently.</p>
     */
    static final double FIXED_UPPER_CORNER_HERTZ = 1.0 / (2.0 * Math.PI * 56.2e3 * 47e-12);

    /** Where the process boost path turns over. Fitted; see the class comment. */
    static final double PROCESS_CORNER_HERTZ = 4_636.6;

    /** BD3860K quotes its lo contour at 100 Hz and its process control at 10 kHz. */
    static final double LO_CONTOUR_REFERENCE_HERTZ = 100.0;
    static final double PROCESS_REFERENCE_HERTZ = 10_000.0;

    /** BD3860K: 5 dB typ, window 3..7 dB, set by an internal circuit and not adjustable. */
    static final double LO_CONTOUR_DB = 5.0;

    /**
     * Level at which BD3860K's detector starts opening the treble VCA, and where it is fully open.
     *
     * <p>The threshold is stated in words — "fixed on about -40dBV (Typ.) by inside circuit" — and
     * the upper end is read off Fig 16, where every process curve has flattened by -20 dBV.</p>
     */
    static final double PROCESS_THRESHOLD_DBV = -40.0;
    static final double PROCESS_FULL_DBV = -20.0;

    /** BD3860K's own maximum output, which is what converts its dBV axis into full scale here. */
    static final double MAXIMUM_OUTPUT_VRMS = 2.5;

    /** Attack is the internal 20k against the detector capacitor; release is the external leg. */
    static final double DETECTOR_ATTACK_SECONDS = 20e-3;
    static final double DETECTOR_RELEASE_SECONDS = 1.0;

    private final double lowSplitHertz;
    private final double highSplitHertz;
    private final double processCornerHertz;
    private final double loContourGain;
    private final double processGain;
    private final double processDb;

    private final double loContourDb;
    private final double loContourReferenceHertz;
    private final double processReferenceHertz;

    AiwaHsJx707Bbe() {
        this(LO_CONTOUR_DB, LO_CONTOUR_REFERENCE_HERTZ,
                AiwaHsJx707Schematic.part("P-BBE-PROCESS-DB").value, PROCESS_REFERENCE_HERTZ);
    }

    /**
     * Any other part's switch setting, which is how the fit is checked for circularity.
     *
     * <p>The corner frequencies do not move: only the two band gains do. A model built this way
     * against NJM2155's low settings has to predict its high ones, and that is a test the fit can
     * fail.</p>
     */
    AiwaHsJx707Bbe(double loContourDb, double loContourReferenceHertz,
                   double processDb, double processReferenceHertz) {
        this.loContourDb = loContourDb;
        this.loContourReferenceHertz = loContourReferenceHertz;
        this.processDb = processDb;
        this.processReferenceHertz = processReferenceHertz;
        lowSplitHertz = splitCornerHertz(LOW_SPLIT_FARADS);
        highSplitHertz = splitCornerHertz(HIGH_SPLIT_FARADS);
        processCornerHertz = PROCESS_CORNER_HERTZ;

        // The two boost paths interact at each other's reference frequency, so solve them
        // together rather than one after the other. Both are monotone in their own gain, so
        // alternating bisection converges; 40 rounds is far more than it needs.
        double lo = 1.0;
        double hi = 1.0;
        for (int round = 0; round < 40; round++) {
            final double currentHigh = hi;
            lo = solveGain(loContourDb,
                    candidate -> magnitudeAt(loContourReferenceHertz, candidate, currentHigh));
            final double currentLow = lo;
            hi = solveGain(processDb,
                    candidate -> magnitudeAt(processReferenceHertz, currentLow, candidate));
        }
        loContourGain = lo;
        processGain = hi;
    }

    private interface GainProbe {
        double magnitudeDb(double candidate);
    }

    private static double solveGain(double targetDb, GainProbe probe) {
        double low = 0.0;
        double high = 64.0;
        for (int step = 0; step < 200; step++) {
            double middle = 0.5 * (low + high);
            if (probe.magnitudeDb(middle) < targetDb) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return 0.5 * (low + high);
    }

    /** The family's split formula: a filter capacitor against the 21.5k the pins present. */
    static double splitCornerHertz(double farads) {
        return 1.0 / (2.0 * Math.PI * SPLIT_RESISTANCE_OHMS * farads);
    }

    double lowSplitHertz() {
        return lowSplitHertz;
    }

    double highSplitHertz() {
        return highSplitHertz;
    }

    double processCornerHertz() {
        return processCornerHertz;
    }

    /** Gain of the low boost path, relative to the through path it is summed with. */
    double loContourPathGain() {
        return loContourGain;
    }

    /** Gain of the high boost path at full VCA opening. */
    double processPathGain() {
        return processGain;
    }

    double processDb() {
        return processDb;
    }

    /**
     * Magnitude of the analogue prototype, in decibels, at full process.
     *
     * <p>This is the curve the realtime stage is checked against, so the two cannot drift.</p>
     */
    double responseDb(double hertz) {
        return magnitudeAt(hertz, loContourGain, processGain);
    }

    /** Magnitude with the treble VCA only partly open, which is what quiet passages see. */
    double responseDb(double hertz, double processFraction) {
        double clamped = Math.max(0.0, Math.min(1.0, processFraction));
        return magnitudeAt(hertz, loContourGain, processGain * clamped);
    }

    private double magnitudeAt(double hertz, double lowGain, double highGain) {
        // Through path, plus a first-order low pass and a first-order high pass in parallel.
        double lowRatio = hertz / lowSplitHertz;
        double lowDenominator = 1.0 + lowRatio * lowRatio;
        double lowReal = lowGain / lowDenominator;
        double lowImaginary = -lowGain * lowRatio / lowDenominator;

        double highRatio = hertz / processCornerHertz;
        double highDenominator = 1.0 + highRatio * highRatio;
        double highReal = highGain * highRatio * highRatio / highDenominator;
        double highImaginary = highGain * highRatio / highDenominator;

        double real = 1.0 + lowReal + highReal;
        double imaginary = lowImaginary + highImaginary;
        return 20.0 * Math.log10(Math.hypot(real, imaginary));
    }

    /**
     * Phase of the realignment network, in degrees.
     *
     * <p>Two first-order all-pass sections at the family's two split frequencies. Each contributes
     * -180 degrees across its corner, so the pair puts the treble a full turn behind the bass and
     * the midrange half a turn — which is the relationship BD3860K and BH3868BFS both state.</p>
     */
    double phaseDegrees(double hertz) {
        return -2.0 * Math.toDegrees(Math.atan(hertz / lowSplitHertz))
                - 2.0 * Math.toDegrees(Math.atan(hertz / highSplitHertz));
    }

    /** Full scale expressed on BD3860K's dBV axis, so its level law can be applied here. */
    static double fullScaleDbv() {
        return 20.0 * Math.log10(MAXIMUM_OUTPUT_VRMS);
    }

    static double thresholdDbFs() {
        return PROCESS_THRESHOLD_DBV - fullScaleDbv();
    }

    static double fullProcessDbFs() {
        return PROCESS_FULL_DBV - fullScaleDbv();
    }

    /**
     * How far the treble VCA is open at a given programme level, 0 shut and 1 fully open.
     *
     * <p>BD3860K's Fig 16 is a smooth S between the two levels; a straight line in decibels is the
     * reading taken here, and it is the only part of the level law not stated in words.</p>
     */
    static double processFraction(double dbFs) {
        double span = fullProcessDbFs() - thresholdDbFs();
        return Math.max(0.0, Math.min(1.0, (dbFs - thresholdDbFs()) / span));
    }

    /**
     * What Aiwa's own BBE capacitors would come to under the family's 21.5k.
     *
     * <p>Reported, not used. The XRC5484 is a different vendor's part and its pin impedances are
     * unknown, so putting JRC's resistance behind Aiwa's capacitors would manufacture evidence
     * rather than find it. It is exposed because the numbers are worth having on record if a real
     * XRC5484 datasheet ever turns up: C59/C60 0.047u lands at 157 Hz, close to the 150 Hz band
     * edge the family names, while C53/C54 820p lands at 9.0 kHz, nowhere near the 2.24 kHz split.
     * One of those is suggestive and the other says the assumption does not transfer.</p>
     */
    static double impliedCornerHertz(String capacitorReference) {
        return splitCornerHertz(AiwaHsJx707Schematic.part(capacitorReference).value);
    }
}
