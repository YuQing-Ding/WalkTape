package com.yqdscott.walktape;

/**
 * The HS-JX707's switched replay equaliser, solved from its traced netlist.
 *
 * <p>This is the offline reference that {@code docs/AIWA_HS_JX707_RECONSTRUCTION.md} describes: an
 * exact small-signal solution of the network around IC1's playback amplifier, used to check the
 * trace against the IEC replay characteristic before any realtime reduction is derived from it. It
 * is not a renderer and does no filtering; it answers "what does this network do at this
 * frequency".</p>
 *
 * <p>Connectivity, read off Aiwa's SCHEMATIC DIAGRAM-1 at up to 17x, both channels separately. For
 * the left channel, with PB NF L on pin 7, PB OUT L on pin 8 and V REF on pin 6:</p>
 *
 * <pre>
 *   R7  330k    pin 7 -- pin 8
 *   R9  15k     pin 8 -- X
 *   C17 0.01u   pin 7 -- X                        so C17 + R9 in series bridge R7
 *   R11 22k     X -- Q1 drain ; Q1 source -- pin 8    so Q1 on parallels R11 with R9
 *   R17 18k     pin 7 -- V REF
 *   R13 560 || R15 560, in series with C19 22u, pin 7 -- V REF
 * </pre>
 *
 * <p>The TA8155FN's playback amplifiers carry no internal feedback resistor and PB IN is the
 * non-inverting input, so the closed loop is {@code 1 + Zf/Zg} with everything external. Both
 * impedances reduce to the same first-order rational form, which is why the whole equaliser is a
 * shelf with one pole and one zero:</p>
 *
 * <pre>
 *   R || (Rs + 1/jwC)  =  R * (1 + jw*Rs*C) / (1 + jw*(R+Rs)*C)
 * </pre>
 *
 * <p>Component values come from {@link AiwaHsJx707Schematic} rather than being repeated here, so
 * the netlist and the transcription cannot drift apart. The head and IC1's input network are
 * deliberately <em>not</em> modelled: they depend on the engineering priors for head resistance and
 * inductance, and mixing those into the check would let a prior absorb an error in the trace.</p>
 *
 * <p>On the target this is checked against, see {@link #amplifierTargetMagnitude}. Getting that
 * wrong is what once made a correct trace look falsified.</p>
 */
final class AiwaHsJx707ReplayEq {

    /** IEC bass turnover, shared by every cassette type. */
    static final double IEC_BASS_SECONDS = 3180e-6;

    /** IEC treble time constant for type I ferric, which this machine calls NORMAL. */
    static final double IEC_NORMAL_SECONDS = 120e-6;

    /** IEC treble time constant for type II and type IV, which this machine switches Q1/Q2 for. */
    static final double IEC_METAL_SECONDS = 70e-6;

    private final double r7;
    private final double r9;
    private final double r11;
    private final double r17;
    private final double gainLegResistance;
    private final double c17;
    private final double c19;

    AiwaHsJx707ReplayEq() {
        this("R7", "R9", "R11", "R17", "R13", "R15", "C17", "C19");
    }

    /**
     * Builds the equaliser from an explicit set of designators.
     *
     * <p>The right channel is the same network with the even designators, and reading it from its
     * own crop rather than mirroring the left one is what this constructor exists for.</p>
     */
    AiwaHsJx707ReplayEq(String feedback, String shunt, String metalShunt, String legShunt,
                        String legA, String legB, String couplingCap, String legCap) {
        r7 = AiwaHsJx707Schematic.part(feedback).value;
        r9 = AiwaHsJx707Schematic.part(shunt).value;
        r11 = AiwaHsJx707Schematic.part(metalShunt).value;
        r17 = AiwaHsJx707Schematic.part(legShunt).value;
        double a = AiwaHsJx707Schematic.part(legA).value;
        double b = AiwaHsJx707Schematic.part(legB).value;
        gainLegResistance = parallel(a, b);
        c17 = AiwaHsJx707Schematic.part(couplingCap).value;
        c19 = AiwaHsJx707Schematic.part(legCap).value;
    }

    /** Series resistance bridged by C17: R9 alone, or R9 in parallel with R11 once Q1 conducts. */
    double feedbackShuntResistance(boolean metal) {
        return metal ? parallel(r9, r11) : r9;
    }

    /**
     * The treble time constant this network actually realises.
     *
     * <p>150 us against the standard's 120 us, or 89.2 us against 70 us with Q1 on.</p>
     */
    double trebleTimeConstantSeconds(boolean metal) {
        return feedbackShuntResistance(metal) * c17;
    }

    /**
     * The bass turnover this network actually realises.
     *
     * <p>3450 us against the standard's 3180 us. That one three-component network yields both
     * standard time constants is what makes the trace a finding rather than a fit.</p>
     */
    double bassTimeConstantSeconds(boolean metal) {
        return (r7 + feedbackShuntResistance(metal)) * c17;
    }

    /** Corner below which C19 and R17 roll the bass off, well under Aiwa's 63 Hz spec limit. */
    double gainLegCornerHertz() {
        return 1.0 / (2.0 * Math.PI * gainLegResistance * c19);
    }

    /** Gain at DC, {@code 1 + R7/R17}, where both capacitors are open circuits. */
    double directGain() {
        return 1.0 + r7 / r17;
    }

