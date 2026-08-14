package com.yqdscott.walktape;

/**
 * Offline, oversampled component/macro reference for the revised TPS-L2 playback electronics.
 *
 * <p>This is intentionally not instantiated on Android's realtime audio thread. It retains
 * separate states for the head/coupling network, Q101/Q201, CX182 AGC pins, the external EQ/tone
 * network, CX184 ripple/output pins and the headphone coupling capacitor. Sony published the
 * external circuit and IC block/pin functions but not the CX182/CX184 transistor masks; those two
 * devices are therefore behavioural pin macros. The fast renderer is regression-calibrated to
 * this reference and to measured whole-machine curves.</p>
 */
final class TpsL2CircuitReferenceModel {
    private static final int OVERSAMPLE = 4;
    private static final float NOMINAL_RAIL = 2.7f;
    private final int sampleRate;
    private final Channel left;
    private final Channel right;
    private final Channel leftLinear;
    private final Channel rightLinear;
    private final Supply supply;
    private final TpsL2Dsp measuredTarget;
    private boolean highTone;

    TpsL2CircuitReferenceModel(int sampleRate) {
        if (sampleRate < 8_000 || sampleRate > 192_000) {
            throw new IllegalArgumentException("Unsupported sample rate: " + sampleRate);
        }
        this.sampleRate = sampleRate;
        int referenceRate = sampleRate * OVERSAMPLE;
        supply = new Supply(referenceRate);
        left = new Channel(referenceRate, 100, 0.0075f);
        right = new Channel(referenceRate, 200, -0.0062f);
        leftLinear = new Channel(referenceRate, 100, 0f);
        rightLinear = new Channel(referenceRate, 200, 0f);
        measuredTarget = new TpsL2Dsp(sampleRate, 0x435243554954L,
                false, false, false, false);
        reset();
    }

    void setHighTone(boolean highTone) {
        this.highTone = highTone;
        measuredTarget.setHighTape(highTone);
    }

    void reset() {
        supply.reset();
        left.reset();
        right.reset();
        leftLinear.reset();
        rightLinear.reset();
        measuredTarget.reset();
    }

    void process(float[] stereo, int frameCount) {
        if (stereo == null || frameCount < 0 || frameCount > stereo.length / 2) {
            throw new IllegalArgumentException("Invalid stereo frame count");
        }
        // The public whole-machine trace is the small-signal calibration authority. The two
        // component branches below are the same external netlist: subtracting the nominal linear
        // branch prevents double-counting its magnitude/phase while retaining junction, AGC,
        // loading and rail-dependent residuals from the nonlinear branch.
        float[] raw = new float[frameCount * 2];
        System.arraycopy(stereo, 0, raw, 0, raw.length);
        measuredTarget.process(stereo, frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            int index = frame * 2;
            float inLeft = sanitize(raw[index]);
            float inRight = sanitize(raw[index + 1]);
            float outLeft = 0f;
            float outRight = 0f;
            float linearLeft = 0f;
            float linearRight = 0f;
            for (int phase = 0; phase < OVERSAMPLE; phase++) {
                float programme = Math.max(Math.abs(inLeft), Math.abs(inRight));
                supply.step(programme);
                outLeft = left.step(inLeft, highTone, supply.preampRail(),
                        supply.powerRail(), true);
                outRight = right.step(inRight, highTone, supply.preampRail() * 0.998f,
                        supply.powerRail(), true);
                linearLeft = leftLinear.step(inLeft, highTone, 2.6f, NOMINAL_RAIL, false);
                linearRight = rightLinear.step(inRight, highTone, 2.6f, NOMINAL_RAIL, false);
            }
            stereo[index] = sanitizeOutput(stereo[index] + (outLeft - linearLeft) * 0.35f);
            stereo[index + 1] = sanitizeOutput(stereo[index + 1]
                    + (outRight - linearRight) * 0.35f);
        }
    }

    float preampRailVolts() {
        return supply.preampRail();
    }

    static double outputCouplingCornerHertz(double headphoneOhms) {
        double resistance = TpsL2Schematic.value("R801") + Math.max(8.0, headphoneOhms);
        return 1.0 / (Math.PI * 2.0 * resistance * TpsL2Schematic.value("C116"));
    }

    static int oversampleFactor() {
        return OVERSAMPLE;
    }

