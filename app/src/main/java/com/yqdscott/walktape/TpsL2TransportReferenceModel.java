package com.yqdscott.walktape;

/**
 * Oversampled coupled ODE reference for the TPS-L2 MNF-1600B/FG/belt/flywheel transport.
 *
 * <p>The service manual establishes the topology and the 1.9 V motor operating point. Sony did
 * not publish motor constants, belt modulus or inertias, so those values are separately labelled
 * engineering priors in {@link TpsL2Schematic}. This offline model is a calibration oracle for
 * the reduced realtime state in {@link TpsL2ElectromechanicalModel}, not an audio-thread object.</p>
 */
final class TpsL2TransportReferenceModel {
    private static final int OVERSAMPLE = 8;
    private static final double NOMINAL_TAPE_METRES_PER_SECOND = 0.0476;
    private static final double MOTOR_VISCOUS = value("P-MOTOR-B");
    private static final double FLYWHEEL_VISCOUS = value("P-FLYWHEEL-B");
    private static final double CAPSTAN_COULOMB_TORQUE = value("P-CAPSTAN-TF");
    private static final double BASE_TAPE_TORQUE = value("P-TAPE-T");
    private static final double SERVO_KP = value("P-SERVO-KP");
    private static final double SERVO_KI = value("P-SERVO-KI");
    private static final double THERMISTOR_COEFFICIENT = value("P-THP-TC");
    private static final double CAPSTAN_RADIUS = value("P-CAPSTAN-R");
    private static final double PULLEY_RATIO = value("P-PULLEY-RATIO");
    private static final double MOTOR_KE = value("P-MOTOR-KE");
    private static final double MOTOR_KT = value("P-MOTOR-KT");
    private static final double MOTOR_RESISTANCE = value("P-MOTOR-R");
    private static final double MOTOR_INERTIA = value("P-MOTOR-J");
    private static final double FLYWHEEL_INERTIA = value("P-FLYWHEEL-J");
    private static final double BELT_STIFFNESS = value("P-BELT-K");
    private static final double BELT_DAMPING = value("P-BELT-C");
    private static final double BATTERY_RESISTANCE = value("P-BATT-R");
    private static final double MAIN_CAPACITANCE = value("C901");

    private final double dt;
    private final double nominalFlywheelSpeed;
    private final double fgInputAlpha;
    private final double shaperAlpha;
    private final double sawAlpha;
    private final double comparatorAlpha;
    private final double servoLeak;
    private final double driveAlpha;
    private final double protectorAlpha;
    private volatile float requestedPosition = 0.5f;
    private volatile TapeTransportState requestedState = TapeTransportState.PLAYING;
    private double tapePosition;
    private TapeTransportState state;
    private double motorAngle;
    private double motorSpeed;
    private double flywheelAngle;
    private double flywheelSpeed;
    private double servoIntegrator;
    private double fgInputState;
    private double shaperState;
    private double sawState;
    private double comparatorState;
    private double driveState;
    private double protectorState;
    private double mainRail;
    private double motorCurrent;
    private double beltTorque;
    private double fgSignal;
    private double thermistorTemperatureC;

