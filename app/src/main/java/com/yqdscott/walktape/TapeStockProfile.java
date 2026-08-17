package com.yqdscott.walktape;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One physical compact-cassette formulation, independent of the playback machine.
 *
 * <p>The public figures are reference data printed by the manufacturer or measured from the
 * named stock.  Renderer-only constants intentionally live beside those references so a future
 * sweep can replace the approximation without touching the machine calibration.</p>
 */
public final class TapeStockProfile {
    public static final String SONY_CHF_1978 = "sony_chf_1978";
    public static final String SONY_EF_1985 = "sony_ef_1985";
    public static final String SONY_SUPER_EF_1990 = "sony_super_ef_1990";
    public static final String SONY_EF_X_1995 = "sony_ef_x_1995";
    public static final String TDK_SA_1988 = "tdk_sa_1988";
    public static final String TDK_MA_X_1990 = "tdk_ma_x_1990";

    /** Groups the three EF generations under one card in the picker. */
    public static final String SERIES_SONY_EF = "sony_ef";

    private static final TapeStockProfile CHF = new TapeStockProfile(
            SONY_CHF_1978,
            "SONY",
            "CHF",
            1978,
            1,
            "FERRIC",
            "NORMAL POSITION",
            120,
            2.1f,
            -1.4f,
            -50.7f,
            1.50f,
            -2.0f,
            -0.8f,
            "EARLY FERRIC / DENSE GRAIN",
            0xffe95c2c,
            null,
            null,
            5.7f,
            4.4414f,
            3.3090f,
            0.3556f,
            13_600f,
            -66.48f,
            0.62f,
            0.0045f
    );

    // ---- The EF line. Sony never published laboratory figures for it and no independent lab
    // ---- measured it either, so unlike CHF these three are *placed* rather than measured: each
    // ---- figure is an interpolation inside a bracket whose ends were measured on the same deck
    // ---- against the same reference tape. Super EF is the exception — three of its four
    // ---- headline figures fall out of Sony's own printed comparison against EF. See
    // ---- docs/TAPE_MEDIA_MODEL.md for the bracket and the arithmetic.

    private static final TapeStockProfile EF = new TapeStockProfile(
            SONY_EF_1985,
            "SONY",
            "EF",
            1985,
            1,
            "FERRIC OXIDE",
            "NORMAL POSITION",
            120,
            3.2f,
            -1.6f,
            -50.2f,
            1.05f,
            -1.5f,
            -0.7f,
            "BUDGET FERRIC / SOFT TOP END",
            0xff6f9fa8,
            SERIES_SONY_EF,
            "EF",
            5.7f,
            3.9641f,
            3.3500f,
            0.4949f,
            13_900f,
            -65.98f,
            0.58f,
            0.0042f
    );

    private static final TapeStockProfile SUPER_EF = new TapeStockProfile(
            SONY_SUPER_EF_1990,
            "SONY",
            "SUPER EF",
            1990,
            1,
            "FERRIC OXIDE",
            "NORMAL POSITION",
            120,
            3.0f,
            -1.1f,
            -50.7f,
            0.85f,
            -1.0f,
            -0.5f,
            "QUIETER COATING / WIDER RANGE",
            0xff5a86c4,
            SERIES_SONY_EF,
            "SUPER EF",
            5.7f,
            4.5278f,
            4.3500f,
            0.3227f,
            14_400f,
            -66.48f,
            0.52f,
            0.0037f
    );

    private static final TapeStockProfile EF_X = new TapeStockProfile(
            SONY_EF_X_1995,
            "SONY",
            "EF-X",
            1995,
            1,
            "FERRIC OXIDE",
            "NORMAL POSITION",
            120,
            4.0f,
            -0.9f,
            -51.8f,
            0.62f,
            -0.5f,
            -0.4f,
            "TOP OF THE EF LINE / NEAR HF",
            0xffc46a4a,
            SERIES_SONY_EF,
            "EF-X",
            5.7f,
            3.9108f,
            4.0000f,
            0.4734f,
            15_100f,
            -67.58f,
            0.45f,
            0.0031f
    );

    private static final TapeStockProfile SA = new TapeStockProfile(
            TDK_SA_1988,
            "TDK",
            "SA",
            1988,
            2,
            "SUPER AVILYN",
            "HIGH POSITION",
            70,
            4.5f,
            -3.6f,
            -60.5f,
            0.62f,
            0.6f,
            1.6f,
            "COBALT FERRIC / LOW NOISE",
            0xffd7a23a,
            null,
            null,
            4.7f,
            3.6105f,
            3.4512f,
            0.8306f,
            17_200f,
            -76.17f,
            0.38f,
            0.0028f
    );

