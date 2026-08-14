package com.yqdscott.walktape;

/** Physical transport command delivered to machine models without tying DSP to the UI. */
enum TapeTransportState {
    STOPPED,
    STARTING,
    PLAYING,
    PAUSED,
    FAST_FORWARD,
    REWIND
}
