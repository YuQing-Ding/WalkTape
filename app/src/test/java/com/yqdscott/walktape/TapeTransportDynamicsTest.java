package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The mechanism between a key press and the tape obeying it.
 *
 * <p>What these pin is the <em>asymmetry</em>, because that is what makes a transport sound like a
 * machine rather than a switch: a capstan takes several times longer to reach speed than a released
 * pinch roller takes to stop the tape, and the head retracts faster than either. Any change that
 * flattens those three into one time constant fails here.</p>
 */
public class TapeTransportDynamicsTest {
    private static final int RATE = 48_000;
    private static final int STRIDE = 4;

    @Test
    public void aSettledTransportStartsInTuneAndTouchingTheTape() {
        TapeTransportDynamics transport = create();
        transport.setState(TapeTransportState.PLAYING);
        transport.reset();
        assertEquals("a renderer rebuilt mid-tape is already running", 1f, transport.speed(), 1e-6);
        assertEquals(1f, transport.headContact(), 1e-6);
        assertEquals("and nothing is owed to the delay line", 0f, transport.slipSamples(), 1e-6);
        assertFalse(transport.isSilent());
    }

    /** PLAY from stop: the tape has to reach speed, and it takes a third of a second to do it. */
    @Test
    public void playWindsUpRatherThanSnappingIntoTune() {
        TapeTransportDynamics transport = stoppedThen(TapeTransportState.STARTING);

        float afterTenMs = advanceFor(transport, 0.010f);
        assertTrue("10 ms in, the tape is barely moving: " + afterTenMs, afterTenMs < 0.10f);

        float afterOneTau = advanceFor(transport, TapeTransportDynamics.SPIN_UP_SECONDS - 0.010f);
        assertEquals("one time constant is about 63% of the way there",
                0.63f, afterOneTau, 0.06f);

        // Seven time constants, because a first-order settle is still 1% short at five.
        float settled = advanceFor(transport, TapeTransportDynamics.SPIN_UP_SECONDS * 7f);
        assertEquals("and it does arrive", 1f, settled, 0.01f);
        assertTrue("having fallen behind on the way", transport.slipSamples() > 1_000f);
    }

    /**
     * PAUSE: the pinch roller lets go but the head stays down.
     *
     * <p>So the tape coasts and the programme goes with it — a pitch drop, not a mute. That is the
     * sound of pause on a machine like this and it is what distinguishes it from stop.</p>
     */
    @Test
    public void pauseCoastsToAHaltWithTheHeadStillOnTheTape() {
        TapeTransportDynamics transport = playing();
        transport.setState(TapeTransportState.PAUSED);

        float midway = advanceFor(transport, TapeTransportDynamics.COAST_DOWN_SECONDS);
        assertTrue("the tape is still moving one time constant in: " + midway,
                midway > 0.2f && midway < 0.55f);
        assertEquals("and the head has not moved", 1f, transport.headContact(), 1e-3);

        advanceFor(transport, TapeTransportDynamics.COAST_DOWN_SECONDS * 12f);
        assertEquals("it does stop", 0f, transport.speed(), 1e-6);
        assertEquals("with the head still down", 1f, transport.headContact(), 1e-3);
    }

    /** STOP retracts the head, so silence arrives before the tape has finished moving. */
    @Test
    public void stopSilencesTheHeadBeforeTheTapeHasStopped() {
        TapeTransportDynamics transport = playing();
        transport.setState(TapeTransportState.STOPPED);

        advanceFor(transport, TapeTransportDynamics.HEAD_RETRACT_SECONDS * 3f);
        assertTrue("the head is essentially clear: " + transport.headContact(),
                transport.headContact() < 0.06f);
        assertTrue("while the tape is still turning: " + transport.speed(),
                transport.speed() > 0.05f);

        advanceFor(transport, TapeTransportDynamics.COAST_DOWN_SECONDS * 12f);
        assertTrue("both the head and the tape have to have arrived",
                transport.isSilent());
    }

    /** Winding lifts the head, which is why fast-forward is silent and not a chipmunk. */
    @Test
    public void windingLiftsTheHeadClearInBothDirections() {
        for (TapeTransportState winding : new TapeTransportState[]{
                TapeTransportState.FAST_FORWARD, TapeTransportState.REWIND}) {
            TapeTransportDynamics transport = playing();
            transport.setState(winding);
            advanceFor(transport, 0.4f);
            assertEquals(winding + " must lift the head", 0f, transport.headContact(), 1e-3);
            assertTrue(winding + " still spins the tape", transport.speed() > 0.9f);
            assertFalse("the transport is running even though it is silent",
                    transport.isSilent());
        }
    }

