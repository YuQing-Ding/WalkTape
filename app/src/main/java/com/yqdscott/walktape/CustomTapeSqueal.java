package com.yqdscott.walktape;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;

public class CustomTapeSqueal implements AudioProcessor {
    private final float amount;
    private final float frequency;
    private float phase;

    public CustomTapeSqueal(float amount, float frequency) {
        this.amount = amount;
        this.frequency = frequency;
        this.phase = 0;
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        float sampleRate = 22050; // Assuming a sample rate of 22050 Hz
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] += amount * (float) Math.sin(2 * Math.PI * frequency * phase / sampleRate);
            phase += 1;
        }
        return true;
    }

    @Override
    public void processingFinished() {

    }
}
