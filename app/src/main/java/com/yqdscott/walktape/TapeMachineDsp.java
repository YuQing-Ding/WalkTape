package com.yqdscott.walktape;

/** Realtime, allocation-free stereo renderer for one physical cassette machine profile. */
interface TapeMachineDsp {
    void setHighTape(boolean enabled);

    /** Unsupported machines deliberately keep the inherited no-op implementation. */
    default void setDolbyMode(DolbyMode mode) {
    }

    /** Normalised pack position lets physical transports derive reel radius and back tension. */
    default void setTapePosition(float position) {
    }

    /** Unsupported renderers keep their established continuously-playing behaviour. */
    default void setTransportState(TapeTransportState state) {
    }

    /**
     * How far the machine's cells have been discharged, 0 for fresh and 1 for exhausted.
     *
     * <p>Battery-powered models let this pull the supply rail down, which costs output headroom
     * and eventually speed. Fresh is the default, so a caller that never sets it sees no change.
     * </p>
     */
    default void setBatteryDepthOfDischarge(float depth) {
    }

    /**
     * Where the record level control was set when this tape was made.
     *
     * <p>A property of the tape rather than of the machine, so only renderers that carry a
     * separate tape stage act on it.</p>
     */
    default void setRecordLevel(RecordLevelProfile level) {
    }

    /**
     * Frames of look-ahead the renderer consumes before its output lines up with its input.
     *
     * <p>Only physical effects that genuinely depend on programme the tape has not reached yet —
     * print-through pre-echo is the real case — need this. The caller discards this many leading
     * frames after {@link #reset()} so the audible timeline stays sample accurate.</p>
     */
    default int latencyFrames() {
        return 0;
    }

    void reset();

    void process(float[] stereo, int frameCount);
}