    /**
     * The three time constants have to stay in their real order.
     *
     * <p>Getting going is slow, stopping is quick, and the head is quicker still. Collapsing them
     * is the single change that would most obviously turn this back into a switch.</p>
     */
    @Test
    public void gettingGoingTakesLongerThanStopping() {
        TapeTransportDynamics up = stoppedThen(TapeTransportState.STARTING);
        int upFrames = framesUntil(up, true);

        TapeTransportDynamics down = playing();
        down.setState(TapeTransportState.PAUSED);
        int downFrames = framesUntil(down, false);

        assertTrue("spin-up " + upFrames + " frames vs coast-down " + downFrames,
                upFrames > downFrames * 2);
        assertTrue(TapeTransportDynamics.SPIN_UP_SECONDS
                > TapeTransportDynamics.COAST_DOWN_SECONDS * 2f);
        assertTrue(TapeTransportDynamics.COAST_DOWN_SECONDS
                > TapeTransportDynamics.HEAD_RETRACT_SECONDS);
    }

    /**
     * Slip is what turns a speed ramp into a pitch glide, and it must stay inside the buffer.
     *
     * <p>Physically the tape stays behind after every start. Carrying that for an entire album
     * would need an unbounded delay line, so it is returned once the transport is steady — and the
     * clamp is what guarantees the renderer's buffer is never overrun even if it is not.</p>
     */
    @Test
    public void slipStaysInsideTheBufferAndIsReturnedWhenSteady() {
        float capacity = RATE * 0.5f;
        TapeTransportDynamics transport = new TapeTransportDynamics(RATE, STRIDE, capacity);
        transport.setState(TapeTransportState.STOPPED);
        transport.reset();

        // Twenty starts and stops back to back, which no player would do but the buffer must take.
        for (int cycle = 0; cycle < 20; cycle++) {
            transport.setState(TapeTransportState.STARTING);
            advanceFor(transport, 0.5f);
            transport.setState(TapeTransportState.STOPPED);
            advanceFor(transport, 0.3f);
            assertTrue("slip ran past the delay line at cycle " + cycle,
                    transport.slipSamples() <= capacity);
        }

        transport.setState(TapeTransportState.PLAYING);
        advanceFor(transport, 1.0f);
        float afterSettling = transport.slipSamples();
        advanceFor(transport, 20f);
        assertTrue("steady play must give the slip back: " + afterSettling + " -> "
                        + transport.slipSamples(),
                transport.slipSamples() < afterSettling * 0.35f);
    }

    /** Every key press has to produce something for the chassis to conduct. */
    @Test
    public void everyTransitionRaisesAThumpThatThenDecays() {
        TapeTransportDynamics transport = playing();
        assertEquals(0f, transport.transitionEnergy(), 1e-3);

        transport.setState(TapeTransportState.STOPPED);
        transport.advance(STRIDE);
        assertTrue("the key press itself: " + transport.transitionEnergy(),
                transport.transitionEnergy() > 0.99f);

        advanceFor(transport, 0.5f);
        assertEquals("and it does not ring for ever", 0f, transport.transitionEnergy(), 1e-3);
    }

    // ---- helpers

    private static TapeTransportDynamics create() {
        return new TapeTransportDynamics(RATE, STRIDE, RATE * 0.5f);
    }

    private static TapeTransportDynamics playing() {
        TapeTransportDynamics transport = create();
        transport.setState(TapeTransportState.PLAYING);
        transport.reset();
        return transport;
    }

    private static TapeTransportDynamics stoppedThen(TapeTransportState next) {
        TapeTransportDynamics transport = create();
        transport.setState(TapeTransportState.STOPPED);
        transport.reset();
        transport.setState(next);
        return transport;
    }

    /** Runs the mechanism for a wall-clock interval and returns the speed it reached. */
    private static float advanceFor(TapeTransportDynamics transport, float seconds) {
        int strides = Math.max(1, (int) (seconds * RATE / STRIDE));
        for (int step = 0; step < strides; step++) {
            transport.advance(STRIDE);
        }
        return transport.speed();
    }

    private static int framesUntil(TapeTransportDynamics transport, boolean rising) {
        for (int stride = 0; stride < RATE * 5 / STRIDE; stride++) {
            transport.advance(STRIDE);
            float speed = transport.speed();
            if (rising ? speed > 0.63f : speed < 0.37f) {
                return stride * STRIDE;
            }
        }
        return Integer.MAX_VALUE;
    }
}
