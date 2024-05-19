package com.yqdscott.walktape;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;

import java.util.Random;

public class CustomDropout implements AudioProcessor {
    private final float amount;
    private final Random random;

    public CustomDropout(float amount) {
        this.amount = amount;
        this.random = new Random();
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        for (int i = 0; i < buffer.length; i++) {
            if (random.nextFloat() < amount) {
                buffer[i] *= 0.5f; // Reduce amplitude instead of complete dropout
            }
        }
        return true;
    }

    @Override
    public void processingFinished() {
    }
}
