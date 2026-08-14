package com.yqdscott.walktape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auditable transcription of the Aiwa HS-JX707 audio path.
 *
 * <p>Source is Aiwa's own service documentation for the HS-JX707/HS-JX707D: the main audio circuit
 * diagram and the electrical main parts list. Every device type number here is the one Aiwa
 * printed; nothing is carried over from a similar model.</p>
 *
 * <p>Capacitor values come from the parts list rather than from the drawing. The list is typeset
 * and covers C1 to C148 without gaps, while the values printed on the schematic are small, rotated
 * and easy to misread — 6800pF for C35/C36 in particular reads convincingly as 4680pF at any
 * sensible magnification. Resistor values come from the drawing, because the parts list carries
 * only the handful of resistors Aiwa stocked as spares. Where the two sources overlap they agree,
 * and both channels of every stereo network were read separately rather than assumed symmetric;
 * that is what caught R34 as 1.1k rather than the 5.1k it first appeared to be.</p>
 *
 * <p>The structure mirrors {@link TpsL2Schematic} so the two machines are held to one standard:
 * SI units, an explicit evidence class on every part, and engineering priors named {@code P*} so
 * they can never be quoted as something Aiwa published. The tuner board is out of scope; the radio
 * path is bypassed during tape playback.</p>
 *
 * <h2>What is transcribed and what is not</h2>
 *
 * <p>This class is a bill of materials by function block: it says which components belong to the
 * replay equaliser, not which node each one lands on. The replay equaliser's connectivity is
 * nonetheless settled, and is recorded in {@code docs/AIWA_HS_JX707_RECONSTRUCTION.md} rather than
 * here. It was read off the original service manual PDF, whose main audio schematic is 4654 px
 * wide against the 2560 px web scan, at magnifications up to 17x, junction dot by junction dot and
 * with both channels read separately. The audio board layout did <em>not</em> settle it: at 2560 px
 * the copper is 3-4 px wide, split across both sides of the board, and labelling it as connected
 * components gives no answer that survives a threshold sweep.</p>
 *
 * <p>For the left channel, with PB NF L on pin 7, PB OUT L on pin 8 and V REF on pin 6: R7 330k
 * bridges pin 7 to pin 8; R9 15k runs from pin 8 to an intermediate node; C17 0.01u runs from pin 7
 * to that same node, so C17 and R9 in series bridge R7; R11 22k runs from that node to Q1's drain
 * with Q1's source on pin 8, so Q1 conducting parallels R11 with R9 for the metal position. The
 * gain-setting leg from pin 7 to V REF is R17 18k in parallel with R13 560 and R15 560 in parallel
 * feeding C19 22u. The right channel is identical with the even designators.</p>
 *
 * <p>That reading was once recorded as falsified and it should not be doubted again. The feedback
 * shelf has its pole at (R7+R9)*C17 = 3450 us against the standard's 3180 us and its zero at
 * R9*C17 = 150 us against 120 us, or 89.2 us against 70 us with Q1 on; one three-component network
 * yielding both standard time constants is a finding rather than a fit. What had been wrong was the
 * target, not the trace: the IEC curve describes the flux on the tape, and the head differentiates
 * flux into EMF, so the amplifier must realise 1/(w * iecTarget) and not iecTarget itself. Against
 * the corrected target this network holds the IEC characteristic to 1.2 dB normal and 1.9 dB metal
 * from 63 Hz to 16 kHz, which is where Aiwa's own frequency response spec starts.</p>
 *
 * <p>Two candidate explanations have been checked and remain rejected, so a later pass need not
 * repeat them. C15 and C16 are 390 pF, and 330k times 390 pF is 128.7 us, which is temptingly
 * close to the standard's 120 us; at high magnification both capacitors sit on solid junction dots
 * on the reference rail, so they shunt the input rather than bridging the feedback path, and the
 * near-miss is a coincidence. Moving C17 out of the feedback path and into the gain-setting leg
 * was also tried on paper: it flattens the response almost completely, because 15k in parallel
 * with 280 ohms barely moves the leg impedance.</p>
 */
final class AiwaHsJx707Schematic {
    enum Kind {
        RESISTOR, POTENTIOMETER, TRIMMER, CAPACITOR, INDUCTOR, TRANSISTOR, FET, DIODE,
        IC_MACRO, HEAD, MOTOR, SWITCH, SENSOR, LOAD
    }