    /**
     * The two poles of {@code 1 + Zf/Zg}, as time constants in seconds.
     *
     * <p>They are just the two denominators: the feedback shelf's {@code (R7+Rs)*C17} and the gain
     * leg's {@code (R13||R15)*C19}. Adding one to a ratio cannot move its poles.</p>
     */
    double[] poleTimeConstantsSeconds(boolean metal) {
        return new double[]{
                (r7 + feedbackShuntResistance(metal)) * c17,
                gainLegResistance * c19
        };
    }

    /**
     * The two zeros of {@code 1 + Zf/Zg}, as time constants in seconds, longest first.
     *
     * <p>Adding one to {@code Zf/Zg} does move the zeros, so these are solved rather than read off
     * the network: the numerator is the quadratic {@code C*s^2 + B*s + (1+k)}. The roots are taken
     * in the numerically stable form, because they differ by four orders of magnitude and the naive
     * quadratic formula loses the short one to cancellation.</p>
     */
    double[] zeroTimeConstantsSeconds(boolean metal) {
        double a = feedbackShuntResistance(metal) * c17;
        double b = (r7 + feedbackShuntResistance(metal)) * c17;
        double e = (r17 + gainLegResistance) * c19;
        double f = gainLegResistance * c19;
        double k = r7 / r17;

        double quadratic = b * f + k * a * e;
        double linear = (b + f) + k * (a + e);
        double constant = 1.0 + k;

        double discriminant = linear * linear - 4.0 * quadratic * constant;
        if (discriminant < 0.0) {
            throw new IllegalStateException("Replay equaliser zeros are not real");
        }
        double q = -0.5 * (linear + Math.signum(linear) * Math.sqrt(discriminant));
        double first = q / quadratic;
        double second = constant / q;
        double longest = Math.max(-1.0 / first, -1.0 / second);
        double shortest = Math.min(-1.0 / first, -1.0 / second);
        return new double[]{longest, shortest};
    }

    /** Closed-loop magnitude from PB IN to PB OUT, ignoring the head and the input network. */
    double closedLoopMagnitude(double hertz, boolean metal) {
        double w = 2.0 * Math.PI * hertz;
        double[] zf = shelfImpedance(r7, feedbackShuntResistance(metal), c17, w);
        double[] zg = shelfImpedance(r17, gainLegResistance, c19, w);
        double[] ratio = divide(zf, zg);
        return Math.hypot(1.0 + ratio[0], ratio[1]);
    }

    /**
     * The IEC replay characteristic as it is normally tabulated: flux on the tape.
     *
     * <p>Rises below 50 Hz, flat through the midband, falls above 1/(2*pi*tau).</p>
     */
    static double iecFluxMagnitude(double hertz, double trebleSeconds) {
        double w = 2.0 * Math.PI * hertz;
        double bass = Math.sqrt(1.0 + 1.0 / ((w * IEC_BASS_SECONDS) * (w * IEC_BASS_SECONDS)));
        double treble = Math.sqrt(1.0 + (w * trebleSeconds) * (w * trebleSeconds));
        return bass / treble;
    }

    /**
     * What the amplifier itself has to realise, measured from head EMF.
     *
     * <p>This is <em>not</em> {@link #iecFluxMagnitude}. That curve describes the flux recorded on
     * the tape; the head turns flux into EMF and differentiates it on the way, so for a flat output
     * the amplifier must supply the reciprocal of the flux curve <em>and</em> undo the head's jw.
     * Comparing the closed loop directly against the flux curve drops that factor of w and inverts
     * the shape of the answer, which is exactly why this network once looked wrong.</p>
     */
    static double amplifierTargetMagnitude(double hertz, double trebleSeconds) {
        return 1.0 / (2.0 * Math.PI * hertz * iecFluxMagnitude(hertz, trebleSeconds));
    }

    /** Closed-loop response at {@code hertz} relative to 1 kHz, in decibels. */
    double relativeResponseDb(double hertz, boolean metal) {
        return 20.0 * Math.log10(closedLoopMagnitude(hertz, metal)
                / closedLoopMagnitude(1_000.0, metal));
    }

    /** The amplifier target at {@code hertz} relative to 1 kHz, in decibels. */
    static double relativeTargetDb(double hertz, double trebleSeconds) {
        return 20.0 * Math.log10(amplifierTargetMagnitude(hertz, trebleSeconds)
                / amplifierTargetMagnitude(1_000.0, trebleSeconds));
    }

    /** {@code R || (Rs + 1/jwC)}, as {@code R * (1 + jw*Rs*C) / (1 + jw*(R+Rs)*C)}. */
    private static double[] shelfImpedance(double shuntedBy, double series, double farads,
                                           double w) {
        double zero = w * series * farads;
        double pole = w * (shuntedBy + series) * farads;
        double[] quotient = divide(new double[]{1.0, zero}, new double[]{1.0, pole});
        return new double[]{shuntedBy * quotient[0], shuntedBy * quotient[1]};
    }

    private static double[] divide(double[] a, double[] b) {
        double denominator = b[0] * b[0] + b[1] * b[1];
        return new double[]{
                (a[0] * b[0] + a[1] * b[1]) / denominator,
                (a[1] * b[0] - a[0] * b[1]) / denominator
        };
    }

    private static double parallel(double a, double b) {
        return a * b / (a + b);
    }
}
