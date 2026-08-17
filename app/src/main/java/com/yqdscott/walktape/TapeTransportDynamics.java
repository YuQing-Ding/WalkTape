package com.yqdscott.walktape;

/**
 * What a cassette transport does between one key press and the tape actually obeying it.
 *
 * <p>Nothing in a real machine happens on the sample the key goes down. Pressing PLAY closes the
 * motor circuit and swings the pinch roller against the capstan, and the tape takes a few hundred
 * milliseconds to reach speed while the flywheel and the motor's own inertia are overcome; the
 * pitch rises into tune rather than starting in it. Pressing PAUSE releases the pinch roller and
 * the tape coasts to a halt against the head in a fraction of that, dropping in pitch as it goes.
 * STOP does the same and additionally retracts the head, so the sound disappears <em>before</em>
 * the tape has stopped moving. Winding lifts the head clear altogether.</p>
 *
 * <p>This class is the shared physics for those four behaviours. It produces two signals — how
 * fast the tape is moving and how well the head is touching it — and leaves each machine to decide
 * what its own electronics do with them.</p>
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>These are engineering priors, and are labelled as such rather than dressed up: no service
 * manual publishes a capstan spin-up curve. What constrains them is that Sony's own TPS-L2 figure
 * for startup — the speed deficit and how long it takes to settle — is already in this codebase and
 * measured, so the play-side constant is anchored to it and the rest is scaled from the mechanics.
 * A pinch roller releasing stops the tape several times faster than the motor gets it going, which
 * is why the down constant is the shorter one.</p>
 */
final class TapeTransportDynamics {

    /**
     * Time constants, in seconds, for a healthy portable transport.
     *
     * <p>Spin-up is dominated by the flywheel; the tape is audibly flat for the first fraction of
     * a second. Coast-down is dominated by tape drag against a retracted pinch roller, which is
     * much faster. Head retraction is a spring and a cam, faster still.</p>
     */
    static final float SPIN_UP_SECONDS = 0.34f;
    static final float COAST_DOWN_SECONDS = 0.11f;
    static final float HEAD_ENGAGE_SECONDS = 0.055f;
    static final float HEAD_RETRACT_SECONDS = 0.040f;

    /** How far below speed the tape starts when the pinch roller first bites. */
    static final float LAUNCH_SPEED = 0.06f;

    /**
     * Slip is bled back so a long session cannot walk the delay line off its end.
     *
     * <p>Physically the tape really is behind after a start, and stays behind. Carrying that
     * forever would need an unbounded buffer, so it is returned over a few seconds once the
     * transport is steady — slowly enough to stay under the wow the machine already has.</p>
     */
    private static final float SLIP_RETURN_SECONDS = 6.0f;

    /** Beyond this the accumulated slip is clamped rather than allowed to grow. */
    private final float maximumSlipSamples;

    private final float spinUpCoefficient;
    private final float coastDownCoefficient;
    private final float engageCoefficient;
    private final float retractCoefficient;
    private final float slipReturnCoefficient;
    private final int sampleRate;

    private volatile TapeTransportState requested = TapeTransportState.PLAYING;
    private TapeTransportState applied = TapeTransportState.PLAYING;
    private float speed = 1f;
    private float contact = 1f;
    private float slipSamples;
    private float transitionEnergy;

    TapeTransportDynamics(int sampleRate, int updateStride, float maximumSlipSamples) {
        this.sampleRate = Math.max(8_000, sampleRate);
        this.maximumSlipSamples = Math.max(16f, maximumSlipSamples);
        int stride = Math.max(1, updateStride);
        spinUpCoefficient = coefficient(stride, SPIN_UP_SECONDS);
        coastDownCoefficient = coefficient(stride, COAST_DOWN_SECONDS);
        engageCoefficient = coefficient(stride, HEAD_ENGAGE_SECONDS);
        retractCoefficient = coefficient(stride, HEAD_RETRACT_SECONDS);
        slipReturnCoefficient = coefficient(stride, SLIP_RETURN_SECONDS);
    }

    private float coefficient(int stride, float seconds) {
        return 1f - (float) Math.exp(-(double) stride / (sampleRate * seconds));
    }

    void setState(TapeTransportState state) {
        requested = state == null ? TapeTransportState.PLAYING : state;
    }

    TapeTransportState state() {
        return applied;
    }

    /** Starts settled and in tune, which is what a renderer built mid-tape should sound like. */
    void reset() {
        applied = requested;
        boolean rolling = isRolling(applied);
        speed = rolling ? 1f : 0f;
        contact = isTouching(applied) ? 1f : 0f;
        slipSamples = 0f;
        transitionEnergy = 0f;
    }