    enum Evidence {
        AIWA_SERVICE_MANUAL, MANUFACTURER_DATASHEET, ENGINEERING_PRIOR
    }

    enum Role {
        HEAD_INPUT, PREAMP, PLAYBACK_EQ, EQ_SWITCH, DOLBY, BBE_DSL, PLSS, BUFFER, MUTING,
        VOLUME, POWER_AMPLIFIER, HEADPHONE_OUTPUT, SUPPLY_FILTER, MOTOR_GOVERNOR, TRANSPORT,
        SYSTEM_CONTROL, RECORD_ONLY, REFERENCE_LOAD
    }

    static final class Part {
        final String reference;
        final Kind kind;
        final double value;
        final String unit;
        final String device;
        final Evidence evidence;
        final Role role;

        Part(String reference, Kind kind, double value, String unit, String device,
             Evidence evidence, Role role) {
            this.reference = reference;
            this.kind = kind;
            this.value = value;
            this.unit = unit;
            this.device = device;
            this.evidence = evidence;
            this.role = role;
        }
    }

    private static final List<Part> AUDIO_PATH;
    private static final Map<String, Part> BY_REFERENCE;

    static {
        ArrayList<Part> parts = new ArrayList<>();

        // ---- Integrated circuits. Type numbers from the parts list, functions from the block
        // ---- captions on the main audio circuit diagram.
        ic(parts, "IC1", "TA8155FN", "pre/rec amp", Role.PREAMP);
        ic(parts, "IC2", "NJM2065AM", "Dolby amp R", Role.DOLBY);
        ic(parts, "IC3", "NJM2065AM", "Dolby amp L", Role.DOLBY);
        ic(parts, "IC4", "XRC5484", "BBE/DSL amp", Role.BBE_DSL);
        ic(parts, "IC5", "TA7688F(S)", "main amp", Role.POWER_AMPLIFIER);
        ic(parts, "IC6", "CXA1405AM", "2.0 remote comparator", Role.SYSTEM_CONTROL);
        ic(parts, "IC7", "TB2003-003FN", "mecha com", Role.SYSTEM_CONTROL);
        ic(parts, "IC8", "TPIC326ADB", "motor governor", Role.MOTOR_GOVERNOR);

        // ---- Playback head and the IC1 input network.
        part(parts, "HEAD-L", Kind.HEAD, 0, "", "playback head L",
                Evidence.AIWA_SERVICE_MANUAL, Role.HEAD_INPUT);
        part(parts, "HEAD-R", Kind.HEAD, 0, "", "playback head R",
                Evidence.AIWA_SERVICE_MANUAL, Role.HEAD_INPUT);
        capacitor(parts, "C1", 0.22e-6, Role.HEAD_INPUT);
        capacitor(parts, "C2", 0.22e-6, Role.HEAD_INPUT);
        capacitor(parts, "C3", 0.022e-6, Role.PREAMP);
        capacitor(parts, "C4", 0.022e-6, Role.PREAMP);
        capacitor(parts, "C5", 1e-6, Role.PREAMP);
        capacitor(parts, "C6", 1e-6, Role.PREAMP);
        capacitor(parts, "C7", 1e-6, Role.PREAMP);
        capacitor(parts, "C8", 1e-6, Role.PREAMP);
        capacitor(parts, "C9", 100e-12, Role.PREAMP);
        capacitor(parts, "C10", 100e-12, Role.PREAMP);
        capacitor(parts, "C11", 1e-6, Role.PREAMP);
        capacitor(parts, "C12", 1e-6, Role.PREAMP);
        resistor(parts, "R5", 10e3, Role.PREAMP);
        resistor(parts, "R6", 10e3, Role.PREAMP);
        resistor(parts, "R102", 560e3, Role.PREAMP);
        resistor(parts, "R103", 3.3e6, Role.PREAMP);
        capacitor(parts, "C102", 10e-6, Role.SUPPLY_FILTER);
        capacitor(parts, "C103", 22e-6, Role.SUPPLY_FILTER);

        // ---- Switched replay equalisation. Two 2SK880 FETs shunt the metal leg of an otherwise
        // ---- shared network, which is how one preamp serves the 120 us and 70 us curves.
        part(parts, "Q1", Kind.FET, 0, "", "2SK880(Y) EQ switch L",
                Evidence.AIWA_SERVICE_MANUAL, Role.EQ_SWITCH);
        part(parts, "Q2", Kind.FET, 0, "", "2SK880(Y) EQ switch R",
                Evidence.AIWA_SERVICE_MANUAL, Role.EQ_SWITCH);
        resistor(parts, "R7", 330e3, Role.PLAYBACK_EQ);
        resistor(parts, "R8", 330e3, Role.PLAYBACK_EQ);
        resistor(parts, "R9", 15e3, Role.PLAYBACK_EQ);
        resistor(parts, "R10", 15e3, Role.PLAYBACK_EQ);
        resistor(parts, "R11", 22e3, Role.PLAYBACK_EQ);
        resistor(parts, "R12", 22e3, Role.PLAYBACK_EQ);
        resistor(parts, "R13", 560, Role.PLAYBACK_EQ);
        resistor(parts, "R14", 560, Role.PLAYBACK_EQ);
        resistor(parts, "R15", 560, Role.PLAYBACK_EQ);
        resistor(parts, "R16", 560, Role.PLAYBACK_EQ);
        resistor(parts, "R17", 18e3, Role.PLAYBACK_EQ);
        resistor(parts, "R18", 18e3, Role.PLAYBACK_EQ);
        capacitor(parts, "C13", 1000e-12, Role.PLAYBACK_EQ);
        capacitor(parts, "C14", 1000e-12, Role.PLAYBACK_EQ);
        capacitor(parts, "C15", 390e-12, Role.PLAYBACK_EQ);
        capacitor(parts, "C16", 390e-12, Role.PLAYBACK_EQ);
        capacitor(parts, "C17", 0.01e-6, Role.PLAYBACK_EQ);
        capacitor(parts, "C18", 0.01e-6, Role.PLAYBACK_EQ);
        capacitor(parts, "C19", 22e-6, Role.PLAYBACK_EQ);
        capacitor(parts, "C20", 22e-6, Role.PLAYBACK_EQ);
        capacitor(parts, "C21", 1e-6, Role.PLAYBACK_EQ);
        capacitor(parts, "C22", 1e-6, Role.PLAYBACK_EQ);

        // ---- Dolby B/C around IC2 and IC3. Both channels were read separately; the networks are
        // ---- identical, which is what makes the pairing below a finding rather than a guess.
        addDolbyChannel(parts, "L", 19, 23);
        addDolbyChannel(parts, "R", 20, 24);
        resistor(parts, "R115", 4.7e3, Role.DOLBY);
        resistor(parts, "R116", 4.7e3, Role.DOLBY);
        capacitor(parts, "C115", 1e-6, Role.DOLBY);
        capacitor(parts, "C116", 1e-6, Role.DOLBY);
        capacitor(parts, "C117", 220e-6, Role.SUPPLY_FILTER);
        capacitor(parts, "C118", 4.7e-6, Role.SUPPLY_FILTER);
        capacitor(parts, "C119", 10e-6, Role.SUPPLY_FILTER);
        switchPart(parts, "S3", "slide SW (Dolby NR / FM mode)", Role.DOLBY);

        // ---- BBE and DSL around IC4. Pin 19 is the BBE switch and pin 18 the DSL switch; the
        // ---- drawing gives both their on and off pin voltages, so the model can reproduce the
        // ---- machine's actual switching rather than an assumed one.
        addBbeChannel(parts, "L", 51, 53, 55, 57, 59, 61, 63, 65, 67, 69, 71);
        addBbeChannel(parts, "R", 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72);
        resistor(parts, "R41", 6.8e3, Role.BBE_DSL);
        resistor(parts, "R42", 6.8e3, Role.BBE_DSL);
        resistor(parts, "R43", 3.3e3, Role.BBE_DSL);
        resistor(parts, "R44", 3.3e3, Role.BBE_DSL);
        resistor(parts, "R45", 6.8e3, Role.BBE_DSL);
        resistor(parts, "R46", 6.8e3, Role.BBE_DSL);
        resistor(parts, "R47", 330, Role.PLSS);
        resistor(parts, "R48", 330, Role.PLSS);
        capacitor(parts, "C120", 1e-6, Role.BBE_DSL);
        capacitor(parts, "C121", 10e-6, Role.BBE_DSL);
        capacitor(parts, "C122", 0.01e-6, Role.BBE_DSL);
        switchPart(parts, "S4", "slide SW (DSL)", Role.BBE_DSL);
        switchPart(parts, "S5", "slide SW (BBE)", Role.BBE_DSL);

        // ---- PLSS bass system and its automatic level control.
        part(parts, "Q25", Kind.TRANSISTOR, 0, "", "HN1C03F(B) PLSS ALC",
                Evidence.AIWA_SERVICE_MANUAL, Role.PLSS);
        resistor(parts, "R49", 22e3, Role.PLSS);
        resistor(parts, "R50", 22e3, Role.PLSS);
        resistor(parts, "R51", 100e3, Role.PLSS);
        resistor(parts, "R52", 100e3, Role.PLSS);
        capacitor(parts, "C73", 3900e-12, Role.PLSS);
        capacitor(parts, "C74", 3900e-12, Role.PLSS);
        capacitor(parts, "C75", 0.027e-6, Role.PLSS);
        capacitor(parts, "C76", 0.027e-6, Role.PLSS);
        switchPart(parts, "S6", "slide SW (PLSS)", Role.PLSS);

        // ---- Buffer stage feeding the volume control.
        part(parts, "Q26", Kind.TRANSISTOR, 0, "", "HN1C01F(GR) buffer amp",
                Evidence.AIWA_SERVICE_MANUAL, Role.BUFFER);
        resistor(parts, "R53", 100e3, Role.BUFFER);
        resistor(parts, "R54", 100e3, Role.BUFFER);
        capacitor(parts, "C77", 1e-6, Role.BUFFER);
        capacitor(parts, "C78", 1e-6, Role.BUFFER);

        // ---- Output muting FET, held on until the transport and supply have settled.
        part(parts, "Q5", Kind.FET, 0, "", "2SK880(Y) muting",
                Evidence.AIWA_SERVICE_MANUAL, Role.MUTING);
        resistor(parts, "R55", 1e3, Role.MUTING);
        resistor(parts, "R57", 120e3, Role.MUTING);
        resistor(parts, "R58", 120e3, Role.MUTING);
        capacitor(parts, "C79", 1e-6, Role.MUTING);
        capacitor(parts, "C80", 1e-6, Role.MUTING);

        // ---- Volume and the network either side of it.
        part(parts, "VR1", Kind.POTENTIOMETER, 20e3, "ohm", "volume 20K(A) dual gang",
                Evidence.AIWA_SERVICE_MANUAL, Role.VOLUME);
        resistor(parts, "R59", 1e3, Role.VOLUME);
        resistor(parts, "R60", 1e3, Role.VOLUME);
        resistor(parts, "R63", 47e3, Role.VOLUME);
        resistor(parts, "R64", 47e3, Role.VOLUME);
        resistor(parts, "R125", 10e3, Role.VOLUME);
        capacitor(parts, "C127", 0.068e-6, Role.VOLUME);
        capacitor(parts, "C131", 47e-6, Role.VOLUME);

        // ---- Main amplifier, its feedback network and the ripple filter ahead of it.
        capacitor(parts, "C81", 1200e-12, Role.POWER_AMPLIFIER);
        capacitor(parts, "C82", 1200e-12, Role.POWER_AMPLIFIER);
        resistor(parts, "R65", 47e3, Role.POWER_AMPLIFIER);
        resistor(parts, "R66", 47e3, Role.POWER_AMPLIFIER);
        resistor(parts, "R136", 750, Role.POWER_AMPLIFIER);
        resistor(parts, "R137", 5.1e3, Role.POWER_AMPLIFIER);
        part(parts, "Q29", Kind.TRANSISTOR, 0, "", "2SA1586(Y) ripple filter",
                Evidence.AIWA_SERVICE_MANUAL, Role.SUPPLY_FILTER);
        capacitor(parts, "C128", 100e-6, Role.SUPPLY_FILTER);
        capacitor(parts, "C130", 220e-6, Role.SUPPLY_FILTER);
        capacitor(parts, "C134", 220e-6, Role.SUPPLY_FILTER);

        // ---- Headphone output coupling and the remote jack's loading network.
        capacitor(parts, "C85", 220e-6, Role.HEADPHONE_OUTPUT);
        capacitor(parts, "C86", 220e-6, Role.HEADPHONE_OUTPUT);
        capacitor(parts, "C83", 0.22e-6, Role.HEADPHONE_OUTPUT);
        capacitor(parts, "C84", 0.22e-6, Role.HEADPHONE_OUTPUT);
        inductor(parts, "L12", 3.3e-6, "chip coil S 3.3uH", Role.HEADPHONE_OUTPUT);
        inductor(parts, "L13", 0, "loading coil", Role.HEADPHONE_OUTPUT);
        inductor(parts, "L14", 3.3e-6, "chip coil S 3.3uH", Role.HEADPHONE_OUTPUT);
        inductor(parts, "L15", 3.3e-6, "chip coil S 3.3uH", Role.HEADPHONE_OUTPUT);
        switchPart(parts, "S7", "slide SW (remote)", Role.SYSTEM_CONTROL);

        // ---- Transport: governor, speed trim, reel sensing and the mode switches.
        part(parts, "M1", Kind.MOTOR, 0, "", "capstan motor",
                Evidence.AIWA_SERVICE_MANUAL, Role.TRANSPORT);
        part(parts, "SFR1", Kind.TRIMMER, 3e3, "ohm", "tape speed adj 3K RVG4H",
                Evidence.AIWA_SERVICE_MANUAL, Role.MOTOR_GOVERNOR);
        part(parts, "CP1", Kind.SENSOR, 0, "", "photo sensor 5164K-F1-Q2",
                Evidence.AIWA_SERVICE_MANUAL, Role.TRANSPORT);
        resistor(parts, "R164", 8.2e3, Role.MOTOR_GOVERNOR);
        resistor(parts, "R165", 1.5e3, Role.MOTOR_GOVERNOR);
        resistor(parts, "R166", 8.2e3, Role.MOTOR_GOVERNOR);
        resistor(parts, "R170", 2.2, Role.MOTOR_GOVERNOR);
        switchPart(parts, "S1", "slide SW (F/R)", Role.TRANSPORT);
        switchPart(parts, "S8", "slide SW (reverse mode)", Role.TRANSPORT);
        switchPart(parts, "S9", "slide SW (F/R)", Role.TRANSPORT);
        switchPart(parts, "S10", "push SW (play)", Role.TRANSPORT);

        // ---- Record-side parts, retained so the transcription describes the whole machine even
        // ---- though playback leaves the bias oscillator switched off.
        switchPart(parts, "S2", "leaf SW (REC)", Role.RECORD_ONLY);
        inductor(parts, "L11", 0, "chip coil, OSC bias", Role.RECORD_ONLY);
        capacitor(parts, "C112", 2700e-12, Role.RECORD_ONLY);

        // ---- Engineering priors. Aiwa did not publish these.
        prior(parts, "P-HEAD-R", 210.0, "ohm", "playback head winding resistance",
                Role.HEAD_INPUT);
        prior(parts, "P-HEAD-L", 88e-3, "H", "playback head winding inductance",
                Role.HEAD_INPUT);
        prior(parts, "P-HP-LOAD", 32.0, "ohm", "rated headphone load", Role.REFERENCE_LOAD);
        prior(parts, "P-BATT-R", 0.34, "ohm", "cell plus contact resistance",
                Role.SUPPLY_FILTER);

        HashMap<String, Part> index = new HashMap<>();
        for (Part part : parts) {
            if (index.put(part.reference, part) != null) {
                throw new IllegalStateException("Duplicate HS-JX707 component: " + part.reference);
            }
            if (part.role == null || part.evidence == null) {
                throw new IllegalStateException("Unmodelled HS-JX707 component: " + part.reference);
            }
        }
        AUDIO_PATH = Collections.unmodifiableList(parts);
        BY_REFERENCE = Collections.unmodifiableMap(index);
    }

