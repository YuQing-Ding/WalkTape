package com.yqdscott.walktape;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;

import java.util.Random;

public class CustomTapeHiss implements AudioProcessor {
    private final float amount;
    private final Random random;

    public CustomTapeHiss(float amount) {
        this.amount = amount;
        this.random = new Random();
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] += (random.nextFloat() - 0.5f) * amount * 2; // Add high-frequency noise
        }
        return true;
    }

    @Override
    public void processingFinished() {
    }
}