    private static final class Channel {
        private final int channel;
        private final HeadNetwork head;
        private final Transistor2458 transistor;
        private final Cx182Macro preamp;
        private final ExternalEq eq;
        private final Cx184Macro powerAmp;

        Channel(int sampleRate, int channel, float asymmetry) {
            this.channel = channel;
            head = new HeadNetwork(sampleRate, channel);
            transistor = new Transistor2458(sampleRate, channel, asymmetry);
            preamp = new Cx182Macro(sampleRate, channel);
            eq = new ExternalEq(sampleRate, channel);
            powerAmp = new Cx184Macro(sampleRate, channel);
        }

        float step(float input, boolean highTone, float preampRail, float powerRail,
                   boolean nonlinear) {
            float value = head.step(input);
            value = transistor.step(value, preampRail, nonlinear);
            value = preamp.step(value, preampRail, nonlinear);
            value = eq.step(value, highTone);
            // The physical 20 kohm audio taper is evaluated at its documented reference position.
            float volumeOhms = value("RV" + (channel + 1));
            float wiper = 0.70f;
            float upper = volumeOhms * (1f - wiper);
            float lower = volumeOhms * wiper;
            value *= lower / (upper + lower);
            return powerAmp.step(value, powerRail, nonlinear);
        }

        void reset() {
            head.reset();
            transistor.reset();
            preamp.reset();
            eq.reset();
            powerAmp.reset();
        }
    }

    private static final class HeadNetwork {
        private final OnePoleLowPass winding;
        private final OnePoleLowPass biasBypass;
        private final DcBlock coupling;

