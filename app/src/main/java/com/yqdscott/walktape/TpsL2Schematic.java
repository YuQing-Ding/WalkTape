package com.yqdscott.walktape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auditable transcription of the revised-model TPS-L2 schematic (service-manual pages 17-18).
 *
 * <p>Values are stored in SI units. Every visible electrical part has a model role; parts whose
 * internal construction Sony did not publish are explicitly marked as pin-level macro models.
 * Keeping this table executable prevents the realtime reductions from turning into anonymous
 * tuning constants and gives the offline reference model a single source of component values.</p>
 */
final class TpsL2Schematic {
    enum Kind {
        RESISTOR, POTENTIOMETER, CAPACITOR, INDUCTOR, TRANSISTOR, DIODE,
        IC_MACRO, HEAD, MOTOR, THERMISTOR, SWITCH, MICROPHONE, LOAD
    }

    enum Evidence {
        SONY_SERVICE_MANUAL, MANUFACTURER_DATASHEET, ENGINEERING_PRIOR
    }

    enum Role {
        HEAD_INPUT, PREAMP_BIAS, PREAMP_FEEDBACK, PLAYBACK_EQ, TONE_SWITCH,
        VOLUME, POWER_AMPLIFIER, HEADPHONE_OUTPUT, HOT_LINE, SUPPLY_FILTER,
        FG_SERVO, MOTOR_DRIVE, TRANSPORT, REFERENCE_LOAD
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

    private static final List<Part> REVISED_MODEL;
    private static final Map<String, Part> BY_REFERENCE;

