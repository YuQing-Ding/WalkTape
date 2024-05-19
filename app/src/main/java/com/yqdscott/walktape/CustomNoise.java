package com.yqdscott.walktape;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;

import java.util.Random;

public class CustomNoise implements AudioProcessor {
    private final float amount;
    private final Random random;

    public CustomNoise(float amount) {
        this.amount = amount;
        this.random = new Random();
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] += (random.nextFloat() - 0.5f) * amount * 0.1f; // Scale noise addition
        }
        return true;
    }

    @Override
    public void processingFinished() {
    }
}