    private static final TapeStockProfile MA_X = new TapeStockProfile(
            TDK_MA_X_1990,
            "TDK",
            "MA-X",
            1990,
            4,
            "FINAVINX METAL",
            "METAL POSITION",
            70,
            6.0f,
            0.5f,
            -58.0f,
            0.42f,
            0.5f,
            1.2f,
            "PURE METAL / HIGH HEADROOM",
            0xffb8c7c9,
            null,
            null,
            4.45f,
            2.9190f,
            3.1871f,
            0.7518f,
            19_400f,
            -73.48f,
            0.28f,
            0.0019f
    );

    private static final List<TapeStockProfile> AVAILABLE = Collections.unmodifiableList(
            Arrays.asList(CHF, EF, SUPER_EF, EF_X, SA, MA_X));

    public final String id;
    public final String manufacturer;
    public final String model;
    public final int year;
    public final int iecType;
    public final String formulation;
    public final String position;
    public final int replayEqMicroseconds;
    public final float mol315Db;
    public final float sol10kDb;
    public final float biasNoiseDb;
    public final float thdAtReferencePercent;
    public final float relativeBiasDb;
    public final float sensitivityDb;
    public final String character;
    public final int accentColor;
    /** Non-null when this stock is one generation of a family shown as a single picker card. */
    public final String seriesId;
    /** Short name for this generation within its family, e.g. "SUPER EF". */
    public final String seriesVariant;

    // Renderer calibration. These are solved so the rendered stock reproduces the published
    // measurements above; TapeStockCalibrationTest is the gate that keeps them honest. They are
    // still not themselves measurements, which is why they stay separate from the public fields.
    final float recordTrebleGainDb;
    final float magneticDrive;
    final float magneticKnee;
    final float maximumDynamicLoss;
    final float coatingBandwidthHz;
    final float renderedHissRmsDb;
    final float modulationNoiseDepth;
    final float coatingWanderDepth;

    private TapeStockProfile(String id,
                             String manufacturer,
                             String model,
                             int year,
                             int iecType,
                             String formulation,
                             String position,
                             int replayEqMicroseconds,
                             float mol315Db,
                             float sol10kDb,
                             float biasNoiseDb,
                             float thdAtReferencePercent,
                             float relativeBiasDb,
                             float sensitivityDb,
                             String character,
                             int accentColor,
                             String seriesId,
                             String seriesVariant,
                             float recordTrebleGainDb,
                             float magneticDrive,
                             float magneticKnee,
                             float maximumDynamicLoss,
                             float coatingBandwidthHz,
                             float renderedHissRmsDb,
                             float modulationNoiseDepth,
                             float coatingWanderDepth) {
        this.id = id;
        this.manufacturer = manufacturer;
        this.model = model;
        this.year = year;
        this.iecType = iecType;
        this.formulation = formulation;
        this.position = position;
        this.replayEqMicroseconds = replayEqMicroseconds;
        this.mol315Db = mol315Db;
        this.sol10kDb = sol10kDb;
        this.biasNoiseDb = biasNoiseDb;
        this.thdAtReferencePercent = thdAtReferencePercent;
        this.relativeBiasDb = relativeBiasDb;
        this.sensitivityDb = sensitivityDb;
        this.character = character;
        this.accentColor = accentColor;
        this.seriesId = seriesId;
        this.seriesVariant = seriesVariant;
        this.recordTrebleGainDb = recordTrebleGainDb;
        this.magneticDrive = magneticDrive;
        this.magneticKnee = magneticKnee;
        this.maximumDynamicLoss = maximumDynamicLoss;
        this.coatingBandwidthHz = coatingBandwidthHz;
        this.renderedHissRmsDb = renderedHissRmsDb;
        this.modulationNoiseDepth = modulationNoiseDepth;
        this.coatingWanderDepth = coatingWanderDepth;
    }

