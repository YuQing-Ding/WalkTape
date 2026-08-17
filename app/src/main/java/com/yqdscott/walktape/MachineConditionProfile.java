package com.yqdscott.walktape;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Benign unit-to-unit tolerances for an otherwise healthy cassette player.
 *
 * <p>These presets deliberately exclude drop-outs, crackle, chewing, gross speed error and other
 * faults. They describe the small alignment and bearing differences found between working units
 * of the same model.</p>
 */
public final class MachineConditionProfile {
    public static final String CALIBRATED = "condition_calibrated";
    public static final String NATURAL = "condition_natural";
    public static final String LIVED_IN = "condition_lived_in";
    public static final String EXTRA_LIVED_IN = "condition_extra_lived_in";

    private static final MachineConditionProfile REFERENCE = new MachineConditionProfile(
            CALIBRATED,
            "CALIBRATED",
            "REFERENCE UNIT",
            "FACTORY-ALIGNED / MATCHED CHANNELS",
            "NO EXTRA TOLERANCE",
            0xff9a968c,
            0f,
            0f,
            0f,
            0f,
            0f
    );

    private static final MachineConditionProfile NATURAL_UNIT = new MachineConditionProfile(
            NATURAL,
            "NATURAL",
            "SUBTLE UNIT VARIATION",
            "SMALL ALIGNMENT / BEARING TOLERANCES",
            "+0.014% W&F  ·  ΔBAL 0.14 dB",
            0xffd7a23a,
            0.50f,
            0.14f,
            0.55f,
            0.14f,
            0.0008f
    );

    private static final MachineConditionProfile LIVED_IN_UNIT = new MachineConditionProfile(
            LIVED_IN,
            "LIVED-IN",
            "MATURE / WELL MAINTAINED",
            "SETTLED MECHANICS / NEVER DAMAGED",
            "+0.028% W&F  ·  ΔBAL 0.34 dB",
            0xffe95c2c,
            1.00f,
            0.34f,
            1.25f,
            0.36f,
            0.0020f
    );

    /**
     * An older survivor: still healthy, but no longer close to its alignment jig.
     *
     * <p>Every term is the same one the LIVED-IN preset uses, roughly doubled, and the character
     * comes mostly from azimuth rather than from speed. That is what actually ages a portable:
     * head wear and a drifted azimuth screw cost high-frequency coherence between the channels
     * long before the bearings get noisy enough to hear. Speed variation is raised least, because
     * a machine whose wow is audible as pitch is a machine that wants servicing, and this class
     * describes units that do not.</p>
     *
     * <p>Still deliberately free of drop-outs, crackle and gross speed error: at +0.052% RMS this
     * remains far inside every modelled machine's service limit, including the JX707's 0.45%.</p>
     */
    private static final MachineConditionProfile EXTRA_LIVED_IN_UNIT = new MachineConditionProfile(
            EXTRA_LIVED_IN,
            "EXTRA LIVED-IN",
            "WELL-WORN / SERVICEABLE",
            "WORN HEAD / DRIFTED AZIMUTH",
            "+0.052% W&F  ·  ΔBAL 0.62 dB",
            0xffb8392b,
            1.85f,
            0.62f,
            2.40f,
            0.62f,
            0.0038f
    );

    private static final List<MachineConditionProfile> AVAILABLE =
            Collections.unmodifiableList(Arrays.asList(
                    REFERENCE, NATURAL_UNIT, LIVED_IN_UNIT, EXTRA_LIVED_IN_UNIT));

    public final String id;
    public final String name;
    public final String levelLabel;
    public final String character;
    public final String compactSpec;
    public final int accentColor;

    // Renderer calibration. Public UI text above remains intentionally qualitative.
    final float transportScale;
    final float channelBalanceDb;
    final float azimuthMicroseconds;
    final float highFrequencyMismatchDb;
    final float extraCrosstalk;

    private MachineConditionProfile(String id,
                                    String name,
                                    String levelLabel,
                                    String character,
                                    String compactSpec,
                                    int accentColor,
                                    float transportScale,
                                    float channelBalanceDb,
                                    float azimuthMicroseconds,
                                    float highFrequencyMismatchDb,
                                    float extraCrosstalk) {
        this.id = id;
        this.name = name;
        this.levelLabel = levelLabel;
        this.character = character;
        this.compactSpec = compactSpec;
        this.accentColor = accentColor;
        this.transportScale = transportScale;
        this.channelBalanceDb = channelBalanceDb;
        this.azimuthMicroseconds = azimuthMicroseconds;
        this.highFrequencyMismatchDb = highFrequencyMismatchDb;
        this.extraCrosstalk = extraCrosstalk;
    }

    public static MachineConditionProfile calibrated() {
        return REFERENCE;
    }

    public static MachineConditionProfile natural() {
        return NATURAL_UNIT;
    }

    public static MachineConditionProfile livedIn() {
        return LIVED_IN_UNIT;
    }

    public static MachineConditionProfile extraLivedIn() {
        return EXTRA_LIVED_IN_UNIT;
    }

    public static List<MachineConditionProfile> availableProfiles() {
        return AVAILABLE;
    }

    public static MachineConditionProfile forId(String id) {
        if (NATURAL.equals(id)) {
            return NATURAL_UNIT;
        }
        if (LIVED_IN.equals(id)) {
            return LIVED_IN_UNIT;
        }
        if (EXTRA_LIVED_IN.equals(id)) {
            return EXTRA_LIVED_IN_UNIT;
        }
        return REFERENCE;
    }

    public boolean isCalibrated() {
        return CALIBRATED.equals(id);
    }
}