    TpsL2TransportReferenceModel(int sampleRate) {
        if (sampleRate < 8_000 || sampleRate > 192_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        dt = 1.0 / (sampleRate * OVERSAMPLE);
        nominalFlywheelSpeed = NOMINAL_TAPE_METRES_PER_SECOND / CAPSTAN_RADIUS;
        fgInputAlpha = rcAlpha(parallel(value("R601"), value("R602")), value("C601"));
        shaperAlpha = rcAlpha(value("R603"), value("C602"));
        sawAlpha = rcAlpha(value("R611") + value("RV601") + value("R610"),
                value("C603"));
        comparatorAlpha = rcAlpha(parallel(value("R604"), value("R605")), value("C604"));
        servoLeak = Math.exp(-dt / (value("R606") * value("C605")));
        driveAlpha = rcAlpha(value("R607") + value("R608") + value("R609"),
                value("C606"));
        protectorAlpha = rcAlpha(value("R612"), value("C607"));
        reset();
    }

    void setTapePosition(float position) {
        requestedPosition = Float.isFinite(position) ? clamp(position, 0f, 1f) : 0.5f;
    }

    void setTransportState(TapeTransportState state) {
        requestedState = state == null ? TapeTransportState.PLAYING : state;
    }

    void reset() {
        tapePosition = requestedPosition;
        state = requestedState;
        motorAngle = 0.0;
        motorSpeed = state == TapeTransportState.PLAYING ? nominalFlywheelSpeed
                * PULLEY_RATIO : 0.0;
        flywheelAngle = 0.0;
        flywheelSpeed = state == TapeTransportState.PLAYING ? nominalFlywheelSpeed : 0.0;
        servoIntegrator = 0.0;
        fgInputState = motorSpeed / PULLEY_RATIO;
        shaperState = fgInputState;
        sawState = fgInputState;
        comparatorState = 0.0;
        driveState = state == TapeTransportState.PLAYING
                ? TpsL2ElectromechanicalModel.MOTOR_OPERATING_VOLTS : 0.0;
        protectorState = 0.0;
        mainRail = 3.02 - 0.100 * BATTERY_RESISTANCE;
        motorCurrent = 0.0;
        beltTorque = 0.0;
        fgSignal = 0.0;
        thermistorTemperatureC = 25.0;
    }

    void advanceFrames(int frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("Negative frame count");
        }
        long steps = (long) frames * OVERSAMPLE;
        if (steps > 80_000_000L) {
            throw new IllegalArgumentException("Reference interval too large");
        }
        for (long step = 0; step < steps; step++) {
            integrate();
        }
    }

    float speedFraction() {
        return (float) (flywheelSpeed / nominalFlywheelSpeed);
    }

    float motorCurrentMilliamps() {
        return (float) (motorCurrent * 1_000.0);
    }

    float mainRailVolts() {
        return (float) mainRail;
    }

    float beltTorqueNewtonMetres() {
        return (float) beltTorque;
    }

    float fgSignal() {
        return (float) fgSignal;
    }