    static {
        ArrayList<Part> parts = new ArrayList<>();

        // PP181-3602E head and the two identical revised-model playback channels.
        part(parts, "HP901-1", Kind.HEAD, 0, "", "PP181-3602E L",
                Evidence.SONY_SERVICE_MANUAL, Role.HEAD_INPUT);
        part(parts, "HP901-2", Kind.HEAD, 0, "", "PP181-3602E R",
                Evidence.SONY_SERVICE_MANUAL, Role.HEAD_INPUT);
        addPlaybackChannel(parts, 100);
        addPlaybackChannel(parts, 200);

        // CX184 common supply decoupling and the two physical headphone sockets.
        capacitor(parts, "C301", 47e-6, Role.SUPPLY_FILTER);
        resistor(parts, "R301", 100e3, Role.SUPPLY_FILTER);
        resistor(parts, "R801", 3.9, Role.HEADPHONE_OUTPUT);
        resistor(parts, "R802", 3.9, Role.HEADPHONE_OUTPUT);
        resistor(parts, "R803", 3.9, Role.HEADPHONE_OUTPUT);
        resistor(parts, "R804", 3.9, Role.HEADPHONE_OUTPUT);
        part(parts, "J801-1", Kind.LOAD, 0, "", "headphone jack 1",
                Evidence.SONY_SERVICE_MANUAL, Role.HEADPHONE_OUTPUT);
        part(parts, "J801-2", Kind.LOAD, 0, "", "headphone jack 2",
                Evidence.SONY_SERVICE_MANUAL, Role.HEADPHONE_OUTPUT);

        // HOT LINE microphone and switch network. It is open in ordinary tape playback but is
        // retained in the reference circuit so the switch/contact parasitic is not forgotten.
        capacitor(parts, "C801", 0.1e-6, Role.HOT_LINE);
        resistor(parts, "R810", 10e3, Role.HOT_LINE);
        resistor(parts, "R811", 10e3, Role.HOT_LINE);
        resistor(parts, "R812", 2.7e3, Role.HOT_LINE);
        resistor(parts, "R814", 1e3, Role.HOT_LINE);
        resistor(parts, "R815", 2e3, Role.HOT_LINE);
        part(parts, "MIC901", Kind.MICROPHONE, 0, "", "C-1004Q electret",
                Evidence.SONY_SERVICE_MANUAL, Role.HOT_LINE);
        part(parts, "S801", Kind.SWITCH, 0, "", "HOT LINE DPDT",
                Evidence.SONY_SERVICE_MANUAL, Role.HOT_LINE);
        part(parts, "S301", Kind.SWITCH, 0, "", "LOW/HIGH tone DPDT",
                Evidence.SONY_SERVICE_MANUAL, Role.TONE_SWITCH);

        // FG servo and motor drive board.
        resistor(parts, "R601", 1.5e3, Role.FG_SERVO);
        resistor(parts, "R602", 100e3, Role.FG_SERVO);
        resistor(parts, "R603", 360, Role.FG_SERVO);
        resistor(parts, "R604", 470e3, Role.FG_SERVO);
        resistor(parts, "R605", 100e3, Role.FG_SERVO);
        resistor(parts, "R606", 2.2e6, Role.FG_SERVO);
        resistor(parts, "R607", 10e3, Role.MOTOR_DRIVE);
        resistor(parts, "R608", 27, Role.MOTOR_DRIVE);
        resistor(parts, "R609", 150, Role.MOTOR_DRIVE);
        resistor(parts, "R610", 2.7e3, Role.FG_SERVO);
        resistor(parts, "R611", 22e3, Role.FG_SERVO);
        resistor(parts, "R612", 270e3, Role.FG_SERVO);
        part(parts, "RV601", Kind.POTENTIOMETER, 5e3, "ohm", "tape speed trim",
                Evidence.SONY_SERVICE_MANUAL, Role.FG_SERVO);
        capacitor(parts, "C601", 33e-6, Role.FG_SERVO);
        capacitor(parts, "C602", 0.1e-6, Role.FG_SERVO);
        capacitor(parts, "C603", 0.47e-6, Role.FG_SERVO);
        capacitor(parts, "C604", 33e-6, Role.FG_SERVO);
        capacitor(parts, "C605", 33e-6, Role.FG_SERVO);
        capacitor(parts, "C606", 4.7e-6, Role.MOTOR_DRIVE);
        capacitor(parts, "C607", 10e-6, Role.FG_SERVO);
        part(parts, "IC601", Kind.IC_MACRO, 0, "", "CX183 FG servo",
                Evidence.SONY_SERVICE_MANUAL, Role.FG_SERVO);
        part(parts, "Q601", Kind.TRANSISTOR, 0, "", "2SC1363",
                Evidence.SONY_SERVICE_MANUAL, Role.MOTOR_DRIVE);
        part(parts, "Q602", Kind.TRANSISTOR, 0, "", "2SC1363",
                Evidence.SONY_SERVICE_MANUAL, Role.MOTOR_DRIVE);
        part(parts, "Q603", Kind.TRANSISTOR, 0, "", "2SC1474",
                Evidence.SONY_SERVICE_MANUAL, Role.MOTOR_DRIVE);
        part(parts, "D601", Kind.DIODE, 0, "", "1T22 protector",
                Evidence.SONY_SERVICE_MANUAL, Role.MOTOR_DRIVE);
        part(parts, "D602", Kind.DIODE, 0, "", "1S1555",
                Evidence.SONY_SERVICE_MANUAL, Role.FG_SERVO);
        part(parts, "D901", Kind.DIODE, 0, "", "TLR109 operation LED",
                Evidence.SONY_SERVICE_MANUAL, Role.MOTOR_DRIVE);
        part(parts, "THP601", Kind.THERMISTOR, 0, "", "servo temperature compensation",
                Evidence.SONY_SERVICE_MANUAL, Role.FG_SERVO);
        part(parts, "L601", Kind.INDUCTOR, 35e-6, "H", "motor RF choke",
                Evidence.SONY_SERVICE_MANUAL, Role.MOTOR_DRIVE);
        part(parts, "M901", Kind.MOTOR, 0, "", "MNF-1600B with FG",
                Evidence.SONY_SERVICE_MANUAL, Role.TRANSPORT);

        // Main 3 V bus and mechanical power switch.
        capacitor(parts, "C901", 220e-6, Role.SUPPLY_FILTER);
        part(parts, "S901", Kind.SWITCH, 0, "", "transport power leaf switch",
                Evidence.SONY_SERVICE_MANUAL, Role.SUPPLY_FILTER);
        part(parts, "CNJ901", Kind.LOAD, 3.0, "V", "external DC input",
                Evidence.SONY_SERVICE_MANUAL, Role.SUPPLY_FILTER);
        part(parts, "BATT901", Kind.LOAD, 3.0, "V", "two AA cells",
                Evidence.SONY_SERVICE_MANUAL, Role.SUPPLY_FILTER);

        // Explicit engineering priors missing from the Sony drawing. They are deliberately named
        // P* rather than disguised as service-manual components.
        prior(parts, "P-HEAD-R", 240.0, "ohm", "head winding resistance", Role.HEAD_INPUT);
        prior(parts, "P-HEAD-L", 95e-3, "H", "head winding inductance", Role.HEAD_INPUT);
        prior(parts, "P-Q-IS", 2.0e-14, "A", "2SC2458 junction saturation current",
                Role.PREAMP_BIAS);
        prior(parts, "P-Q-BETA", 300.0, "ratio", "2SC2458 nominal forward hFE",
                Role.PREAMP_BIAS);
        prior(parts, "P-MIC-C", 5.5e-9, "F", "electret and wiring capacitance",
                Role.HOT_LINE);
        prior(parts, "P-BATT-R", 0.36, "ohm", "cells plus contacts", Role.SUPPLY_FILTER);
        // Two LR6 alkaline cells. The capacity and the end-point are the published ratings for a
        // 100 mA discharge, which is exactly the transport current Sony specifies for PLAY; the
        // resistance growth and the shape between those endpoints are declared priors.
        prior(parts, "P-BATT-AH", 2.1, "A*h", "LR6 rated capacity at 100 mA to 0.9 V",
                Role.SUPPLY_FILTER);
        prior(parts, "P-BATT-V-FULL", 3.02, "V", "fresh pair off-load", Role.SUPPLY_FILTER);
        prior(parts, "P-BATT-V-END", 1.80, "V", "0.9 V per cell end point",
                Role.SUPPLY_FILTER);
        prior(parts, "P-BATT-R-END", 1.45, "ohm", "pair resistance when exhausted",
                Role.SUPPLY_FILTER);
        prior(parts, "P-CX184-RO", 3.2, "ohm", "CX184 dynamic ripple-filter output",
                Role.SUPPLY_FILTER);
        prior(parts, "P-CX182-RO", 1.1, "ohm", "CX182 rail feed dynamic residue",
                Role.SUPPLY_FILTER);
        prior(parts, "P-MOTOR-KT", 0.0048, "N*m/A", "motor torque constant", Role.TRANSPORT);
        prior(parts, "P-MOTOR-KE", 0.0048, "V*s/rad", "motor back-EMF constant",
                Role.TRANSPORT);
        prior(parts, "P-MOTOR-R", 14.0, "ohm", "motor winding resistance", Role.TRANSPORT);
        prior(parts, "P-MOTOR-J", 1.1e-7, "kg*m2", "motor reflected inertia", Role.TRANSPORT);
        prior(parts, "P-FLYWHEEL-J", 2.8e-6, "kg*m2", "flywheel reflected inertia",
                Role.TRANSPORT);
        prior(parts, "P-BELT-K", 0.014, "N*m/rad", "belt torsional stiffness",
                Role.TRANSPORT);
        prior(parts, "P-BELT-C", 0.00016, "N*m*s/rad", "belt torsional damping",
                Role.TRANSPORT);
        prior(parts, "P-PULLEY-RATIO", 2.8, "ratio", "motor to capstan speed ratio",
                Role.TRANSPORT);
        prior(parts, "P-CAPSTAN-R", 0.0012, "m", "capstan radius", Role.TRANSPORT);
        prior(parts, "P-MOTOR-B", 3.75e-6, "N*m*s/rad", "motor viscous friction",
                Role.TRANSPORT);
        prior(parts, "P-FLYWHEEL-B", 1.5e-6, "N*m*s/rad", "flywheel bearing friction",
                Role.TRANSPORT);
        prior(parts, "P-CAPSTAN-TF", 2.0e-5, "N*m", "capstan Coulomb friction",
                Role.TRANSPORT);
        prior(parts, "P-TAPE-T", 3.1e-5, "N*m", "nominal tape/back-tension torque",
                Role.TRANSPORT);
        prior(parts, "P-SERVO-KP", 0.014, "V*s/rad", "CX183 proportional macro gain",
                Role.FG_SERVO);
        prior(parts, "P-SERVO-KI", 0.11, "V/rad", "CX183 integral macro gain",
                Role.FG_SERVO);
        prior(parts, "P-THP-TC", 0.00035, "1/C", "THP601 compensation coefficient",
                Role.FG_SERVO);
        prior(parts, "P-HP-LOAD", 35.0, "ohm", "manual rated headphone load",
                Role.REFERENCE_LOAD);

        HashMap<String, Part> index = new HashMap<>();
        for (Part part : parts) {
            if (index.put(part.reference, part) != null) {
                throw new IllegalStateException("Duplicate TPS-L2 component: " + part.reference);
            }
            if (part.role == null || part.evidence == null) {
                throw new IllegalStateException("Unmodelled TPS-L2 component: " + part.reference);
            }
        }
        REVISED_MODEL = Collections.unmodifiableList(parts);
        BY_REFERENCE = Collections.unmodifiableMap(index);
    }