        HeadNetwork(int sampleRate, int channel) {
            float headR = value("P-HEAD-R");
            float headL = value("P-HEAD-L");
            winding = new OnePoleLowPass(sampleRate,
                    headR / ((float) Math.PI * 2f * headL));
            biasBypass = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 1)), value("C" + (channel + 2))));
            float inputR = parallel(value("R" + (channel + 1)),
                    value("R" + (channel + 2)));
            coupling = new DcBlock(sampleRate,
                    rcCorner(inputR, value("C" + (channel + 1))));
        }

        float step(float input) {
            // 100 mV full-scale equivalent at the playback head before the model's calibrated gain.
            float wound = winding.step(input);
            float biasMemory = biasBypass.step(wound);
            return coupling.step(wound - biasMemory * 0.015f) * 0.10f;
        }

        void reset() {
            winding.reset();
            biasBypass.reset();
            coupling.reset();
        }
    }

    /** 2SC2458 large-signal junction macro constrained by the Toshiba hFE/noise datasheet. */
    private static final class Transistor2458 {
        private static final float THERMAL_VOLTS = 0.02585f;
        private static final float SATURATION_CURRENT = value("P-Q-IS");
        private static final float FORWARD_BETA = value("P-Q-BETA");
        private final float asymmetry;
        private final OnePoleLowPass collectorFeedback;
        private final DcBlock emitterBypass;
        private float biasEnvelope;

        Transistor2458(int sampleRate, int channel, float asymmetry) {
            this.asymmetry = asymmetry;
            collectorFeedback = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 3)), value("C" + (channel + 4))));
            emitterBypass = new DcBlock(sampleRate,
                    rcCorner(value("R" + (channel + 4)), value("C" + (channel + 3))));
        }

        float step(float input, float rail, boolean nonlinear) {
            float vbeDelta = clamp(input * 0.045f, -0.12f, 0.12f);
            double collectorDelta = SATURATION_CURRENT
                    * Math.expm1(vbeDelta / THERMAL_VOLTS) * FORWARD_BETA;
            float junction = input + (float) collectorDelta * 18f;
            float bypassed = emitterBypass.step(junction);
            float feedback = collectorFeedback.step(bypassed);
            float shaped = feedback + (nonlinear ? asymmetry * feedback * feedback
                    - 0.010f * feedback * feedback * feedback : 0f);
            float railScale = clamp(rail / 2.6f, 0.82f, 1.04f);
            biasEnvelope += (Math.abs(shaped) - biasEnvelope) * 0.0007f;
            return shaped * railScale / (1f + (nonlinear ? biasEnvelope * 0.018f : 0f));
        }

        void reset() {
            collectorFeedback.reset();
            emitterBypass.reset();
            biasEnvelope = 0f;
        }
    }

    /** CX182 pin-level preamp/AGC macro; the unpublished internal transistor netlist is not faked. */
    private static final class Cx182Macro {
        private final DcBlock inputCoupling;
        private final OnePoleLowPass agcDetector;
        private final OnePoleLowPass compensation;
        private float envelope;

        Cx182Macro(int sampleRate, int channel) {
            inputCoupling = new DcBlock(sampleRate,
                    rcCorner(value("R" + (channel + 5)), value("C" + (channel + 5))));
            agcDetector = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 8)), value("C" + (channel + 6))));
            compensation = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 5)), value("C" + (channel + 10))));
        }

        float step(float input, float rail, boolean nonlinear) {
            float coupled = inputCoupling.step(input);
            float detected = agcDetector.step(Math.abs(coupled));
            envelope += (detected - envelope) * (detected > envelope ? 0.004f : 0.00008f);
            float gain = 10.0f / (1f + (nonlinear
                    ? Math.max(0f, envelope - 0.16f) * 1.8f : 0f));
            float compensated = compensation.step(coupled) * 0.03f + coupled * 0.97f;
            float headroom = 0.86f * clamp(rail / 2.6f, 0.80f, 1.03f);
            return nonlinear ? softLimit(compensated * gain, headroom) : compensated * gain;
        }

        void reset() {
            inputCoupling.reset();
            agcDetector.reset();
            compensation.reset();
            envelope = 0f;
        }
    }

    /** External R105-R111/C105-C111 feedback and LOW/HIGH switch network. */
    private static final class ExternalEq {
        private final DcBlock seriesCoupling;
        private final OnePoleLowPass playbackPole;
        private final OnePoleLowPass highBranch;
        private final OnePoleLowPass feedbackCompensation;
        private final OnePoleLowPass railDecouplingResidue;

        ExternalEq(int sampleRate, int channel) {
            seriesCoupling = new DcBlock(sampleRate,
                    rcCorner(value("R" + (channel + 9)) + value("R" + (channel + 10)),
                            value("C" + (channel + 7))));
            playbackPole = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 7)), value("C" + (channel + 8))));
            highBranch = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 6)), value("C" + (channel + 7))));
            feedbackCompensation = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 11)), value("C" + (channel + 11))));
            railDecouplingResidue = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 12)), value("C" + (channel + 9))));
        }

        float step(float input, boolean highTone) {
            float coupled = seriesCoupling.step(input);
            float low = playbackPole.step(coupled);
            float split = coupled - highBranch.step(coupled);
            float feedback = feedbackCompensation.step(low);
            float decoupled = railDecouplingResidue.step(feedback);
            return low * 0.84f + decoupled * 0.16f + (highTone ? split * 0.72f : 0f);
        }

        void reset() {
            seriesCoupling.reset();
            playbackPole.reset();
            highBranch.reset();
            feedbackCompensation.reset();
            railDecouplingResidue.reset();
        }
    }

    /** CX184 pins 2/3/5/6 power stages, pin 8 ripple filter and all external RC parts. */
    private static final class Cx184Macro {
        private final DcBlock inputFeedback;
        private final OnePoleLowPass phaseCompensation;
        private final OnePoleLowPass returnCompensation;
        private final DcBlock outputCoupling;
        private final OnePoleLowPass localSupply;

        Cx184Macro(int sampleRate, int channel) {
            inputFeedback = new DcBlock(sampleRate,
                    rcCorner(value("R" + (channel + 13)), value("C" + (channel + 12))));
            phaseCompensation = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 14)), value("C" + (channel + 14))));
            returnCompensation = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 14)), value("C" + (channel + 15))));
            String primarySeries = "R80" + (channel == 100 ? 1 : 3);
            String secondarySeries = "R80" + (channel == 100 ? 2 : 4);
            float primaryBranch = value("P-HP-LOAD") + value(primarySeries);
            // The reference measurement uses one 35 ohm headset. The second physical socket is
            // open, but its series resistor remains an explicit (near-open) branch in the netlist.
            float secondaryBranch = 1e12f + value(secondarySeries);
            float load = 1f / (1f / primaryBranch + 1f / secondaryBranch);
            outputCoupling = new DcBlock(sampleRate,
                    rcCorner(load, value("C" + (channel + 16))));
            localSupply = new OnePoleLowPass(sampleRate,
                    rcCorner(value("R" + (channel + 13)), value("C" + (channel + 13))));
        }

        float step(float input, float rail, boolean nonlinear) {
            float feedback = inputFeedback.step(input);
            float compensated = phaseCompensation.step(feedback);
            float returned = returnCompensation.step(feedback);
            float supplyMemory = localSupply.step(rail / NOMINAL_RAIL);
            float drive = feedback * 0.92f + compensated * 0.04f + returned * 0.04f;
            float headroom = 0.90f * clamp(supplyMemory, 0.82f, 1.03f);
            return outputCoupling.step(nonlinear ? softLimit(drive, headroom) : drive);
        }

        void reset() {
            inputFeedback.reset();
            phaseCompensation.reset();
            returnCompensation.reset();
            outputCoupling.reset();
            localSupply.resetToOne();
        }
    }

    private static final class Supply {
        private final float mainAlpha;
        private final float cx184Alpha;
        private final float channelAlpha;
        private float mainRail;
        private float powerRail;
        private float preampRail;

        Supply(int sampleRate) {
            mainAlpha = rcAlpha(sampleRate, value("P-BATT-R"), value("C901"));
            cx184Alpha = rcAlpha(sampleRate, value("R301"), value("C301"));
            channelAlpha = rcAlpha(sampleRate, value("R112"), value("C109"));
        }

        void step(float programme) {
            float audioCurrent = Math.min(0.008f, programme * programme * 0.008f);
            float target = 3.02f - (0.100f + audioCurrent) * value("P-BATT-R");
            mainRail += (target - mainRail) * mainAlpha;
            // The internal CX184 ripple filter regulates the DC level but has finite PSRR and
            // dynamic output impedance. Programme current therefore leaves a small residual on
            // pin 8 even while a fresh 3 V battery provides enough static dropout margin.
            float powerTarget = Math.min(NOMINAL_RAIL, mainRail - 0.20f)
                    - audioCurrent * value("P-CX184-RO");
            powerRail += (powerTarget - powerRail) * cx184Alpha;
            float preampTarget = Math.min(2.6f, powerRail - 0.10f)
                    - audioCurrent * value("P-CX182-RO");
            preampRail += (preampTarget - preampRail) * channelAlpha;
        }

        float preampRail() {
            return preampRail;
        }

        float powerRail() {
            return powerRail;
        }

        void reset() {
            mainRail = 3.02f - 0.100f * value("P-BATT-R");
            powerRail = NOMINAL_RAIL;
            preampRail = 2.6f;
        }
    }

    private static final class OnePoleLowPass {
        private final float alpha;
        private float state;

        OnePoleLowPass(int sampleRate, float corner) {
            float bounded = clamp(corner, 0.01f, sampleRate * 0.45f);
            alpha = 1f - (float) Math.exp(-Math.PI * 2.0 * bounded / sampleRate);
        }

        float step(float input) {
            state += (input - state) * alpha;
            return state;
        }

        void reset() {
            state = 0f;
        }

        void resetToOne() {
            state = 1f;
        }
    }

    private static final class DcBlock {
        private final float pole;
        private float previousInput;
        private float previousOutput;

        DcBlock(int sampleRate, float corner) {
            pole = (float) Math.exp(-Math.PI * 2.0
                    * clamp(corner, 0.01f, sampleRate * 0.45f) / sampleRate);
        }

        float step(float input) {
            float output = input - previousInput + pole * previousOutput;
            previousInput = input;
            previousOutput = Math.abs(output) < 1e-20f ? 0f : output;
            return previousOutput;
        }

        void reset() {
            previousInput = 0f;
            previousOutput = 0f;
        }
    }

    private static float value(String reference) {
        return TpsL2Schematic.value(reference);
    }

    private static float parallel(float a, float b) {
        return a * b / Math.max(1e-12f, a + b);
    }

    private static float rcCorner(float resistance, float capacitance) {
        return 1f / Math.max(1e-12f, (float) Math.PI * 2f * resistance * capacitance);
    }

    private static float rcAlpha(int sampleRate, float resistance, float capacitance) {
        return 1f - (float) Math.exp(-1.0
                / Math.max(1.0, sampleRate * resistance * capacitance));
    }

    private static float softLimit(float input, float headroom) {
        float safeHeadroom = Math.max(0.01f, headroom);
        return (float) Math.tanh(input / safeHeadroom) * safeHeadroom;
    }

    private static float sanitize(float value) {
        return Float.isFinite(value) ? clamp(value, -8f, 8f) : 0f;
    }

    private static float sanitizeOutput(float value) {
        return Float.isFinite(value) ? clamp(value, -0.995f, 0.995f) : 0f;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