    private AiwaHsJx707Schematic() {
    }

    /**
     * One channel of the Dolby network around an NJM2065AM.
     *
     * <p>{@code firstResistor} is R19 for the left channel and R20 for the right; the odd and even
     * designators alternate through the pair exactly as Aiwa numbered them.</p>
     */
    private static void addDolbyChannel(List<Part> parts, String channel,
                                        int firstResistor, int firstCapacitor) {
        int r = firstResistor;
        resistor(parts, "R" + r, 330e3, Role.DOLBY);
        resistor(parts, "R" + (r + 2), 2e3, Role.DOLBY);
        resistor(parts, "R" + (r + 4), 510e3, Role.DOLBY);
        resistor(parts, "R" + (r + 6), 2.4e3, Role.DOLBY);
        resistor(parts, "R" + (r + 8), 47e3, Role.DOLBY);
        resistor(parts, "R" + (r + 10), 100e3, Role.DOLBY);
        resistor(parts, "R" + (r + 12), 120, Role.DOLBY);
        resistor(parts, "R" + (r + 14), 1.1e3, Role.DOLBY);
        resistor(parts, "R" + (r + 16), 2e3, Role.DOLBY);
        resistor(parts, "R" + (r + 18), 47e3, Role.DOLBY);
        resistor(parts, "R" + (r + 20), 5.1e3, Role.DOLBY);

        int c = firstCapacitor;
        capacitor(parts, "C" + c, 1e-6, Role.DOLBY);
        capacitor(parts, "C" + (c + 2), 0.047e-6, Role.DOLBY);
        capacitor(parts, "C" + (c + 4), 2.2e-6, Role.DOLBY);
        capacitor(parts, "C" + (c + 6), 0.22e-6, Role.DOLBY);
        capacitor(parts, "C" + (c + 8), 0.22e-6, Role.DOLBY);
        capacitor(parts, "C" + (c + 10), 2.2e-6, Role.DOLBY);
        capacitor(parts, "C" + (c + 12), 6800e-12, Role.DOLBY);
        capacitor(parts, "C" + (c + 14), 150e-12, Role.DOLBY);
        capacitor(parts, "C" + (c + 16), 5600e-12, Role.DOLBY);
        capacitor(parts, "C" + (c + 18), 0.01e-6, Role.DOLBY);
        capacitor(parts, "C" + (c + 20), 5600e-12, Role.DOLBY);
        capacitor(parts, "C" + (c + 22), 1e-6, Role.DOLBY);
        capacitor(parts, "C" + (c + 24), 0.01e-6, Role.DOLBY);
        capacitor(parts, "C" + (c + 26), 0.047e-6, Role.DOLBY);
    }

