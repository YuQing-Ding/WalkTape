package com.yqdscott.walktape;

/** Realtime, allocation-free stereo renderer for one physical cassette machine profile. */
interface TapeMachineDsp {
    void setHighTape(boolean enabled);

    void reset();

    void process(float[] stereo, int frameCount);
}
