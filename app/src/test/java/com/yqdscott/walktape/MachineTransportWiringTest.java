package com.yqdscott.walktape;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * That the two component-modelled machines actually obey their transports.
 *
 * <p>{@code TapeTransportDynamicsTest} proves the mechanism is right; this proves it is plugged
 * in. They are separate on purpose — a renderer that constructed the model and then ignored it
 * would pass the first file completely.</p>
 */
public class MachineTransportWiringTest {
    private static final int RATE = 48_000;
    private static final double TWO_PI = Math.PI * 2.0;

    @Test
    public void bothMachinesGoQuietWhenTheHeadLiftsToWind() {
        for (String machine : new String[]{"jx707", "tpsl2"}) {
            TapeMachineDsp renderer = create(machine);
            renderer.setTransportState(TapeTransportState.PLAYING);
            renderer.reset();
            double playing = renderThenRms(renderer, 0.30f);

            renderer.setTransportState(TapeTransportState.FAST_FORWARD);
            double winding = renderThenRms(renderer, 0.30f);

            assertTrue(machine + " must produce programme while playing: " + playing,
                    playing > 0.02);
            assertTrue(machine + " must lift the head to wind, leaving only mechanism: "
                            + winding + " against " + playing,
                    winding < playing * 0.15);
        }
    }

    @Test
    public void bothMachinesCoastRatherThanCutWhenPaused() {
        for (String machine : new String[]{"jx707", "tpsl2"}) {
            TapeMachineDsp renderer = create(machine);
            renderer.setTransportState(TapeTransportState.PLAYING);
            renderer.reset();
            renderThenRms(renderer, 0.20f);

            renderer.setTransportState(TapeTransportState.PAUSED);
            // The first 40 ms of a 110 ms coast: the tape is slowing, not muted.
            double duringCoast = renderThenRms(renderer, 0.040f);
            double afterCoast = renderThenRms(renderer, 0.80f);

            assertTrue(machine + " must still be sounding 40 ms into the coast: " + duringCoast,
                    duringCoast > 0.01);
            assertTrue(machine + " must have stopped once the coast is over: " + afterCoast,
                    afterCoast < duringCoast * 0.25);
        }
    }

    /**
     * Starting from a standstill has to move the programme in time, not just in level.
     *
     * <p>A tape running slow delivers less programme than real time asks for, so the renderer
     * falls behind. Measuring that lag is how a genuine pitch glide is told apart from a fade
     * dressed up as one.</p>
     */
    @Test
    public void startingFromStopPutsTheProgrammeBehindRealTime() {
        for (String machine : new String[]{"jx707", "tpsl2"}) {
            TapeMachineDsp settled = create(machine);
            settled.setTransportState(TapeTransportState.PLAYING);
            settled.reset();

            TapeMachineDsp starting = create(machine);
            starting.setTransportState(TapeTransportState.STOPPED);
            starting.reset();
            starting.setTransportState(TapeTransportState.STARTING);

            // Half a second of a 1 kHz tone through each, then compare how much programme energy
            // came out. The machine winding up delivers measurably less of it.
            double settledEnergy = renderThenRms(settled, 0.50f);
            double startingEnergy = renderThenRms(starting, 0.50f);
            assertTrue(machine + " winding up must deliver less than one already at speed: "
                            + startingEnergy + " against " + settledEnergy,
                    startingEnergy < settledEnergy * 0.9);
        }
    }

    private static TapeMachineDsp create(String machine) {
        if ("jx707".equals(machine)) {
            return new AiwaHsJx707Dsp(RATE, 11L, true, false, false);
        }
        return new TpsL2Dsp(RATE, 11L, true, false, false);
    }

    /** Renders a 1 kHz tone for the given time and returns the RMS of the last third of it. */
    private static double renderThenRms(TapeMachineDsp renderer, float seconds) {
        int frames = Math.max(256, (int) (seconds * RATE));
        float[] audio = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float value = (float) (Math.sin(TWO_PI * 1_000.0 * frame / RATE) * 0.25);
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value;
        }
        renderer.process(audio, frames);
        double sum = 0.0;
        int counted = 0;
        for (int frame = frames * 2 / 3; frame < frames; frame++) {
            sum += audio[frame * 2] * (double) audio[frame * 2];
            counted++;
        }
        return Math.sqrt(sum / Math.max(1, counted));
    }
}