    /** One channel of the BBE/DSL network around the XRC5484. */
    private static void addBbeChannel(List<Part> parts, String channel, int... references) {
        double[] values = {
                0.047e-6, 820e-12, 100e-12, 2.2e-6, 0.047e-6, 2.2e-6,
                4.7e-6, 0.33e-6, 0.068e-6, 0.068e-6, 1e-6
        };
        for (int index = 0; index < references.length; index++) {
            capacitor(parts, "C" + references[index], values[index], Role.BBE_DSL);
        }
    }

    static List<Part> audioPathParts() {
        return AUDIO_PATH;
    }

    static Part part(String reference) {
        Part result = BY_REFERENCE.get(reference);
        if (result == null) {
            throw new IllegalArgumentException("Unknown HS-JX707 component: " + reference);
        }
        return result;
    }

    static float value(String reference) {
        return (float) part(reference).value;
    }

    static int count(Kind kind) {
        int count = 0;
        for (Part part : AUDIO_PATH) {
            if (part.kind == kind) {
                count++;
            }
        }
        return count;
    }

    static int countByRole(Role role) {
        int count = 0;
        for (Part part : AUDIO_PATH) {
            if (part.role == role) {
                count++;
            }
        }
        return count;
    }

    private static void ic(List<Part> parts, String reference, String device, String function,
                           Role role) {
        part(parts, reference, Kind.IC_MACRO, 0, "", device + " " + function,
                Evidence.AIWA_SERVICE_MANUAL, role);
    }

