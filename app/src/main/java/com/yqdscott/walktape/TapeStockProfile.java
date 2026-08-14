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
    public static final String TDK_SA_1988 = "tdk_sa_1988";
    public static final String TDK_MA_X_1990 = "tdk_ma_x_1990";

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
            5.7f,
            4.4414f,
            3.3090f,
            0.3556f,
            13_600f,
            -66.48f,
            0.62f,
            0.0045f
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
            Arrays.asList(CHF, SA, MA_X));

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
        if (TDK_SA_1988.equals(id)) {
            return SA;
        }
        if (TDK_MA_X_1990.equals(id)) {
            return MA_X;
        }
        return CHF;
    }

    public boolean isHighPosition() {
        return iecType != 1;
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
