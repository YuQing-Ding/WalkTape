package com.yqdscott.walktape;

/**
 * A measurement target for a physical tape machine.  This is deliberately
 * separate from DSP implementation: displaying or storing a specification must
 * never be confused with having reproduced it accurately.
 */
public final class TapeMachineProfile {
    public final String manufacturer;
    public final String model;
    public final int year;
    public final int lowFrequencyHz;
    public final int highFrequencyHz;
    public final float wowFlutterRmsPercent;
    public final float referenceNoiseFloorDb;
    public final float highTapePresenceBoostDb;
    public final String transport;

    private TapeMachineProfile(String manufacturer,
                               String model,
                               int year,
                               int lowFrequencyHz,
                               int highFrequencyHz,
                               float wowFlutterRmsPercent,
                               float referenceNoiseFloorDb,
                               float highTapePresenceBoostDb,
                               String transport) {
        this.manufacturer = manufacturer;
        this.model = model;
        this.year = year;
        this.lowFrequencyHz = lowFrequencyHz;
        this.highFrequencyHz = highFrequencyHz;
        this.wowFlutterRmsPercent = wowFlutterRmsPercent;
        this.referenceNoiseFloorDb = referenceNoiseFloorDb;
        this.highTapePresenceBoostDb = highTapePresenceBoostDb;
        this.transport = transport;
    }

    public static TapeMachineProfile sonyTpsL2Reference() {
        return new TapeMachineProfile(
                "SONY",
                "TPS-L2",
                1979,
                40,
                12_000,
                0.219f,
                -67f,
                6f,
                "MECHANICAL"
        );
    }
}
