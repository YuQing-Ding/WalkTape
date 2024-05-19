package com.yqdscott.walktape;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;

public class CustomDistortion implements AudioProcessor {
    private final float amount;

    public CustomDistortion(float amount) {
        this.amount = amount;
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = buffer[i] > amount ? amount : Math.max(buffer[i], -amount);
            buffer[i] *= 0.5f; // Reduce overall amplitude to avoid harshness
        }
        return true;
    }

    @Override
    public void processingFinished() {
    }
}
