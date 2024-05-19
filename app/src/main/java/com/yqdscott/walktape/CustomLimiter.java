package com.yqdscott.walktape;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;

public class CustomLimiter implements AudioProcessor {
    private final float threshold;

    public CustomLimiter(float threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        for (int i = 0; i < buffer.length; i++) {
            if (buffer[i] > threshold) {
                buffer[i] = threshold;
            } else if (buffer[i] < -threshold) {
                buffer[i] = -threshold;
            }
        }
        return true;
    }

    @Override
    public void processingFinished() {
    }
}