    /** Begins a transition from wherever the transport currently is, without jumping. */
    void resetToStarting() {
        applied = TapeTransportState.STARTING;
        requested = TapeTransportState.STARTING;
        speed = LAUNCH_SPEED;
        contact = 1f;
        slipSamples = 0f;
        transitionEnergy = 1f;
    }

    /**
     * Advances the mechanism by one control stride.
     *
     * <p>Call once per stride, not per sample: nothing here moves faster than a few tens of
     * milliseconds, so a stride of a few samples costs nothing in fidelity.</p>
     */
    void advance(int strideFrames) {
        TapeTransportState target = requested;
        if (target != applied) {
            transitionEnergy = 1f;
            applied = target;
        }

        float speedTarget = isRolling(applied) ? 1f : 0f;
        // Getting going is the slow direction; stopping is the fast one.
        float speedCoefficient = speedTarget > speed ? spinUpCoefficient : coastDownCoefficient;
        speed += (speedTarget - speed) * speedCoefficient;
        if (speed < 1e-4f) {
            speed = 0f;
        }

        float contactTarget = isTouching(applied) ? 1f : 0f;
        contact += (contactTarget - contact)
                * (contactTarget > contact ? engageCoefficient : retractCoefficient);
        if (contact < 1e-4f) {
            contact = 0f;
        }

        // Tape running slow means the programme arrives late: the shortfall accumulates as extra
        // delay, which is what turns a speed ramp into an audible pitch glide.
        slipSamples += (1f - speed) * strideFrames;
        if (speedTarget == 1f && speed > 0.999f) {
            slipSamples -= slipSamples * slipReturnCoefficient;
        }
        if (slipSamples > maximumSlipSamples) {
            slipSamples = maximumSlipSamples;
        } else if (slipSamples < 0f) {
            slipSamples = 0f;
        }

        transitionEnergy -= transitionEnergy * engageCoefficient;
        if (transitionEnergy < 1e-4f) {
            transitionEnergy = 0f;
        }
    }

    /** Capstan speed as a fraction of nominal. */
    float speed() {
        return speed;
    }

    /** How completely the head is against the tape, 0 when retracted or lifted for winding. */
    float headContact() {
        return contact;
    }

    /**
     * What the head actually puts out, which needs the tape to be moving as well as touching.
     *
     * <p>A playback head is an induction pickup: its EMF follows the <em>rate of change</em> of
     * the flux passing the gap, so it is proportional to tape speed. A stationary tape against a
     * perfectly seated head produces silence, not a held note. That is why pausing sounds the way
     * it does — the level falls away with the speed while the pitch drops, both from the same
     * cause — and it is also what stops a halted transport from quietly resuming once the delay
     * line's slip runs into its clamp.</p>
     */
    float headOutputGain() {
        return contact * speed;
    }

    /** Extra playback delay accumulated by running slow, in samples. */
    float slipSamples() {
        return slipSamples;
    }

    /** Decays from 1 at each key press, for whatever thump the chassis conducts. */
    float transitionEnergy() {
        return transitionEnergy;
    }

    /**
     * How loud the running gear is, relative to steady play.
     *
     * <p>Winding spins the reels far faster than playing does and drives them through a different
     * part of the gear train, so it is audibly louder; rewind is the noisier of the two on most
     * portables because the supply side is being driven backwards. Scaling by speed is what makes
     * the noise arrive and leave with the motor instead of switching on and off.</p>
     *
     * <p>The two winding factors are the ones {@code TpsL2ElectromechanicalModel} already uses, so
     * the two machines do not disagree about the same mechanism. That model applies them itself,
     * which is why only the machine without one asks this class for them.</p>
     */
    float motorNoiseGain() {
        float winding = applied == TapeTransportState.FAST_FORWARD ? 1.8f
                : applied == TapeTransportState.REWIND ? 2.05f : 1f;
        return winding * speed;
    }

    /** True once the head is clear and the tape stopped, so the renderer can skip its work. */
    boolean isSilent() {
        return contact <= 0f && speed <= 0f;
    }

    private static boolean isRolling(TapeTransportState state) {
        return state == TapeTransportState.PLAYING || state == TapeTransportState.STARTING
                || state == TapeTransportState.FAST_FORWARD
                || state == TapeTransportState.REWIND;
    }

    /**
     * Whether the head is against the tape.
     *
     * <p>Winding lifts it clear on every machine here, which is why fast-forward and rewind are
     * silent rather than a sped-up version of the programme: there is nothing for the head to
     * read.</p>
     */
    private static boolean isTouching(TapeTransportState state) {
        return state == TapeTransportState.PLAYING || state == TapeTransportState.STARTING
                || state == TapeTransportState.PAUSED;
    }
}
