package com.yqdscott.walktape;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;

public class CustomWowFlutter implements AudioProcessor {
    private final float rate;
    private final float depth;
    private float phase;

    public CustomWowFlutter(float rate, float depth) {
        this.rate = rate;
        this.depth = depth;
        this.phase = 0;
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        float sampleRate = audioEvent.getSampleRate();
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] *= (float) (1 + depth * Math.sin(2 * Math.PI * rate * (phase + i) / sampleRate));
        }
        phase += buffer.length;
        return true;
    }

    @Override
    public void processingFinished() {
    }
}