    private void integrate() {
        tapePosition = requestedPosition;
        state = requestedState;
        double ratio = PULLEY_RATIO;
        double targetSpeed = targetFlywheelSpeed(state);
        double fgMeasuredSpeed = motorSpeed / ratio;
        fgInputState += (fgMeasuredSpeed - fgInputState) * fgInputAlpha;
        shaperState += (fgInputState - shaperState) * shaperAlpha;
        sawState += (shaperState - sawState) * sawAlpha;
        double rawSpeedError = targetSpeed - sawState;
        comparatorState += (rawSpeedError - comparatorState) * comparatorAlpha;
        double speedError = rawSpeedError * 0.82 + comparatorState * 0.18;
        // THP601/D602 compensate servo drift and D601 protects the DC amplifier. Their precise
        // curves are not published, so the pin macro uses a silicon clamp plus a bounded healthy
        // thermistor coefficient rather than inventing an internal CX183 circuit.
        double thermalGain = 1.0
                - (thermistorTemperatureC - 25.0) * THERMISTOR_COEFFICIENT;
        servoIntegrator = clamp(servoIntegrator * servoLeak
                + speedError * SERVO_KI * thermalGain * dt, -0.62, 0.62);
        double baseDrive = state == TapeTransportState.STOPPED
                || state == TapeTransportState.PAUSED ? 0.0
                : TpsL2ElectromechanicalModel.MOTOR_OPERATING_VOLTS;
        double requestedDrive = clamp(baseDrive + speedError * SERVO_KP + servoIntegrator,
                0.0, mainRail);
        driveState += (requestedDrive - driveState) * driveAlpha;
        protectorState += (Math.abs(driveState) - protectorState) * protectorAlpha;
        double protectionDrop = Math.max(0.0, protectorState - mainRail) * 0.25;
        double drive = clamp(driveState - protectionDrop, 0.0, mainRail);

        double backEmf = MOTOR_KE * motorSpeed;
        double targetCurrent = clamp((drive - backEmf) / MOTOR_RESISTANCE, -0.025, 0.16);
        double chokeAlpha = 1.0 - Math.exp(-dt * MOTOR_RESISTANCE / value("L601"));
        motorCurrent += (targetCurrent - motorCurrent) * chokeAlpha;
        double motorTorque = MOTOR_KT * motorCurrent;
        double motorSideAngle = motorAngle / ratio;
        double motorSideSpeed = motorSpeed / ratio;
        beltTorque = BELT_STIFFNESS * (motorSideAngle - flywheelAngle)
                + BELT_DAMPING * (motorSideSpeed - flywheelSpeed);

        double direction = flywheelSpeed == 0.0 ? Math.signum(targetSpeed)
                : Math.signum(flywheelSpeed);
        double tapeTorque = direction * BASE_TAPE_TORQUE
                * (0.72 + TpsL2ElectromechanicalModel.positionLoadAt((float) tapePosition));
        if (state == TapeTransportState.STOPPED || state == TapeTransportState.PAUSED) {
            tapeTorque = 0.0;
        }
        double motorAcceleration = (motorTorque - beltTorque / ratio
                - MOTOR_VISCOUS * motorSpeed) / MOTOR_INERTIA;
        double flywheelAcceleration = (beltTorque - FLYWHEEL_VISCOUS * flywheelSpeed
                - direction * CAPSTAN_COULOMB_TORQUE - tapeTorque)
                / FLYWHEEL_INERTIA;

        // Symplectic update keeps the stiff belt state passive at the chosen 8x reference rate.
        motorSpeed += motorAcceleration * dt;
        flywheelSpeed += flywheelAcceleration * dt;
        if (!Double.isFinite(motorSpeed) || !Double.isFinite(flywheelSpeed)) {
            throw new IllegalStateException("TPS-L2 transport reference diverged");
        }
        motorSpeed = clamp(motorSpeed, -650.0, 650.0);
        flywheelSpeed = clamp(flywheelSpeed, -220.0, 220.0);
        motorAngle += motorSpeed * dt;
        flywheelAngle += flywheelSpeed * dt;

        // D901 operation LED current shares the battery bus. THP601 warms slightly from the
        // servo-board current and cools toward ambient, closing the slow temperature loop.
        double ledCurrent = state == TapeTransportState.STOPPED ? 0.0 : 0.003;
        double totalCurrent = 0.037 + ledCurrent + Math.max(0.0, motorCurrent);
        double thermalTarget = 25.0 + Math.max(0.0, motorCurrent) * 18.0;
        thermistorTemperatureC += (thermalTarget - thermistorTemperatureC)
                * (1.0 - Math.exp(-dt / 22.0));
        double batteryTarget = 3.02 - totalCurrent * BATTERY_RESISTANCE;
        double railAlpha = 1.0 - Math.exp(-dt
                / (BATTERY_RESISTANCE * MAIN_CAPACITANCE));
        mainRail += (batteryTarget - mainRail) * railAlpha;
        fgSignal = Math.sin(motorAngle * 6.0);
    }

    private double targetFlywheelSpeed(TapeTransportState state) {
        if (state == TapeTransportState.FAST_FORWARD) {
            return nominalFlywheelSpeed * 3.2;
        }
        if (state == TapeTransportState.REWIND) {
            return -nominalFlywheelSpeed * 3.0;
        }
        if (state == TapeTransportState.STOPPED || state == TapeTransportState.PAUSED) {
            return 0.0;
        }
        return nominalFlywheelSpeed;
    }

    private static double value(String reference) {
        return TpsL2Schematic.part(reference).value;
    }

    private double rcAlpha(double resistance, double capacitance) {
        return 1.0 - Math.exp(-dt / Math.max(1e-12, resistance * capacitance));
    }

    private static double parallel(double a, double b) {
        return a * b / Math.max(1e-12, a + b);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
