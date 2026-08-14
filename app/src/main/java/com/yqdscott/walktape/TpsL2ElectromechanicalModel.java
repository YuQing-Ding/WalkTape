package com.yqdscott.walktape;

/**
 * Shared low-rate physical state for the TPS-L2 power rail, CX183 FG servo and belt transport.
 *
 * <p>The Sony service manual gives the rail topology, operating voltages and transport currents:
 * two AA cells and C901 (220 uF) feed the CX183/Q601-Q603 servo and MNF-1600B FG motor, while
 * each CX184 power amplifier provides a ripple-filtered 2.7/2.6 V rail to its CX182 playback
 * preamplifier. Battery resistance and mechanical inertias are not published, so their values are
 * conservative healthy-unit priors constrained by the measured startup and wow/flutter traces.</p>
 */
final class TpsL2ElectromechanicalModel {
    static final float PLAY_CURRENT_MA = 100f;
    static final float FAST_FORWARD_CURRENT_MA = 105f;
    static final float REWIND_CURRENT_MA = 110f;
    static final float MAIN_RESERVOIR_UF = 220f;
    static final float PREAMP_RAIL_VOLTS = 2.6f;
    static final float POWER_AMP_FILTERED_RAIL_VOLTS = 2.7f;
    static final float MOTOR_OPERATING_VOLTS = 1.9f;

    private static final double TWO_PI = Math.PI * 2.0;
    private static final int CONTROL_STRIDE = 4;
    private static final float BATTERY_OPEN_CIRCUIT_VOLTS =
            TpsL2Schematic.value("P-BATT-V-FULL");
    private static final float BATTERY_END_VOLTS = TpsL2Schematic.value("P-BATT-V-END");
    private static final float BATTERY_CAPACITY_AMP_SECONDS =
            TpsL2Schematic.value("P-BATT-AH") * 3_600f;
    private static final float BATTERY_SERIES_OHMS = TpsL2Schematic.value("P-BATT-R");
    private static final float BATTERY_END_OHMS = TpsL2Schematic.value("P-BATT-R-END");

    /**
     * Normalised off-load terminal voltage of the alkaline pair against depth of discharge.
     *
     * <p>Zinc/manganese-dioxide cells do not hold a plateau the way NiMH does; they slope steadily
     * and then fall away over the last fifth. The two endpoints are the published ratings — a
     * fresh pair, and 0.9 V per cell at the rated 2.1 Ah for a 100 mA load — while the interior of
     * the curve is a declared engineering prior for the classic alkaline shape, not a measurement.
     * Index {@code i} is depth {@code i/8}.</p>
     */
    private static final float[] BATTERY_CURVE = {
            1.000f, 0.777f, 0.662f, 0.577f, 0.492f, 0.400f, 0.308f, 0.180f, 0.000f
    };
    private static final float CHANNEL_RIPPLE_FILTER_OHMS = TpsL2Schematic.value("R112");
    private static final float CHANNEL_DECOUPLING_FARADS = TpsL2Schematic.value("C109");
    private static final float NOMINAL_POSITION_LOAD = 0.5f;

    private final float mainRailCoefficient;
    private final float rippleFilterCoefficient;
    private final float servoCoefficient;
    private final float brushStepSine;
    private final float brushStepCosine;
    private final float commutatorStepSine;
    private final float commutatorStepCosine;
    private final float transientStepSine;
    private final float transientStepCosine;
    private final float transientDecay;
    private final long initialSeed;

    private volatile float requestedTapePosition = 0.5f;
    private volatile float requestedBatteryDepth = 0f;
    private volatile TapeTransportState requestedState = TapeTransportState.STARTING;
    private float tapePosition;
    private float batteryDepth;
    private TapeTransportState state;
    private float mainRail;
    private float filteredRail;
    private float servoLoadEstimate;
    private float residualSpeedError;
    private float motorLoad;
    private float currentAmps;
    private float brushSine;
    private float brushCosine;
    private float commutatorSine;
    private float commutatorCosine;
    private float electricalNoise;
    private float transientSine;
    private float transientCosine;
    private float transientEnvelope;
    private float transientPolarity;
    private long noiseState;

