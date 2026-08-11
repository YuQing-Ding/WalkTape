package com.yqdscott.walktape;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A measurement target for a physical tape machine.  This is deliberately
 * separate from DSP implementation: displaying or storing a specification must
 * never be confused with having reproduced it accurately.
 */
public final class TapeMachineProfile {
    public static final String SONY_TPS_L2 = "sony_tps_l2";
    public static final String SONY_WM_F2015 = "sony_wm_f2015";
    public static final String SONY_WM_D6C = "sony_wm_d6c";
    public static final String AIWA_HS_JX707 = "aiwa_hs_jx707";

    private static final TapeMachineProfile TPS_L2_REFERENCE = new TapeMachineProfile(
            SONY_TPS_L2,
            "SONY",
            "TPS-L2",
            1979,
            40,
            12_000,
            0.219f,
            -67f,
            6f,
            "MECHANICAL",
            "DIGITISED ARCHIVE TRACE",
            "40 Hz — 12 kHz",
            "0.219% RMS",
            "NOISE FLOOR",
            "−67 dB TRACE",
            "HIGH",
            "LOW",
            true
    );

    private static final TapeMachineProfile JX707_REFERENCE = new TapeMachineProfile(
            AIWA_HS_JX707,
            "AIWA",
            "HS-JX707",
            1991,
            63,
            12_500,
            0.320f,
            -56f,
            0f,
            "AUTO REVERSE",
            "1992 FACTORY SERVICE REFERENCE",
            "63 Hz — 12.5 kHz",
            "0.320% RMS · <0.45 MAX",
            "SIGNAL / NOISE",
            ">45 dB (DOLBY OFF)",
            "Cr/METAL",
            "NORMAL",
            false
    );

    private static final TapeMachineProfile WM_F2015_REFERENCE = new TapeMachineProfile(
            SONY_WM_F2015,
            "SONY",
            "WM-F2015",
            1990,
            40,
            15_000,
            0.340f,
            -65f,
            0f,
            "DUAL BELT / MANUAL",
            "1991 SONY SERVICE CIRCUIT / SPEED LIMIT",
            "40 Hz — 15 kHz",
            "0.340% RMS MODEL · SPEED ±0.5%",
            "NOISE REDUCTION",
            "NONE · LA4570M",
            "Cr/METAL",
            "NORMAL",
            false
    );

    private static final TapeMachineProfile WM_D6C_REFERENCE = new TapeMachineProfile(
            SONY_WM_D6C,
            "SONY",
            "WM-D6C",
            1984,
            40,
            15_000,
            0.040f,
            -58f,
            0f,
            "QUARTZ-LOCKED DISC DRIVE",
            "1984 SONY SERVICE MANUAL / NAB WRMS",
            "40 Hz — 15 kHz",
            "0.040% WRMS  ·  ±0.14% DIN",
            "SIGNAL / NOISE",
            "58 dB  ·  TYPE II/IV  ·  NR OFF",
            "CrO₂ / METAL",
            "NORMAL",
            false
    );

    private static final List<TapeMachineProfile> AVAILABLE = Collections.unmodifiableList(
            Arrays.asList(TPS_L2_REFERENCE, WM_F2015_REFERENCE, JX707_REFERENCE,
                    WM_D6C_REFERENCE));

    public final String id;
    public final String manufacturer;
    public final String model;
    public final int year;
    public final int lowFrequencyHz;
    public final int highFrequencyHz;
    public final float wowFlutterRmsPercent;
    public final float referenceNoiseFloorDb;
    public final float highTapePresenceBoostDb;
    public final String transport;
    public final String calibrationBasis;
    public final String highTapeFrequencySpec;
    public final String wowFlutterSpec;
    public final String noiseSpecLabel;
    public final String noiseSpecValue;
    public final String highTapeLabel;
    public final String lowTapeLabel;
    public final boolean hotlineSupported;

