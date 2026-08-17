package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Gates the headphone output network against Aiwa's component values.
 *
 * <p>The point of these is as much about what the renderer leaves out as what it puts in: the
 * Zobel and the chip coil are omitted deliberately, and that decision is only defensible while
 * their corners really do sit outside the audio band.</p>
 */
public class AiwaHsJx707OutputStageTest {

    @Test
    public void theCouplingCornerComesFromC86AndTheRatedLoad() {
        AiwaHsJx707OutputStage stage = new AiwaHsJx707OutputStage();
        double coupling = AiwaHsJx707Schematic.part("C86").value;
        double load = AiwaHsJx707Schematic.part("P-HP-LOAD").value;
        assertEquals(1.0 / (2.0 * Math.PI * coupling * load),
                stage.couplingCornerHertz(), 1e-9);
        assertEquals(22.61, stage.couplingCornerHertz(), 0.02);
        assertEquals(7.04e-3, stage.couplingTimeConstantSeconds(), 1e-5);
    }

    @Test
    public void bothChannelsCoupleIdentically() {
        AiwaHsJx707OutputStage right = new AiwaHsJx707OutputStage();
        AiwaHsJx707OutputStage left = new AiwaHsJx707OutputStage("C85", "C83", "R68", "L12");
        assertEquals(right.couplingCornerHertz(), left.couplingCornerHertz(), 1e-9);
    }

    /**
     * The renderer omits the Zobel and the chip coil. That is only honest while they are this far
     * out of band, so the omission is pinned rather than assumed.
     */
    @Test
    public void theOmittedPartsOfTheNetworkAreFarOutsideTheAudioBand() {
        AiwaHsJx707OutputStage stage = new AiwaHsJx707OutputStage();
        assertEquals(153_900.0, stage.zobelCornerHertz(), 500.0);
        assertEquals(1.543e6, stage.chipCoilCornerHertz(), 5e3);
        assertTrue("A Zobel inside the band would have to be modelled",
                stage.zobelCornerHertz() > 100_000.0);
        assertTrue("A chip coil inside the band would have to be modelled",
                stage.chipCoilCornerHertz() > 1_000_000.0);
        // Both are above half of every sample rate the renderer supports, so no aliased image of
        // them lands in the band either.
        assertTrue(stage.zobelCornerHertz() > 96_000 / 2.0);
    }

    /**
     * The amplifier itself, from Toshiba's datasheet rather than Aiwa's drawing.
     *
     * <p>This file used to record the gain as underivable because Aiwa prints no values on the
     * TA7688F's internal network. Toshiba does, so it is derivable after all; the check that it is
     * really the part and not a fitted number is that the internal ratio reproduces Toshiba's own
     * quoted 30.5 dB.</p>
     */
    @Test
    public void theAmplifierGainComesFromToshibasInternalNetwork() {
        assertEquals("Toshiba quotes 30.5 dB for the internal network",
                30.5, AiwaHsJx707OutputStage.internalGainDb(), 0.4);
        // Aiwa's R65 47k parallels the internal 33k, which lands below the 30 dB Toshiba says the
        // part is specified for. Pinned as read, not adjusted to suit the application note.
        assertEquals(26.33, new AiwaHsJx707OutputStage().externalGainDb(), 0.05);
    }

    /**
     * Clipping is expressed as a fraction of the available swing, so it survives the JX707 running
     * this part from two cells rather than Toshiba's 3 V bench supply.
     */
    @Test
    public void clippingIsDerivedFromToshibasOutputPowerRatings() {
        AiwaHsJx707OutputStage stage = new AiwaHsJx707OutputStage();
        assertEquals("10 mW into 32 ohm, where THD is still its floor",
                0.533, stage.linearFractionOfSwing(), 0.005);
        assertEquals("27 mW into 32 ohm, Toshiba's 10% THD point",
                0.876, stage.clipFractionOfSwing(), 0.005);
        assertTrue("The stage must distort before it runs out of rail",
                stage.linearFractionOfSwing() < stage.clipFractionOfSwing());
        assertTrue("and 10% THD must arrive before the rail, not after",
                stage.clipFractionOfSwing() < 1.0);
    }

    @Test
    public void theCouplingHighPassRollsOffTheSubsonicEndOnly() {
        AiwaHsJx707OutputStage stage = new AiwaHsJx707OutputStage();
        assertEquals(-3.57, stage.relativeResponseDb(20.0), 0.05);
        assertEquals(-0.53, stage.relativeResponseDb(63.0), 0.05);
        assertEquals(-3.01, stage.relativeResponseDb(stage.couplingCornerHertz()), 0.02);
        assertTrue("The passband must be untouched",
                Math.abs(stage.relativeResponseDb(1_000.0)) < 0.01);
        for (double hertz = 20.0; hertz < 2_000.0; hertz *= 1.2) {
            assertTrue("A high-pass must rise monotonically",
                    stage.relativeResponseDb(hertz * 1.2) > stage.relativeResponseDb(hertz));
        }
    }
}