    /**
     * Same published stock with different renderer constants, used to solve and to verify them.
     *
     * <p>The public measurements are carried through untouched, so a calibration run can only
     * change how the stock is rendered and never what it claims to be.</p>
     */
    static TapeStockProfile withRendererConstants(TapeStockProfile base,
                                                  float recordTrebleGainDb,
                                                  float magneticDrive,
                                                  float magneticKnee,
                                                  float maximumDynamicLoss,
                                                  float renderedHissRmsDb) {
        return new TapeStockProfile(base.id, base.manufacturer, base.model, base.year,
                base.iecType, base.formulation, base.position, base.replayEqMicroseconds,
                base.mol315Db, base.sol10kDb, base.biasNoiseDb, base.thdAtReferencePercent,
                base.relativeBiasDb, base.sensitivityDb, base.character, base.accentColor,
                base.seriesId, base.seriesVariant,
                recordTrebleGainDb, magneticDrive, magneticKnee, maximumDynamicLoss,
                base.coatingBandwidthHz, renderedHissRmsDb, base.modulationNoiseDepth,
                base.coatingWanderDepth);
    }

    /**
     * Digital level standing in for the tape industry's reference flux, 200 nWb/m at 315 Hz.
     *
     * <p>Every published figure in this class — maximum output level, saturation output level and
     * bias noise — is quoted relative to it. The level is often called "Dolby level" because Dolby
     * Laboratories defined it as the alignment point for their noise reduction, but it is a
     * property of the tape and is the reference whether or not a machine has noise reduction at
     * all. The TPS-L2 has none; these measurements still apply to the stock it plays.</p>
     */
    static final float REFERENCE_FLUX_LEVEL = 0.12589254f; // -18 dBFS

    public static TapeStockProfile sonyChf1978() {
        return CHF;
    }

    public static TapeStockProfile sonyEf1985() {
        return EF;
    }

    public static TapeStockProfile sonySuperEf1990() {
        return SUPER_EF;
    }

    public static TapeStockProfile sonyEfX1995() {
        return EF_X;
    }

    public static TapeStockProfile tdkSa1988() {
        return SA;
    }

    public static TapeStockProfile tdkMaX1990() {
        return MA_X;
    }

    public static List<TapeStockProfile> availableProfiles() {
        return AVAILABLE;
    }

    public static TapeStockProfile forId(String id) {
        for (TapeStockProfile profile : AVAILABLE) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        return CHF;
    }

    public boolean hasSeries() {
        return seriesId != null;
    }

    /**
     * Every generation of one family, in the order they are offered.
     *
     * <p>Returned for the picker's second level. A stock with no family returns just itself, so a
     * caller does not have to branch on {@link #hasSeries()}.</p>
     */
    public static List<TapeStockProfile> seriesMembers(String seriesId) {
        if (seriesId == null) {
            return Collections.emptyList();
        }
        java.util.ArrayList<TapeStockProfile> members = new java.util.ArrayList<>();
        for (TapeStockProfile profile : AVAILABLE) {
            if (seriesId.equals(profile.seriesId)) {
                members.add(profile);
            }
        }
        return Collections.unmodifiableList(members);
    }

    /**
     * What the picker's top level shows: one entry per family, plus every ungrouped stock.
     *
     * <p>The family is represented by its first generation, which is also what selecting the
     * family card without choosing a generation gives you.</p>
     */
    public static List<TapeStockProfile> topLevelProfiles() {
        java.util.ArrayList<TapeStockProfile> cards = new java.util.ArrayList<>();
        java.util.HashSet<String> seenSeries = new java.util.HashSet<>();
        for (TapeStockProfile profile : AVAILABLE) {
            if (profile.seriesId == null) {
                cards.add(profile);
            } else if (seenSeries.add(profile.seriesId)) {
                cards.add(profile);
            }
        }
        return Collections.unmodifiableList(cards);
    }

    public boolean isHighPosition() {
        return iecType != 1;
    }

    /**
     * Headroom above the noise floor, the way a tape laboratory reports it.
     *
     * <p>Maximum output level minus A-weighted bias noise. Worth knowing that this is not a
     * convention invented here: it reproduces the published dynamic-range column exactly for the
     * stock whose figures were taken from a lab rather than placed, which is what lets the EF
     * generations be positioned against that column at all.</p>
     */
    public float dynamicRangeDb() {
        return mol315Db - biasNoiseDb;
    }

    public String typeLabel() {
        return typeShortLabel() + " / " + position;
    }

    public String typeShortLabel() {
        return "TYPE " + (iecType == 1 ? "I" : iecType == 2 ? "II" : "IV");
    }

    public String compactSpec() {
        return replayEqMicroseconds + " \u00b5s  \u00b7  MOL " + signed(mol315Db)
                + " dB  \u00b7  SOL10K " + signed(sol10kDb) + " dB";
    }

    private static String signed(float value) {
        return (value >= 0f ? "+" : "\u2212")
                + String.format(java.util.Locale.US, "%.1f", Math.abs(value));
    }
}
