package com.yqdscott.walktape;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;

public class CustomGain implements AudioProcessor {
    private final float gain;

    public CustomGain(float gain) {
        this.gain = gain;
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] *= gain;
        }
        return true;
    }

    @Override
    public void processingFinished() {
    }
}
