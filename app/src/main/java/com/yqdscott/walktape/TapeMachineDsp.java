package com.yqdscott.walktape;

/** Realtime, allocation-free stereo renderer for one physical cassette machine profile. */
interface TapeMachineDsp {
    void setHighTape(boolean enabled);

    /** Unsupported machines deliberately keep the inherited no-op implementation. */
    default void setDolbyMode(DolbyMode mode) {
    }

    void reset();

    void process(float[] stereo, int frameCount);
}
