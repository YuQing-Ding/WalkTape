package com.yqdscott.walktape;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;

public class CustomSuperBass implements AudioProcessor {
    private final float bassBoost;
    private final float cutoffFrequency;
    private final float sampleRate;
    private float lowPassOutput;

    public CustomSuperBass(float bassBoost, float cutoffFrequency) {
        this.bassBoost = bassBoost;
        this.cutoffFrequency = cutoffFrequency;
        this.sampleRate = 22050; // Set sample rate to 22050 Hz
        this.lowPassOutput = 0;
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        float alpha = (float) (2 * Math.PI * cutoffFrequency / sampleRate);

        for (int i = 0; i < buffer.length; i++) {
            // Apply low-pass filter
            lowPassOutput += alpha * (buffer[i] - lowPassOutput);

            // Combine original signal with boosted low-pass signal
            buffer[i] = buffer[i] + bassBoost * lowPassOutput;
        }
        return true;
    }

    @Override
    public void processingFinished() {
    }
}
