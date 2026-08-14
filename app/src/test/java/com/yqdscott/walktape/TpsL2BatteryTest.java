package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Acceptance gates for the two-cell alkaline supply.
 *
 * <p>The anchors are the published LR6 ratings — nominally 2.1 Ah down to 0.9 V per cell at a
 * 100 mA load, which is exactly the PLAY transport current in Sony's service manual — plus the
 * fresh-pair voltage the rest of the model is already calibrated against. The shape between those
 * endpoints is a declared engineering prior, so these tests check behaviour rather than pretending
 * to verify unpublished numbers.</p>
 */
public class TpsL2BatteryTest {

    private static final int SAMPLE_RATE = 48_000;

    @Test
    public void ratedServiceLifeMatchesThePublishedHundredMilliampCapacity() {
        // 2.1 Ah at the 100 mA PLAY current is about twenty-one hours of tape.
        assertEquals(21.0, TpsL2ElectromechanicalModel.ratedPlayHours(), 0.5);
    }

    @Test
    public void curveRunsFromAFreshPairToNinetenthsOfAVoltPerCell() {
        assertEquals(3.02, TpsL2ElectromechanicalModel.batteryOpenCircuitVolts(0f), 0.005);
        assertEquals(1.80, TpsL2ElectromechanicalModel.batteryOpenCircuitVolts(1f), 0.005);
        // Alkaline cells slope rather than hold a plateau, so half-used sits well below halfway.
        float halfway = TpsL2ElectromechanicalModel.batteryOpenCircuitVolts(0.5f);
        assertEquals(2.40, halfway, 0.05);

        float previous = Float.MAX_VALUE;
        for (int step = 0; step <= 20; step++) {
            float volts = TpsL2ElectromechanicalModel.batteryOpenCircuitVolts(step / 20f);
            assertTrue("Terminal voltage must fall monotonically", volts <= previous);
            previous = volts;
        }
    }

    @Test
    public void seriesResistanceClimbsAsTheCellsAreUsedUp() {
        float fresh = TpsL2ElectromechanicalModel.batterySeriesOhms(0f);
        float halfway = TpsL2ElectromechanicalModel.batterySeriesOhms(0.5f);
        float exhausted = TpsL2ElectromechanicalModel.batterySeriesOhms(1f);
        assertEquals(TpsL2Schematic.value("P-BATT-R"), fresh, 1e-6);
        assertEquals(TpsL2Schematic.value("P-BATT-R-END"), exhausted, 1e-6);
        assertTrue("Resistance growth should stay gentle until the cells are well used",
                halfway < fresh + (exhausted - fresh) * 0.25f);
    }

    @Test
    public void freshCellsLeaveTheCalibratedRailAndOutputUntouched() {
        TpsL2ElectromechanicalModel model =
                new TpsL2ElectromechanicalModel(SAMPLE_RATE, 0x424154544552L);
        model.setTransportState(TapeTransportState.PLAYING);
        model.setBatteryDepthOfDischarge(0f);
        model.reset();
        for (int step = 0; step < SAMPLE_RATE; step++) {
            model.advanceControl(0f, 0f, 0f);
        }
        assertEquals(TpsL2ElectromechanicalModel.POWER_AMP_FILTERED_RAIL_VOLTS,
                model.filteredRailVolts(), 0.01f);
        assertEquals(1.0, model.outputHeadroomScale(), 0.03);
    }

    @Test
    public void exhaustedCellsCostRailVoltageAndOutputHeadroom() {
        TpsL2ElectromechanicalModel model =
                new TpsL2ElectromechanicalModel(SAMPLE_RATE, 0x424154544552L);
        model.setTransportState(TapeTransportState.PLAYING);
        model.setBatteryDepthOfDischarge(0.95f);
        model.reset();
        for (int step = 0; step < SAMPLE_RATE; step++) {
            model.advanceControl(0f, 0f, 0f);
        }
        assertTrue("A flat pair cannot hold the 2.7 V playback rail",
                model.filteredRailVolts()
                        < TpsL2ElectromechanicalModel.POWER_AMP_FILTERED_RAIL_VOLTS - 0.3f);
        assertTrue("Lost rail must cost output headroom",
                model.outputHeadroomScale() < 0.95f);
        assertTrue("The machine should run slow, never fast",
                model.residualSpeedError() > 0f);
    }

    @Test
    public void batteryStateSurvivesAResetBecauseItIsASessionProperty() {
        TpsL2ElectromechanicalModel model =
                new TpsL2ElectromechanicalModel(SAMPLE_RATE, 0x424154544552L);
        model.setBatteryDepthOfDischarge(0.8f);
        model.reset();
        float railAfterReset = model.filteredRailVolts();
        model.reset();
        assertEquals(railAfterReset, model.filteredRailVolts(), 1e-6f);
    }
}
