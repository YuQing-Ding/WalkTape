package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Pins how the three EF generations were placed, because they were not measured.
 *
 * <p>CHF, SA and MA-X carry figures somebody put on a deck. The EF line does not: no laboratory
 * published one, and the survey that supplied CHF's numbers — a Nakamichi BX-300E against a Maxell
 * UR reference — has no EF row and its index confirms none. So every EF figure here is a
 * <em>placement</em>: an interpolation inside a bracket whose two ends were measured on that same
 * deck against that same reference.</p>
 *
 * <p>That is a weaker class of evidence than the other stocks carry and it is labelled as such
 * rather than blended in. What these tests do is stop the placement drifting into invention: the
 * bracket has to keep holding, the generations have to keep their order, and Super EF's figures
 * have to keep falling out of Sony's own printed comparison instead of being chosen.</p>
 *
 * <p>The one thing that is not a judgement call is the dynamic-range identity, and
 * {@link #theDynamicRangeIdentityReproducesTheLabsOwnColumn} is the load-bearing test here: MOL
 * minus A-weighted bias noise reproduces the published dynamic-range column exactly for CHF, whose
 * numbers nobody here chose. That is what makes positioning EF against that column meaningful
 * rather than circular.</p>
 */
public class SonyEfStockTest {

    /**
     * The measured bracket, from the survey CHF's own figures came from.
     *
     * <p>Below: CHF 1978, the budget ferric EF took over from. Above: HF 1985, the tier EF sat
     * under in every market that got both.</p>
     */
    private static final float CHF_DYNAMIC_RANGE_DB = 52.8f;
    private static final float HF_1985_DYNAMIC_RANGE_DB = 54.5f;
    private static final float HF_1985_MOL_DB = 4.5f;
    private static final float HF_1990_DYNAMIC_RANGE_DB = 57.1f;

    /** Sony's own printed comparison of Super EF against EF, from the 1990-92 packaging. */
    private static final float SONY_PRINTED_NOISE_IMPROVEMENT_DB = 0.5f;
    private static final float SONY_PRINTED_MID_RANGE_GAIN_DB = 0.3f;
    private static final float SONY_PRINTED_HIGH_GAIN_DB = 0.5f;

    @Test
    public void theDynamicRangeIdentityReproducesTheLabsOwnColumn() {
        // Nobody here chose CHF's figures, so this is a real check on the convention rather than
        // an assertion that two of our own numbers subtract correctly.
        assertEquals("MOL minus A-weighted noise must be the published dynamic range",
                CHF_DYNAMIC_RANGE_DB, TapeStockProfile.sonyChf1978().dynamicRangeDb(), 0.05f);
    }

    @Test
    public void allThreeGenerationsAreNormalPositionFerric() {
        for (TapeStockProfile ef : TapeStockProfile.seriesMembers(
                TapeStockProfile.SERIES_SONY_EF)) {
            assertEquals(ef.model + " is a Type I tape", 1, ef.iecType);
            assertEquals(ef.model + " takes the 120 us replay curve", 120, ef.replayEqMicroseconds);
            assertEquals("SONY", ef.manufacturer);
            assertTrue(ef.model + " must not claim to be a high-position tape", !ef.isHighPosition());
        }
    }

    /**
     * EF is placed inside the bracket, not outside it.
     *
     * <p>Above CHF because it replaced CHF in the budget slot; below HF because that is where every
     * account of the line puts it. If a later edit pushes EF past either end, the placement has
     * stopped being an interpolation and this fails.</p>
     */
    @Test
    public void efSitsInsideTheMeasuredBracketItWasPlacedIn() {
        TapeStockProfile ef = TapeStockProfile.sonyEf1985();
        assertTrue("EF must beat the CHF it replaced: " + ef.dynamicRangeDb(),
                ef.dynamicRangeDb() > CHF_DYNAMIC_RANGE_DB);
        assertTrue("EF must not reach the HF tier above it: " + ef.dynamicRangeDb(),
                ef.dynamicRangeDb() < HF_1985_DYNAMIC_RANGE_DB);
        assertTrue("nor may its maximum output level: " + ef.mol315Db,
                ef.mol315Db > TapeStockProfile.sonyChf1978().mol315Db
                        && ef.mol315Db < HF_1985_MOL_DB);
        assertTrue("a budget ferric distorts more than HF and less than CHF: "
                        + ef.thdAtReferencePercent,
                ef.thdAtReferencePercent < TapeStockProfile.sonyChf1978().thdAtReferencePercent
                        && ef.thdAtReferencePercent > 0.60f);
    }

    /**
     * Super EF's headline figures are Sony's arithmetic, not a choice.
     *
     * <p>The packaging reads "Bias noise reduced by 0.5dB and the Dynamic Range expanded, compared
     * with conventional SONY EF", with the reported detail being +0.3 dB at mid and +0.5 dB at high
     * frequencies. Applying those to EF fixes three of Super EF's four headline numbers, and the
     * fourth follows: a 0.5 dB quieter floor with only 0.3 dB more range means maximum output level
     * went <em>down</em> 0.2 dB. That trade is carried rather than smoothed away, because it is
     * what Sony's own numbers say.</p>
     */
    @Test
    public void superEfFollowsSonysOwnPrintedComparisonWithEf() {
        TapeStockProfile ef = TapeStockProfile.sonyEf1985();
        TapeStockProfile superEf = TapeStockProfile.sonySuperEf1990();

        assertEquals("bias noise reduced by 0.5 dB, exactly as printed",
                ef.biasNoiseDb - SONY_PRINTED_NOISE_IMPROVEMENT_DB, superEf.biasNoiseDb, 0.05f);
        assertEquals("dynamic range expanded by the reported mid-frequency figure",
                ef.dynamicRangeDb() + SONY_PRINTED_MID_RANGE_GAIN_DB,
                superEf.dynamicRangeDb(), 0.05f);
        assertEquals("and the high-frequency figure lands on the saturation output level",
                ef.sol10kDb + SONY_PRINTED_HIGH_GAIN_DB, superEf.sol10kDb, 0.05f);
        assertEquals("which leaves maximum output level 0.2 dB lower, as the arithmetic requires",
                ef.mol315Db - 0.2f, superEf.mol315Db, 0.05f);
        assertTrue("the quieter coating is the point of the revision",
                superEf.biasNoiseDb < ef.biasNoiseDb);
    }

    @Test
    public void efXIsTheTopOfTheLineWithoutOvertakingHf() {
        TapeStockProfile superEf = TapeStockProfile.sonySuperEf1990();
        TapeStockProfile efX = TapeStockProfile.sonyEfX1995();
        assertTrue("EF-X must improve on Super EF: " + efX.dynamicRangeDb(),
                efX.dynamicRangeDb() > superEf.dynamicRangeDb());
        assertTrue("but must not reach the contemporary HF: " + efX.dynamicRangeDb(),
                efX.dynamicRangeDb() < HF_1990_DYNAMIC_RANGE_DB);
        assertTrue("and it must be the cleanest of the three",
                efX.thdAtReferencePercent < superEf.thdAtReferencePercent);
    }

    /** The line improves generation by generation, which is the one thing every account agrees on. */
    @Test
    public void theLineImprovesInOrder() {
        List<TapeStockProfile> line = TapeStockProfile.seriesMembers(
                TapeStockProfile.SERIES_SONY_EF);
        assertEquals(3, line.size());
        for (int index = 1; index < line.size(); index++) {
            TapeStockProfile older = line.get(index - 1);
            TapeStockProfile newer = line.get(index);
            assertTrue(newer.model + " must post-date " + older.model,
                    newer.year > older.year);
            assertTrue(newer.model + " must out-range " + older.model,
                    newer.dynamicRangeDb() > older.dynamicRangeDb());
            assertTrue(newer.model + " must be quieter than " + older.model,
                    newer.biasNoiseDb < older.biasNoiseDb);
            assertTrue(newer.model + " must hold high frequencies better than " + older.model,
                    newer.sol10kDb > older.sol10kDb);
        }
    }

    /**
     * The picker's two levels come from the data, so a new family needs no view changes.
     */
    @Test
    public void theEfGenerationsAreOneCardAtTheTopLevel() {
        List<TapeStockProfile> top = TapeStockProfile.topLevelProfiles();
        assertEquals("CHF, the EF family, SA and MA-X", 4, top.size());
        assertEquals(TapeStockProfile.SONY_CHF_1978, top.get(0).id);
        assertEquals("the family is represented by its first generation",
                TapeStockProfile.SONY_EF_1985, top.get(1).id);
        assertEquals(TapeStockProfile.TDK_SA_1988, top.get(2).id);
        assertEquals(TapeStockProfile.TDK_MA_X_1990, top.get(3).id);

        assertNotNull(top.get(1).seriesId);
        assertNull("an ungrouped stock must not claim a family", top.get(0).seriesId);
        assertNull(top.get(2).seriesId);

        List<TapeStockProfile> members = TapeStockProfile.seriesMembers(
                TapeStockProfile.SERIES_SONY_EF);
        assertEquals(3, members.size());
        assertEquals(TapeStockProfile.SONY_EF_1985, members.get(0).id);
        assertEquals(TapeStockProfile.SONY_SUPER_EF_1990, members.get(1).id);
        assertEquals(TapeStockProfile.SONY_EF_X_1995, members.get(2).id);
        assertEquals("EF", members.get(0).seriesVariant);

        assertTrue(TapeStockProfile.seriesMembers(null).isEmpty());
        assertTrue(TapeStockProfile.seriesMembers("not_a_family").isEmpty());
    }

    /** Every generation has to be reachable by id, or a saved preference loses the tape. */
    @Test
    public void eachGenerationRoundTripsThroughItsIdentifier() {
        for (TapeStockProfile stock : TapeStockProfile.availableProfiles()) {
            assertEquals(stock.id, TapeStockProfile.forId(stock.id).id);
        }
        assertEquals("an unknown id must fall back rather than throw",
                TapeStockProfile.SONY_CHF_1978, TapeStockProfile.forId("gone").id);
        assertEquals(TapeStockProfile.SONY_CHF_1978, TapeStockProfile.forId(null).id);
    }
}
