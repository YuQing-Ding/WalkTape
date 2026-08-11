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
            2.12f,
            0.46f,
            13_600f,
            -54.8f,
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
            1.68f,
            0.31f,
            17_200f,
            -59.5f,
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
            1.43f,
            0.20f,
            19_400f,
            -58.8f,
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

    // Renderer calibration. These are deliberately not presented as published measurements.
    final float recordTrebleGainDb;
    final float magneticDrive;
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
        this.maximumDynamicLoss = maximumDynamicLoss;
        this.coatingBandwidthHz = coatingBandwidthHz;
        this.renderedHissRmsDb = renderedHissRmsDb;
        this.modulationNoiseDepth = modulationNoiseDepth;
        this.coatingWanderDepth = coatingWanderDepth;
    }

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
