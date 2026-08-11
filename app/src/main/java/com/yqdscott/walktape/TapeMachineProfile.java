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

    private static final List<TapeMachineProfile> AVAILABLE = Collections.unmodifiableList(
            Arrays.asList(TPS_L2_REFERENCE, JX707_REFERENCE));

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

    public static List<TapeMachineProfile> availableProfiles() {
        return AVAILABLE;
    }

    public static TapeMachineProfile forId(String id) {
        if (AIWA_HS_JX707.equals(id)) {
            return JX707_REFERENCE;
        }
        return TPS_L2_REFERENCE;
    }

    public boolean isAiwaHsJx707() {
        return AIWA_HS_JX707.equals(id);
    }

    public String frequencySpec(boolean highTape) {
        if (isAiwaHsJx707() && !highTape) {
            return "63 Hz — 8 kHz";
        }
        return highTapeFrequencySpec;
    }
}