    TpsL2ElectromechanicalModel(int sampleRate, long seed) {
        if (sampleRate < 8_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        int controlRate = Math.max(1, sampleRate / CONTROL_STRIDE);
        // C901 is a real 220 uF reservoir. The battery resistance is the documented topology's
        // missing source impedance and represents a healthy pair of alkaline cells plus contacts.
        mainRailCoefficient = rcCoefficient(controlRate,
                BATTERY_SERIES_OHMS, MAIN_RESERVOIR_UF * 0.000001f);
        // CX184's ripple-filter output feeds the 47 uF C109/C209 decouplers in the revised model.
        rippleFilterCoefficient = rcCoefficient(controlRate,
                CHANNEL_RIPPLE_FILTER_OHMS, CHANNEL_DECOUPLING_FARADS);
        // Slow load estimate is the CX183 FG loop's finite correction through belt compliance.
        servoCoefficient = 1f - (float) Math.exp(-1.0 / (controlRate * 0.31));

        double brushStep = TWO_PI * 91.7 / sampleRate;
        brushStepSine = (float) Math.sin(brushStep);
        brushStepCosine = (float) Math.cos(brushStep);
        double commutatorStep = TWO_PI * 275.1 / sampleRate;
        commutatorStepSine = (float) Math.sin(commutatorStep);
        commutatorStepCosine = (float) Math.cos(commutatorStep);
        double transientStep = TWO_PI * 73.0 / sampleRate;
        transientStepSine = (float) Math.sin(transientStep);
        transientStepCosine = (float) Math.cos(transientStep);
        transientDecay = (float) Math.exp(-1.0 / (sampleRate * 0.046));
        initialSeed = seed == 0 ? 0x5450534c504f5745L : seed;
        reset();
    }

    void setTapePosition(float position) {
        requestedTapePosition = finite(position) ? clamp(position, 0f, 1f) : 0.5f;
    }

    /**
     * Sets how far the two AA cells have been discharged, 0 for fresh and 1 for exhausted.
     *
     * <p>This is an input rather than something the model integrates on its own. Charge drawn is a
     * property of the whole listening session, not of one track, and how quickly a user's virtual
     * batteries should age is a product decision rather than a circuit one. Fresh is the default,
     * so nothing changes until something drives it.</p>
     */
    void setBatteryDepthOfDischarge(float depth) {
        requestedBatteryDepth = finite(depth) ? clamp(depth, 0f, 1f) : 0f;
    }

    /**
     * Off-load terminal voltage of the pair at the current depth of discharge.
     *
     * <p>Both endpoints are the published LR6 ratings; the slope between them is the declared
     * alkaline prior in {@link #BATTERY_CURVE}.</p>
     */
    static float batteryOpenCircuitVolts(float depth) {
        float bounded = clamp(depth, 0f, 1f);
        float scaled = bounded * (BATTERY_CURVE.length - 1);
        int lower = Math.min(BATTERY_CURVE.length - 2, (int) scaled);
        float fraction = scaled - lower;
        float normalised = BATTERY_CURVE[lower]
                + (BATTERY_CURVE[lower + 1] - BATTERY_CURVE[lower]) * fraction;
        return BATTERY_END_VOLTS + normalised * (BATTERY_OPEN_CIRCUIT_VOLTS - BATTERY_END_VOLTS);
    }

    /**
     * Series resistance of the pair, which climbs steeply as the cells are used up.
     *
     * <p>A tired alkaline cell fails by internal resistance as much as by lost voltage, which is
     * why a Walkman with old batteries sags on bass transients before it refuses to run at all.</p>
     */
    static float batterySeriesOhms(float depth) {
        float bounded = clamp(depth, 0f, 1f);
        float growth = bounded * bounded * bounded;
        return BATTERY_SERIES_OHMS + growth * (BATTERY_END_OHMS - BATTERY_SERIES_OHMS);
    }

    /** Hours of PLAY a fresh pair supports at the service-manual transport current. */
    static float ratedPlayHours() {
        return BATTERY_CAPACITY_AMP_SECONDS / (PLAY_CURRENT_MA / 1_000f) / 3_600f;
    }

    void setTransportState(TapeTransportState requested) {
        requestedState = requested == null ? TapeTransportState.PLAYING : requested;
    }

    void reset() {
        tapePosition = clamp(requestedTapePosition, 0f, 1f);
        batteryDepth = clamp(requestedBatteryDepth, 0f, 1f);
        state = requestedState;
        currentAmps = currentFor(state);
        mainRail = batteryOpenCircuitVolts(batteryDepth)
                - currentAmps * batterySeriesOhms(batteryDepth);
        filteredRail = POWER_AMP_FILTERED_RAIL_VOLTS;
        servoLoadEstimate = positionLoad(tapePosition);
        residualSpeedError = 0f;
        motorLoad = servoLoadEstimate;
        brushSine = (float) Math.sin(0.73);
        brushCosine = (float) Math.cos(0.73);
        commutatorSine = (float) Math.sin(3.11);
        commutatorCosine = (float) Math.cos(3.11);
        electricalNoise = 0f;
        transientSine = 0f;
        transientCosine = 1f;
        transientEnvelope = 0f;
        transientPolarity = 1f;
        noiseState = initialSeed;
    }

    /** Advances the shared power/servo state at the TPS transport control rate. */
    void advanceControl(float camLoad, float randomContactLoad, float audioPowerLoad) {
        tapePosition = clamp(requestedTapePosition, 0f, 1f);
        TapeTransportState nextState = requestedState;
        if (nextState != state) {
            triggerTransition(state, nextState);
            state = nextState;
        }
        float positionLoad = positionLoad(tapePosition);
        float boundedCam = clamp(camLoad, 0f, 1f);
        float contact = clamp(randomContactLoad, -1f, 1f);
        motorLoad = positionLoad + boundedCam * 0.19f + contact * 0.018f;

        float baseCurrent = currentFor(state);
        currentAmps = baseCurrent + Math.max(-0.006f,
                (motorLoad - NOMINAL_POSITION_LOAD) * 0.025f)
                + clamp(audioPowerLoad, 0f, 1f) * 0.007f;
        // Both the cell voltage and its series resistance move as the pair is used up, so a tired
        // battery both lowers the rail and lets programme current pull it further down.
        batteryDepth = requestedBatteryDepth;
        float batteryTarget = batteryOpenCircuitVolts(batteryDepth)
                - currentAmps * batterySeriesOhms(batteryDepth);
        mainRail += (batteryTarget - mainRail) * mainRailCoefficient;

        // CX184 cannot create rail voltage; its ripple filter follows the sagging main bus while
        // rejecting motor-current steps before they reach the CX182/Q101 playback chain.
        // No floor: the ripple filter is a follower and cannot invent rail voltage. A healthy pair
        // never brings the main bus near this region, but exhausted cells must be allowed to drag
        // the playback rail down, which is what makes a dying Walkman quiet before it stops.
        float rippleTarget = Math.min(POWER_AMP_FILTERED_RAIL_VOLTS,
                Math.max(0f, mainRail - 0.20f));
        filteredRail += (rippleTarget - filteredRail) * rippleFilterCoefficient;

        // The measured cam/random flutter components in TpsL2Dsp are already the post-servo
        // residual. Do not count them twice here. This state supplies only the slow load change
        // caused by changing tape-pack radius and the rail coupling caused by all motor torque.
        servoLoadEstimate += (positionLoad - servoLoadEstimate) * servoCoefficient;
        float uncorrectedLoad = positionLoad - servoLoadEstimate;
        float railError = Math.max(0f,
                (POWER_AMP_FILTERED_RAIL_VOLTS - filteredRail) / POWER_AMP_FILTERED_RAIL_VOLTS);
        // Positive error means tape is slow and therefore accumulates additional delay.
        residualSpeedError = uncorrectedLoad * 0.0018f + railError * 0.0015f;
    }

    float residualSpeedError() {
        return residualSpeedError;
    }

    float filteredRailVolts() {
        return filteredRail;
    }

    float outputHeadroomScale() {
        return clamp(filteredRail / POWER_AMP_FILTERED_RAIL_VOLTS, 0.84f, 1.03f);
    }

    float mechanicalNoiseGain() {
        float winding = state == TapeTransportState.FAST_FORWARD
                ? 1.8f : state == TapeTransportState.REWIND ? 2.05f : 1f;
        return winding * clamp(0.88f + motorLoad * 0.24f, 0.85f, 1.35f);
    }

    /** Motor/servo ripple left after C901 and the CX184/47 uF filter network. */
    float nextRailRipple() {
        float oldBrush = brushSine;
        brushSine = oldBrush * brushStepCosine + brushCosine * brushStepSine;
        brushCosine = brushCosine * brushStepCosine - oldBrush * brushStepSine;
        float oldCommutator = commutatorSine;
        commutatorSine = oldCommutator * commutatorStepCosine
                + commutatorCosine * commutatorStepSine;
        commutatorCosine = commutatorCosine * commutatorStepCosine
                - oldCommutator * commutatorStepSine;

        long x = noiseState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        noiseState = x;
        float white = ((x >>> 40) / 8_388_607.5f) - 1f;
        electricalNoise += (white - electricalNoise) * 0.026f;
        float loadGain = mechanicalNoiseGain();
        return (brushSine * 0.000020f + commutatorSine * 0.000008f
                + electricalNoise * 0.000006f) * loadGain;
    }

    /** Chassis-conducted key/cam thump generated only by a real transport state transition. */
    float nextMechanicalTransient() {
        float oldSine = transientSine;
        transientSine = oldSine * transientStepCosine
                + transientCosine * transientStepSine;
        transientCosine = transientCosine * transientStepCosine
                - oldSine * transientStepSine;
        float envelope = transientEnvelope;
        transientEnvelope *= transientDecay;
        if (transientEnvelope < 1e-12f) {
            transientEnvelope = 0f;
        }
        return transientPolarity * envelope * (transientSine * 0.72f
                + electricalNoise * 0.28f);
    }

    float currentMilliamps() {
        return currentAmps * 1_000f;
    }

    static float positionLoadAt(float position) {
        return positionLoad(clamp(position, 0f, 1f));
    }

    private static float currentFor(TapeTransportState state) {
        if (state == TapeTransportState.FAST_FORWARD) {
            return FAST_FORWARD_CURRENT_MA / 1_000f;
        }
        if (state == TapeTransportState.REWIND) {
            return REWIND_CURRENT_MA / 1_000f;
        }
        if (state == TapeTransportState.STOPPED || state == TapeTransportState.PAUSED) {
            return 0.004f;
        }
        return PLAY_CURRENT_MA / 1_000f;
    }

    private void triggerTransition(TapeTransportState previous, TapeTransportState next) {
        if (next == TapeTransportState.FAST_FORWARD || next == TapeTransportState.REWIND) {
            transientEnvelope = 0.010f;
            transientPolarity = next == TapeTransportState.REWIND ? -1f : 1f;
        } else if (next == TapeTransportState.STOPPED || next == TapeTransportState.PAUSED) {
            transientEnvelope = 0.0075f;
            transientPolarity = -1f;
        } else if (previous == TapeTransportState.STOPPED
                || previous == TapeTransportState.PAUSED
                || previous == TapeTransportState.FAST_FORWARD
                || previous == TapeTransportState.REWIND) {
            transientEnvelope = 0.0085f;
            transientPolarity = 1f;
        }
        transientSine = 0f;
        transientCosine = 1f;
    }

    private static float positionLoad(float position) {
        // Pack radii follow sqrt(area). Back tension rises with supply radius while take-up torque
        // rises as its pack grows; their broad U-shaped sum is lowest near the middle of a side.
        float coreSquared = 0.17f;
        float supplyRadius = (float) Math.sqrt(coreSquared + (1f - position) * 0.83f);
        float takeupRadius = (float) Math.sqrt(coreSquared + position * 0.83f);
        float raw = 0.36f * supplyRadius + 0.31f * takeupRadius
                + 0.19f / Math.max(0.41f, takeupRadius);
        // Normalise the calibrated middle-of-side load to 0.5.
        return raw * (NOMINAL_POSITION_LOAD / 0.7608652f);
    }

    private static float rcCoefficient(int sampleRate, float resistance, float capacitance) {
        return 1f - (float) Math.exp(-1.0
                / Math.max(1.0, sampleRate * resistance * capacitance));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
