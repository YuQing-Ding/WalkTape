package com.yqdscott.walktape;

import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies that the full analogue model has enough real-time headroom on an Android device. */
@RunWith(AndroidJUnit4.class)
public class TpsL2DspDeviceTest {

    private static final String BENCHMARK_TAG = "WalkTapeBench";

    @Test
    public void fullCharacterRendererStaysComfortablyAheadOfRealTime() {
        final int sampleRate = 48_000;
        final int blockFrames = 2_048;
        final int audioSeconds = 12;
        final int blockCount = sampleRate * audioSeconds / blockFrames;
        float[] block = new float[blockFrames * 2];
        TpsL2Dsp renderer = new TpsL2Dsp(sampleRate);

        double phase = 0.0;
        for (int frame = 0; frame < blockFrames; frame++) {
            // Dense, deterministic programme material exercises saturation and modulation noise
            // more realistically than silence while keeping source synthesis outside the timer.
            float value = (float) (Math.sin(phase) * 0.48
                    + Math.sin(phase * 2.017) * 0.17
                    + Math.sin(phase * 5.031) * 0.06);
            phase += Math.PI * 2.0 * 997.0 / sampleRate;
            block[frame * 2] = value;
            block[frame * 2 + 1] = value * 0.93f;
        }

        // Exclude one-time ART/JIT compilation from the steady-state renderer measurement.
        for (int warmup = 0; warmup < 12; warmup++) {
            renderer.process(block, blockFrames);
        }
        renderer.reset();

        long startedMs = SystemClock.elapsedRealtime();
        for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
            renderer.process(block, blockFrames);
        }
        long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - startedMs);
        long renderedAudioMs = blockCount * blockFrames * 1_000L / sampleRate;
        Log.i(BENCHMARK_TAG, "full renderedMs=" + renderedAudioMs + " elapsedMs=" + elapsedMs);

        assertTrue("TPS-L2 renderer needs playback headroom: rendered " + renderedAudioMs
                        + " ms in " + elapsedMs + " ms",
                elapsedMs < renderedAudioMs / 2L);
    }

    @Test
    public void aiwaReferenceRendererStaysComfortablyAheadOfRealTime() {
        final int sampleRate = 48_000;
        final int blockFrames = 2_048;
        final int audioSeconds = 12;
        final int blockCount = sampleRate * audioSeconds / blockFrames;
        float[] block = new float[blockFrames * 2];
        AiwaHsJx707Dsp renderer = new AiwaHsJx707Dsp(sampleRate);
        renderer.setHighTape(true);

        double phase = 0.0;
        for (int frame = 0; frame < blockFrames; frame++) {
            float value = (float) (Math.sin(phase) * 0.48
                    + Math.sin(phase * 2.017) * 0.17
                    + Math.sin(phase * 5.031) * 0.06);
            phase += Math.PI * 2.0 * 997.0 / sampleRate;
            block[frame * 2] = value;
            block[frame * 2 + 1] = value * 0.93f;
        }
        for (int warmup = 0; warmup < 12; warmup++) {
            renderer.process(block, blockFrames);
        }
        renderer.reset();

        long startedMs = SystemClock.elapsedRealtime();
        for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
            renderer.process(block, blockFrames);
        }
        long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - startedMs);
        long renderedAudioMs = blockCount * blockFrames * 1_000L / sampleRate;
        Log.i(BENCHMARK_TAG, "aiwa renderedMs=" + renderedAudioMs
                + " elapsedMs=" + elapsedMs);

        assertTrue("HS-JX707 renderer needs playback headroom: rendered " + renderedAudioMs
                        + " ms in " + elapsedMs + " ms",
                elapsedMs < renderedAudioMs / 2L);
    }

    @Test
    public void reportsComponentCostBreakdown() {
        final int sampleRate = 48_000;
        final int blockFrames = 2_048;
        float[] block = new float[blockFrames * 2];
        for (int frame = 0; frame < blockFrames; frame++) {
            float value = (float) Math.sin(frame * Math.PI * 2.0 * 997.0 / sampleRate) * 0.55f;
            block[frame * 2] = value;
            block[frame * 2 + 1] = value * 0.91f;
        }

        benchmark("filters", new TpsL2Dsp(sampleRate, 9L, false, false, false), block);
        benchmark("saturation", new TpsL2Dsp(sampleRate, 9L, false, false, true), block);
        benchmark("transport", new TpsL2Dsp(sampleRate, 9L, true, false, false), block);
        benchmark("hiss", new TpsL2Dsp(sampleRate, 9L, false, true, false), block);
        benchmark("full", new TpsL2Dsp(sampleRate, 9L, true, true, true), block);
    }

    @Test
    public void productionTapeAndMachineChainsRetainRealtimeHeadroom() {
        final int sampleRate = 48_000;
        final int blockFrames = 2_048;
        final long renderedAudioMs = 3_072L;
        float[] block = new float[blockFrames * 2];
        for (int frame = 0; frame < blockFrames; frame++) {
            double phase = frame * Math.PI * 2.0 * 997.0 / sampleRate;
            float value = (float) (Math.sin(phase) * 0.52
                    + Math.sin(phase * 3.017) * 0.15);
            block[frame * 2] = value;
            block[frame * 2 + 1] = value * 0.91f;
        }

        TapeMachineDsp sonyFerric = TapeMachineDspFactory.create(
                TapeMachineProfile.sonyTpsL2Reference(),
                TapeStockProfile.sonyChf1978(), sampleRate);
        TapeMachineDsp aiwaTypeTwo = TapeMachineDspFactory.create(
                TapeMachineProfile.aiwaHsJx707Reference(),
                TapeStockProfile.tdkSa1988(), sampleRate);
        TapeMachineDsp aiwaDolbyC = TapeMachineDspFactory.create(
                TapeMachineProfile.aiwaHsJx707Reference(),
                TapeStockProfile.tdkSa1988(), sampleRate);
        TapeMachineDsp d6cDolbyC = TapeMachineDspFactory.create(
                TapeMachineProfile.sonyWmD6cReference(),
                TapeStockProfile.tdkSa1988(), sampleRate);
        TapeMachineDsp d6cTypeTwo = TapeMachineDspFactory.create(
                TapeMachineProfile.sonyWmD6cReference(),
                TapeStockProfile.tdkSa1988(), sampleRate);
        TapeMachineDsp f2015TypeTwo = TapeMachineDspFactory.create(
                TapeMachineProfile.sonyWmF2015Reference(),
                TapeStockProfile.tdkSa1988(), sampleRate);
        TapeMachineDsp sonyLivedIn = TapeMachineDspFactory.create(
                TapeMachineProfile.sonyTpsL2Reference(),
                TapeStockProfile.sonyChf1978(),
                MachineConditionProfile.livedIn(), sampleRate);
        TapeMachineDsp f2015LivedIn = TapeMachineDspFactory.create(
                TapeMachineProfile.sonyWmF2015Reference(),
                TapeStockProfile.tdkSa1988(),
                MachineConditionProfile.livedIn(), sampleRate);
        aiwaTypeTwo.setHighTape(true);
        aiwaDolbyC.setHighTape(true);
        aiwaDolbyC.setDolbyMode(DolbyMode.C);
        d6cDolbyC.setHighTape(true);
        d6cDolbyC.setDolbyMode(DolbyMode.C);
        d6cTypeTwo.setHighTape(true);
        f2015TypeTwo.setHighTape(true);
        long sonyMs = benchmark("production-tps-chf", sonyFerric, block);
        long aiwaMs = benchmark("production-jx707-sa", aiwaTypeTwo, block);
        long aiwaDolbyCMs = benchmark("production-jx707-sa-dolby-c", aiwaDolbyC, block);
        long d6cMs = benchmark("production-wm-d6c-sa", d6cTypeTwo, block);
        long d6cDolbyCMs = benchmark("production-wm-d6c-sa-dolby-c", d6cDolbyC, block);
        long f2015Ms = benchmark("production-wm-f2015-sa", f2015TypeTwo, block);
        long livedInMs = benchmark("production-tps-chf-lived-in", sonyLivedIn, block);
        long f2015LivedInMs = benchmark(
                "production-wm-f2015-sa-lived-in", f2015LivedIn, block);

        assertTrue("TPS-L2 × CHF needs at least 1.5x realtime headroom: " + sonyMs,
                sonyMs < renderedAudioMs * 0.65);
        assertTrue("JX707 × SA needs at least 1.5x realtime headroom: " + aiwaMs,
                aiwaMs < renderedAudioMs * 0.65);
        assertTrue("JX707 × SA × Dolby C needs sustained realtime headroom: "
                        + aiwaDolbyCMs,
                aiwaDolbyCMs < renderedAudioMs * 0.85);
        assertTrue("WM-D6C × SA × Dolby C needs sustained realtime headroom: "
                        + d6cDolbyCMs,
                d6cDolbyCMs < renderedAudioMs * 0.85);
        assertTrue("WM-D6C × SA needs at least 1.5x realtime headroom: " + d6cMs,
                d6cMs < renderedAudioMs * 0.65);
        assertTrue("WM-F2015 × SA needs at least 1.5x realtime headroom: " + f2015Ms,
                f2015Ms < renderedAudioMs * 0.65);
        assertTrue("LIVED-IN condition needs at least 1.5x realtime headroom: " + livedInMs,
                livedInMs < renderedAudioMs * 0.65);
        assertTrue("WM-F2015 × SA × LIVED-IN needs at least 1.5x realtime headroom: "
                        + f2015LivedInMs,
                f2015LivedInMs < renderedAudioMs * 0.65);
    }

    @Test
    public void reports192KhzFirAndTapePipelineCost() {
        final int inputRate = 192_000;
        final int outputRate = 48_000;
        final int inputFrames = 4_096;
        final int blocks = inputRate * 6 / inputFrames;
        float[] input = new float[inputFrames * 2];
        for (int frame = 0; frame < inputFrames; frame++) {
            float value = (float) Math.sin(frame * Math.PI * 2.0 * 997.0 / inputRate) * 0.55f;
            input[frame * 2] = value;
            input[frame * 2 + 1] = value * 0.91f;
        }
        PlaybackController.PcmRateConverter converter =
                new PlaybackController.PcmRateConverter(inputRate, outputRate);
        float[] output = new float[converter.maximumOutputFrames(inputFrames) * 2];
        TpsL2Dsp renderer = new TpsL2Dsp(outputRate);
        renderer.setHighTape(true);
        renderer.reset();

        for (int warmup = 0; warmup < 12; warmup++) {
            int frames = converter.process(input, inputFrames, output);
            renderer.process(output, frames);
        }
        converter.reset();
        renderer.reset();
        long started = SystemClock.elapsedRealtime();
        for (int block = 0; block < blocks; block++) {
            int frames = converter.process(input, inputFrames, output);
            renderer.process(output, frames);
        }
        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - started);
        long audioMs = (long) blocks * inputFrames * 1_000L / inputRate;
        Log.i(BENCHMARK_TAG, "192k-pipeline audioMs=" + audioMs + " elapsedMs=" + elapsed);

        TapeMachineDsp d6cDolbyC = TapeMachineDspFactory.create(
                TapeMachineProfile.sonyWmD6cReference(),
                TapeStockProfile.tdkSa1988(), outputRate);
        d6cDolbyC.setHighTape(true);
        d6cDolbyC.setDolbyMode(DolbyMode.C);
        converter.reset();
        for (int warmup = 0; warmup < 12; warmup++) {
            int frames = converter.process(input, inputFrames, output);
            d6cDolbyC.process(output, frames);
        }
        converter.reset();
        d6cDolbyC.reset();
        long d6cStarted = SystemClock.elapsedRealtime();
        for (int block = 0; block < blocks; block++) {
            int frames = converter.process(input, inputFrames, output);
            d6cDolbyC.process(output, frames);
        }
        long d6cElapsed = Math.max(1L,
                SystemClock.elapsedRealtime() - d6cStarted);
        Log.i(BENCHMARK_TAG, "192k-d6c-dolby-c audioMs=" + audioMs
                + " elapsedMs=" + d6cElapsed);
        assertTrue("192 kHz resample × WM-D6C × Dolby C must remain ahead of realtime: "
                        + d6cElapsed,
                d6cElapsed < audioMs * 0.92);
    }

    private static long benchmark(String name, TapeMachineDsp renderer, float[] template) {
        final int blockFrames = template.length / 2;
        final int blocks = 72; // 3.072 seconds at 48 kHz.
        float[] block = template.clone();
        for (int warmup = 0; warmup < 12; warmup++) {
            renderer.process(block, blockFrames);
        }
        renderer.reset();
        long started = SystemClock.elapsedRealtime();
        for (int index = 0; index < blocks; index++) {
            renderer.process(block, blockFrames);
        }
        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - started);
        Log.i(BENCHMARK_TAG, name + " audioMs=3072 elapsedMs=" + elapsed);
        return elapsed;
    }

}
