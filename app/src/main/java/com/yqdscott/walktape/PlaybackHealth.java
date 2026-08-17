package com.yqdscott.walktape;

import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Always-on, allocation-free health probe for the realtime playback pipeline.
 *
 * <p>A tape renderer that is fast in isolation can still stutter when file I/O, the platform
 * decoder or the scheduler steals the decode thread. Guessing which one is responsible from an
 * audible symptom is unreliable, so the pipeline measures itself: how much CPU each stage really
 * costs, how many seconds of PCM reserve remain, and how many times AudioTrack actually ran dry.
 *
 * <p>One line is written to logcat every {@link #REPORT_INTERVAL_MS} milliseconds under the tag
 * {@code WalkTapeHealth}. Counters are owned by a single thread each and published through
 * volatile fields, so nothing here can block or allocate on the audio path.</p>
 */
final class PlaybackHealth {

    static final String TAG = "WalkTapeHealth";
    private static final long REPORT_INTERVAL_MS = 5_000L;

    // Written only by the decoder/render thread.
    private long readNanos;
    private long decodeNanos;
    private long renderNanos;
    private long producedFrames;
    private long blockedOnQueueNanos;
    private long lastReportUptimeMs;

    // Written only by the PCM writer thread.
    private volatile int underruns;
    private volatile int recoveries;
    private volatile long writeBlockedNanos;

    // Written by both; a torn read only mis-reports one line.
    private volatile int reserveFrames;
    private volatile int reserveCapacityFrames;
    private volatile int latencyFrames;
    private volatile int leadInFrames;
    private volatile int leadInArmings;

    /** Which machine is being rendered. Set from the thread that builds the renderer. */
    private volatile String label;
    private int sampleRate;

    PlaybackHealth(String label) {
        this.label = label;
        lastReportUptimeMs = SystemClock.uptimeMillis();
    }

    /**
     * Names the machine the following reports describe.
     *
     * <p>The renderer can be swapped mid-track, and a report that keeps naming the machine the
     * session started on is worse than no name: the cost of each machine is exactly what these
     * lines are read for.</p>
     */
    void setLabel(String label) {
        if (label != null && !label.isEmpty()) {
            this.label = label;
        }
    }

    void setSampleRate(int rate) {
        sampleRate = rate;
    }

    /** Time spent inside MediaExtractor/packet acquisition, i.e. blocking storage reads. */
    void addReadNanos(long nanos) {
        readNanos += nanos;
    }

    /** Time spent inside MediaCodec / the ALAC decoder. */
    void addDecodeNanos(long nanos) {
        decodeNanos += nanos;
    }

    /** Time spent inside PCM conversion, resampling and the tape/machine renderer. */
    void addRenderNanos(long nanos, int frames) {
        renderNanos += nanos;
        producedFrames += frames;
    }

    /** Time the producer sat waiting because the reserve was already full. This is healthy. */
    void addQueueWaitNanos(long nanos) {
        blockedOnQueueNanos += nanos;
    }

    void setReserve(int frames, int capacityFrames) {
        reserveFrames = frames;
        reserveCapacityFrames = capacityFrames;
    }

    /**
     * Records that the renderer's look-ahead has been armed again.
     *
     * <p>A renderer with look-ahead emits nothing until it has been fed that many frames. If this
     * count climbs during steady playback then something is resetting the renderer repeatedly, and
     * every arming is a gap the reserve has to absorb.</p>
     */
    void armLookAhead(int latency, int leadIn) {
        latencyFrames = latency;
        leadInFrames = leadIn;
        if (leadIn > 0) {
            leadInArmings++;
        }
    }

    void setLeadInRemaining(int frames) {
        leadInFrames = frames;
    }

    void addWriteBlockedNanos(long nanos) {
        writeBlockedNanos += nanos;
    }

    void recordRecovery() {
        recoveries++;
    }

    void updateUnderruns(AudioTrack output) {
        if (output != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                underruns = output.getUnderrunCount();
            } catch (IllegalStateException ignored) {
                // The track is being released; the next report simply repeats the last value.
            }
        }
    }

    /**
     * Emits one report if the interval has elapsed. Called from the producer between blocks, so
     * it never delays a write to the hardware.
     */
    void reportIfDue() {
        long now = SystemClock.uptimeMillis();
        long elapsedMs = now - lastReportUptimeMs;
        if (elapsedMs < REPORT_INTERVAL_MS) {
            return;
        }
        lastReportUptimeMs = now;

        int rate = sampleRate;
        double audioSeconds = rate > 0 ? producedFrames / (double) rate : 0.0;
        if (audioSeconds <= 0.0) {
            resetWindow();
            return;
        }

        // Milliseconds of CPU consumed per second of audio produced. The pipeline is healthy while
        // the total stays far below 1000; a value approaching 1000 means the producer is at the
        // edge and any scheduling hiccup becomes an audible dropout.
        double read = readNanos / 1e6 / audioSeconds;
        double decode = decodeNanos / 1e6 / audioSeconds;
        double render = renderNanos / 1e6 / audioSeconds;
        double idle = blockedOnQueueNanos / 1e6 / audioSeconds;
        double writeBlocked = writeBlockedNanos / 1e6 / audioSeconds;
        int frames = reserveFrames;
        int capacity = Math.max(1, reserveCapacityFrames);
        double reserveMs = rate > 0 ? frames * 1000.0 / rate : 0.0;

        Log.i(TAG, String.format(
                "%s %dHz | io %.1f decode %.1f render %.1f idle %.0f writeWait %.0f ms/s"
                        + " | reserve %.0f ms (%d%%) | underruns %d | reprimes %d"
                        + " | lookAhead %.0f ms, leadIn %.0f ms, armed %d",
                label, rate, read, decode, render, idle, writeBlocked,
                reserveMs, frames * 100 / capacity, underruns, recoveries,
                rate > 0 ? latencyFrames * 1000.0 / rate : 0.0,
                rate > 0 ? leadInFrames * 1000.0 / rate : 0.0,
                leadInArmings));

        resetWindow();
    }

    private void resetWindow() {
        readNanos = 0;
        decodeNanos = 0;
        renderNanos = 0;
        producedFrames = 0;
        blockedOnQueueNanos = 0;
        writeBlockedNanos = 0;
    }
}
