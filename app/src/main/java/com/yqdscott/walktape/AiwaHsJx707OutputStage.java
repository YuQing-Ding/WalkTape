package com.yqdscott.walktape;

/**
 * The HS-JX707's headphone output network, solved from its component values.
 *
 * <p>Read off Aiwa's SCHEMATIC DIAGRAM-1 at up to 3.4x around the jack. Each channel leaves IC5
 * and reaches the socket through three things:</p>
 *
 * <pre>
 *   IC5 OUT --+-- R68 4.7 -- C84 0.22u -- ground        Zobel damping
 *             |
 *             +-- C86 220u --- L 3.3uH --- jack         series coupling, then a chip coil
 * </pre>
 *
 * <p>Only one of those three is audible, and this class exists to say so with numbers rather than
 * by omission. Into the rated 32 ohm load the coupling capacitor is a first-order high-pass at
 * about 23 Hz; the Zobel sits at 154 kHz and the chip coil at 1.5 MHz, both far outside any
 * sample rate this renderer runs at, so they are deliberately modelled as no-ops.</p>
 *
 * <p>What this class does <em>not</em> claim is the amplifier itself. Aiwa's block diagram for the
 * TA7688F on page 24 of the manual gives the topology — pin 16 IN to the non-inverting input, pin
 * 15 NF through an internal network, an internal MUTE block — but prints <em>no values</em> on the
 * internal resistors. R65 47k and its opposite number close the loop from OUT back to NF, and
 * without the internal leg the closed-loop gain cannot be derived from anything Aiwa published.
 * Voltage gain and clipping level therefore remain outside the component-derived path, and the
 * renderer's output limiter stays what it always was: a spec-based stand-in.</p>
 *
 * <p>The load is {@code P-HP-LOAD}, an engineering prior rather than an Aiwa figure, so the corner
 * moves with the headphones actually plugged in. That is physically true of the real machine and
 * is why the prior is named separately instead of being folded into a constant here.</p>
 */
final class AiwaHsJx707OutputStage {

    private final double couplingFarads;
    private final double loadOhms;
    private final double zobelOhms;
    private final double zobelFarads;
    private final double coilHenries;

    AiwaHsJx707OutputStage() {
        this("C86", "C84", "R68", "L14");
    }

    AiwaHsJx707OutputStage(String coupling, String zobelCap, String zobelResistor, String coil) {
        couplingFarads = AiwaHsJx707Schematic.part(coupling).value;
        loadOhms = AiwaHsJx707Schematic.part("P-HP-LOAD").value;
        zobelFarads = AiwaHsJx707Schematic.part(zobelCap).value;
        zobelOhms = AiwaHsJx707Schematic.part(zobelResistor).value;
        coilHenries = AiwaHsJx707Schematic.part(coil).value;
    }

    /** C86 into the rated load: the one part of this network that lands in the audio band. */
    double couplingCornerHertz() {
        return 1.0 / (2.0 * Math.PI * couplingFarads * loadOhms);
    }

    /** R68 with C84. Above 100 kHz, which is why the renderer omits it. */
    double zobelCornerHertz() {
        return 1.0 / (2.0 * Math.PI * zobelOhms * zobelFarads);
    }

    /** The series chip coil against the load. Above 1 MHz, which is why the renderer omits it. */
    double chipCoilCornerHertz() {
        return loadOhms / (2.0 * Math.PI * coilHenries);
    }

    /** Time constant of the coupling high-pass, in seconds. */
    double couplingTimeConstantSeconds() {
        return couplingFarads * loadOhms;
    }

    // ---- TA7688F, from Toshiba's own datasheet rather than from Aiwa's drawing. Aiwa's block
    // ---- diagram prints no values on the internal network; Toshiba's does, so the amplifier is
    // ---- no longer outside the derived path. Evidence class is MANUFACTURER_DATASHEET.

    /** Internal feedback resistor, NF to OUT. */
    private static final double INTERNAL_FEEDBACK_OHMS = 33e3;

    /** Internal gain-setting leg, NF to VB. */
    private static final double INTERNAL_LEG_OHMS = 820.0;

    /**
     * Toshiba quotes 32 dB from the resistor ratio but 30.5 dB measured, "because of influence of
     * the other circuit". That 1.5 dB is carried rather than dropped, so a derived gain lands where
     * the part actually lands.
     */
    private static final double NOMINAL_TO_ACTUAL_DB = -1.5;

    /** Output power at 10% THD into the rated load, and the power at which THD is still its floor. */
    private static final double CLIP_POWER_WATTS = 27e-3;
    private static final double LINEAR_POWER_WATTS = 10e-3;

    /** Supply and output bias in Toshiba's test conditions, which set the available swing. */
    private static final double DATASHEET_SUPPLY_VOLTS = 3.0;
    private static final double DATASHEET_OUTPUT_BIAS_VOLTS = 1.5;

    /** Closed-loop gain with the internal network alone, before Aiwa's external resistor. */
    static double internalGainDb() {
        return 20.0 * Math.log10(
                (INTERNAL_FEEDBACK_OHMS + INTERNAL_LEG_OHMS) / INTERNAL_LEG_OHMS)
                + NOMINAL_TO_ACTUAL_DB;
    }

    /**
     * Closed-loop gain with Aiwa's R65 in parallel with the internal feedback resistor.
     *
     * <p>Worth knowing before quoting this: it comes out below 30 dB, and Toshiba's application
     * note says the part "is not available at Gv &lt; 30 dB" because of high-frequency phase delay.
     * Either Aiwa ran it outside that recommendation or R65's far end is not on OUT after all. The
     * drawing reads as OUT at 9x, so the number is reported as read rather than adjusted to suit
     * the note.</p>
     */
    double externalGainDb() {
        double external = AiwaHsJx707Schematic.part("R65").value;
        double feedback = INTERNAL_FEEDBACK_OHMS * external / (INTERNAL_FEEDBACK_OHMS + external);
        return 20.0 * Math.log10((feedback + INTERNAL_LEG_OHMS) / INTERNAL_LEG_OHMS)
                + NOMINAL_TO_ACTUAL_DB;
    }

    /** Half the supply, i.e. the peak swing available before the output runs into a rail. */
    private static double halfRailVolts() {
        return Math.min(DATASHEET_OUTPUT_BIAS_VOLTS,
                DATASHEET_SUPPLY_VOLTS - DATASHEET_OUTPUT_BIAS_VOLTS);
    }

    private double peakVoltsFor(double watts) {
        return Math.sqrt(2.0 * watts * loadOhms);
    }

    /**
     * Where the output reaches 10% THD, as a fraction of the available swing.
     *
     * <p>Expressed as a fraction rather than in volts on purpose: the JX707 runs this part from a
     * two-cell rail through Q29's ripple filter, not from Toshiba's 3 V bench supply, so the
     * absolute voltage does not transfer but the proportion of the swing does.</p>
     */
    double clipFractionOfSwing() {
        return peakVoltsFor(CLIP_POWER_WATTS) / halfRailVolts();
    }

    /** Where THD is still at its floor, as the same fraction. Below this the stage is clean. */
    double linearFractionOfSwing() {
        return peakVoltsFor(LINEAR_POWER_WATTS) / halfRailVolts();
    }

    /** Response of the coupling high-pass alone, in decibels, relative to its passband. */
    double relativeResponseDb(double hertz) {
        double ratio = hertz / couplingCornerHertz();
        return 20.0 * Math.log10(ratio / Math.sqrt(1.0 + ratio * ratio));
    }
}