    private TapeMachineProfile(String id,
                               String manufacturer,
                               String model,
                               int year,
                               int lowFrequencyHz,
                               int highFrequencyHz,
                               float wowFlutterRmsPercent,
                               float referenceNoiseFloorDb,
                               float highTapePresenceBoostDb,
                               String transport,
                               String calibrationBasis,
                               String highTapeFrequencySpec,
                               String wowFlutterSpec,
                               String noiseSpecLabel,
                               String noiseSpecValue,
                               String highTapeLabel,
                               String lowTapeLabel,
                               boolean hotlineSupported) {
        this.id = id;
        this.manufacturer = manufacturer;
        this.model = model;
        this.year = year;
        this.lowFrequencyHz = lowFrequencyHz;
        this.highFrequencyHz = highFrequencyHz;
        this.wowFlutterRmsPercent = wowFlutterRmsPercent;
        this.referenceNoiseFloorDb = referenceNoiseFloorDb;
        this.highTapePresenceBoostDb = highTapePresenceBoostDb;
        this.transport = transport;
        this.calibrationBasis = calibrationBasis;
        this.highTapeFrequencySpec = highTapeFrequencySpec;
        this.wowFlutterSpec = wowFlutterSpec;
        this.noiseSpecLabel = noiseSpecLabel;
        this.noiseSpecValue = noiseSpecValue;
        this.highTapeLabel = highTapeLabel;
        this.lowTapeLabel = lowTapeLabel;
        this.hotlineSupported = hotlineSupported;
    }

    public static TapeMachineProfile sonyTpsL2Reference() {
        return TPS_L2_REFERENCE;
    }

    public static TapeMachineProfile aiwaHsJx707Reference() {
        return JX707_REFERENCE;
    }

    public static TapeMachineProfile sonyWmF2015Reference() {
        return WM_F2015_REFERENCE;
    }

    public static TapeMachineProfile sonyWmD6cReference() {
        return WM_D6C_REFERENCE;
    }

    public static List<TapeMachineProfile> availableProfiles() {
        return AVAILABLE;
    }

    public static TapeMachineProfile forId(String id) {
        if (SONY_WM_F2015.equals(id)) {
            return WM_F2015_REFERENCE;
        }
        if (SONY_WM_D6C.equals(id)) {
            return WM_D6C_REFERENCE;
        }
        if (AIWA_HS_JX707.equals(id)) {
            return JX707_REFERENCE;
        }
        return TPS_L2_REFERENCE;
    }

    public boolean isAiwaHsJx707() {
        return AIWA_HS_JX707.equals(id);
    }

    public boolean isSonyWmF2015() {
        return SONY_WM_F2015.equals(id);
    }

    public boolean isSonyWmD6c() {
        return SONY_WM_D6C.equals(id);
    }

    public boolean usesTapeTypeSelector() {
        return isSonyWmF2015() || isAiwaHsJx707() || isSonyWmD6c();
    }

    public boolean supportsDolbyBC() {
        return isAiwaHsJx707() || isSonyWmD6c();
    }

    public String noiseSpecValue(DolbyMode mode, TapeStockProfile tape) {
        DolbyMode selected = supportsDolbyBC() && mode != null ? mode : DolbyMode.OFF;
        if (isSonyWmD6c()) {
            boolean typeOne = tape == null || TapeStockProfile.forId(tape.id).iecType == 1;
            int signalToNoise = typeOne
                    ? selected == DolbyMode.C ? 67 : selected == DolbyMode.B ? 61 : 54
                    : selected == DolbyMode.C ? 71 : selected == DolbyMode.B ? 65 : 58;
            return signalToNoise + " dB  ·  TYPE " + (typeOne ? "I" : "II/IV")
                    + "  ·  NR " + selected.label;
        }
        if (selected == DolbyMode.OFF) {
            return noiseSpecValue;
        }
        return ">45 dB BASE  ·  DOLBY " + selected.label + " / "
                + selected.maximumNoiseReductionDb + " dB HF";
    }

    public String frequencySpec(boolean highTape) {
        if (isAiwaHsJx707() && !highTape) {
            return "63 Hz — 8 kHz";
        }
        if (isSonyWmF2015() && !highTape) {
            return "40 Hz — 11.6 kHz MODEL";
        }
        return highTapeFrequencySpec;
    }
}