    private TpsL2Schematic() {
    }

    static List<Part> revisedModelParts() {
        return REVISED_MODEL;
    }

    static Part part(String reference) {
        Part result = BY_REFERENCE.get(reference);
        if (result == null) {
            throw new IllegalArgumentException("Unknown TPS-L2 component: " + reference);
        }
        return result;
    }

    static float value(String reference) {
        return (float) part(reference).value;
    }

    static int count(Kind kind) {
        int count = 0;
        for (Part part : REVISED_MODEL) {
            if (part.kind == kind) {
                count++;
            }
        }
        return count;
    }

    private static void addPlaybackChannel(List<Part> parts, int channel) {
        String prefix = channel == 100 ? "L" : "R";
        resistor(parts, "R" + (channel + 1), 100e3, Role.PREAMP_BIAS);
        resistor(parts, "R" + (channel + 2), 56e3, Role.PREAMP_BIAS);
        resistor(parts, "R" + (channel + 3), 33e3, Role.PREAMP_FEEDBACK);
        resistor(parts, "R" + (channel + 4), 560, Role.PREAMP_BIAS);
        resistor(parts, "R" + (channel + 5), 82e3, Role.PLAYBACK_EQ);
        resistor(parts, "R" + (channel + 6), 470, Role.PLAYBACK_EQ);
        resistor(parts, "R" + (channel + 7), 1.8e3, Role.TONE_SWITCH);
        resistor(parts, "R" + (channel + 8), 47e3, Role.PREAMP_FEEDBACK);
        resistor(parts, "R" + (channel + 9), 1.5e3, Role.PLAYBACK_EQ);
        resistor(parts, "R" + (channel + 10), 680, Role.PLAYBACK_EQ);
        resistor(parts, "R" + (channel + 11), 2.7e3, Role.PLAYBACK_EQ);
        resistor(parts, "R" + (channel + 12), 10, Role.SUPPLY_FILTER);
        resistor(parts, "R" + (channel + 13), 47e3, Role.POWER_AMPLIFIER);
        resistor(parts, "R" + (channel + 14), 330, Role.POWER_AMPLIFIER);

        capacitor(parts, "C" + (channel + 1), 0.47e-6, Role.HEAD_INPUT);
        capacitor(parts, "C" + (channel + 2), 22e-6, Role.PREAMP_BIAS);
        capacitor(parts, "C" + (channel + 3), 0.001e-6, Role.PREAMP_FEEDBACK);
        capacitor(parts, "C" + (channel + 4), 68e-12, Role.PREAMP_FEEDBACK);
        capacitor(parts, "C" + (channel + 5), 10e-6, Role.PLAYBACK_EQ);
        capacitor(parts, "C" + (channel + 6), 100e-12, Role.PREAMP_FEEDBACK);
        capacitor(parts, "C" + (channel + 7), 0.047e-6, Role.PLAYBACK_EQ);
        capacitor(parts, "C" + (channel + 8), 0.01e-6, Role.TONE_SWITCH);
        capacitor(parts, "C" + (channel + 9), 47e-6, Role.SUPPLY_FILTER);
        capacitor(parts, "C" + (channel + 10), 1e-6, Role.PLAYBACK_EQ);
        capacitor(parts, "C" + (channel + 11), 1e-6, Role.PLAYBACK_EQ);
        capacitor(parts, "C" + (channel + 12), 0.0022e-6, Role.POWER_AMPLIFIER);
        capacitor(parts, "C" + (channel + 13), 47e-6, Role.POWER_AMPLIFIER);
        capacitor(parts, "C" + (channel + 14), 0.033e-6, Role.POWER_AMPLIFIER);
        capacitor(parts, "C" + (channel + 15), 0.033e-6, Role.POWER_AMPLIFIER);
        capacitor(parts, "C" + (channel + 16), 220e-6, Role.HEADPHONE_OUTPUT);
        part(parts, "RV" + (channel + 1), Kind.POTENTIOMETER, 20e3, "ohm",
                prefix + " volume", Evidence.SONY_SERVICE_MANUAL, Role.VOLUME);
        part(parts, "Q" + (channel + 1), Kind.TRANSISTOR, 0, "", "2SC2458",
                Evidence.MANUFACTURER_DATASHEET, Role.PREAMP_BIAS);
        part(parts, "IC" + (channel + 1), Kind.IC_MACRO, 0, "", "CX182 preamp/AGC",
                Evidence.SONY_SERVICE_MANUAL, Role.PREAMP_FEEDBACK);
        part(parts, "IC" + (channel + 2), Kind.IC_MACRO, 0, "", "CX184 power/ripple filter",
                Evidence.SONY_SERVICE_MANUAL, Role.POWER_AMPLIFIER);
    }

    private static void resistor(List<Part> parts, String reference, double ohms, Role role) {
        part(parts, reference, Kind.RESISTOR, ohms, "ohm", "",
                Evidence.SONY_SERVICE_MANUAL, role);
    }

    private static void capacitor(List<Part> parts, String reference, double farads, Role role) {
        part(parts, reference, Kind.CAPACITOR, farads, "F", "",
                Evidence.SONY_SERVICE_MANUAL, role);
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