    private static void switchPart(List<Part> parts, String reference, String device, Role role) {
        part(parts, reference, Kind.SWITCH, 0, "", device,
                Evidence.AIWA_SERVICE_MANUAL, role);
    }

    private static void inductor(List<Part> parts, String reference, double henries,
                                 String device, Role role) {
        part(parts, reference, Kind.INDUCTOR, henries, henries > 0 ? "H" : "", device,
                Evidence.AIWA_SERVICE_MANUAL, role);
    }

    private static void resistor(List<Part> parts, String reference, double ohms, Role role) {
        part(parts, reference, Kind.RESISTOR, ohms, "ohm", "",
                Evidence.AIWA_SERVICE_MANUAL, role);
    }

    private static void capacitor(List<Part> parts, String reference, double farads, Role role) {
        part(parts, reference, Kind.CAPACITOR, farads, "F", "",
                Evidence.AIWA_SERVICE_MANUAL, role);
    }

    private static void prior(List<Part> parts, String reference, double value, String unit,
                              String device, Role role) {
        part(parts, reference, Kind.LOAD, value, unit, device, Evidence.ENGINEERING_PRIOR, role);
    }

    private static void part(List<Part> parts, String reference, Kind kind, double value,
                             String unit, String device, Evidence evidence, Role role) {
        parts.add(new Part(reference, kind, value, unit, device, evidence, role));
    }
}
