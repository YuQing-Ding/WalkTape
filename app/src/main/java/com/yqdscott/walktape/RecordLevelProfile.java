package com.yqdscott.walktape;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * How hot the programme was recorded onto the tape.
 *
 * <p>Every cassette deck has a record level control, and where it was set decides everything about
 * how the tape behaves: a coating has one fixed maximum output level, so the recordist chooses how
 * far into it the music sits. Without this the renderer had no defined operating point at all — it
 * fed the source file's own level straight at the coating, and a modern master peaking at 0 dBFS
 * lands about 18 dB above reference flux, which is far past where any tape was ever recorded. Sony
 * CHF is at 28 per cent distortion there, so the music arrives permanently crushed against the
 * ceiling with none of the dynamic range a tape actually has.</p>
 *
 * <p>Levels are stated the way a deck's meter is marked: dB above reference flux, which is where
 * programme peaks are intended to land. Record gain is applied before the coating and taken back
 * out after it, so moving this control changes how hard the tape is driven without changing how
 * loud playback is — the same thing a listener does by adjusting volume to match.</p>
 */
public final class RecordLevelProfile {
    public static final String CONSERVATIVE = "record_conservative";
    public static final String STANDARD = "record_standard";
    public static final String HOT = "record_hot";
    public static final String SATURATED = "record_saturated";

    /** Source full scale in the model, relative to reference flux: -18 dBFS is 0 dB. */
    private static final float FULL_SCALE_OVER_REFERENCE_DB = 18f;

    private static final RecordLevelProfile CONSERVATIVE_LEVEL = new RecordLevelProfile(
            CONSERVATIVE,
            "CONSERVATIVE",
            "PEAKS AT +3",
            "CAREFUL LEVEL / MINIMAL COMPRESSION",
            0xff8fb3a1,
            3f
    );

    private static final RecordLevelProfile STANDARD_LEVEL = new RecordLevelProfile(
            STANDARD,
            "STANDARD",
            "PEAKS AT +6",
            "NORMAL DOMESTIC RECORDING",
            0xffd7a23a,
            6f
    );

    private static final RecordLevelProfile HOT_LEVEL = new RecordLevelProfile(
            HOT,
            "HOT",
            "PEAKS AT +9",
            "PUSHED FOR TAPE COMPRESSION",
            0xffe08b3a,
            9f
    );

    private static final RecordLevelProfile SATURATED_LEVEL = new RecordLevelProfile(
            SATURATED,
            "SATURATED",
            "PEAKS AT +14",
            "DELIBERATELY OVERDRIVEN",
            0xffe95c2c,
            14f
    );

    private static final List<RecordLevelProfile> AVAILABLE =
            Collections.unmodifiableList(Arrays.asList(
                    CONSERVATIVE_LEVEL, STANDARD_LEVEL, HOT_LEVEL, SATURATED_LEVEL));

    public final String id;
    public final String name;
    public final String levelLabel;
    public final String character;
    public final int accentColor;

    /** Where programme peaks land, in dB above reference flux. */
    public final float peakOverReferenceDb;

    private RecordLevelProfile(String id,
                               String name,
                               String levelLabel,
                               String character,
                               int accentColor,
                               float peakOverReferenceDb) {
        this.id = id;
        this.name = name;
        this.levelLabel = levelLabel;
        this.character = character;
        this.accentColor = accentColor;
        this.peakOverReferenceDb = peakOverReferenceDb;
    }

    /**
     * Gain applied ahead of the coating, in dB. Always negative: source full scale is well above
     * any level a tape was recorded at, so the record control turns it down.
     */
    public float recordGainDb() {
        return peakOverReferenceDb - FULL_SCALE_OVER_REFERENCE_DB;
    }

    public String compactSpec() {
        return "PEAK " + (peakOverReferenceDb >= 0 ? "+" : "−")
                + Math.round(Math.abs(peakOverReferenceDb)) + " dB  ·  REC "
                + Math.round(recordGainDb()) + " dB";
    }

    public static RecordLevelProfile standard() {
        return STANDARD_LEVEL;
    }

    public static List<RecordLevelProfile> availableProfiles() {
        return AVAILABLE;
    }

    public static RecordLevelProfile forId(String id) {
        for (RecordLevelProfile profile : AVAILABLE) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        return STANDARD_LEVEL;
    }
}
