package com.yqdscott.walktape;

/**
 * A small allocation-free PCM level timeline used to keep a visual meter on the audible
 * playhead rather than on the decoder's several-seconds-ahead producer position.
 */
final class AudioLevelTimeline {
    private static final int CAPACITY = 1_024;
    private static final long STALE_GRACE_US = 75_000L;
    private final long[] startUs = new long[CAPACITY];
    private final long[] endUs = new long[CAPACITY];
    private final float[] leftPeak = new float[CAPACITY];
    private final float[] rightPeak = new float[CAPACITY];
    private int writeIndex;
    private int count;

    void recordPcm(long firstFrameUs, int sampleRate, float[] stereo, int frameCount) {
        if (sampleRate <= 0 || frameCount <= 0 || stereo == null
                || frameCount * 2 > stereo.length) {
            return;
        }
        // Ten-millisecond windows preserve real transients without pushing callbacks from the
        // realtime decoder. Main/UI samples these points against AudioTrack's playback head.
        int windowFrames = Math.max(1, sampleRate / 100);
        int frame = 0;
        while (frame < frameCount) {
            int windowEnd = Math.min(frameCount, frame + windowFrames);
            float left = 0f;
            float right = 0f;
            for (int current = frame; current < windowEnd; current++) {
                int sample = current * 2;
                left = Math.max(left, Math.abs(finite(stereo[sample])));
                right = Math.max(right, Math.abs(finite(stereo[sample + 1])));
            }
            long windowStartUs = Math.max(0L, firstFrameUs
                    + frame * 1_000_000L / sampleRate);
            long windowEndUs = Math.max(windowStartUs + 1L, firstFrameUs
                    + windowEnd * 1_000_000L / sampleRate);
            recordPeaks(windowStartUs, windowEndUs,
                    Math.min(1f, left), Math.min(1f, right));
            frame = windowEnd;
        }
    }

    synchronized void sample(long playheadUs, float[] destination) {
        if (destination == null || destination.length < 2) {
            throw new IllegalArgumentException("Two meter channels are required");
        }
        destination[0] = 0f;
        destination[1] = 0f;
        long position = Math.max(0L, playheadUs);
        for (int offset = 1; offset <= count; offset++) {
            int index = (writeIndex - offset + CAPACITY) % CAPACITY;
            if (position < startUs[index]) {
                continue;
            }
            if (position <= endUs[index] + STALE_GRACE_US) {
                destination[0] = leftPeak[index];
                destination[1] = rightPeak[index];
            }
            return;
        }
    }

    synchronized void clear() {
        writeIndex = 0;
        count = 0;
    }

    synchronized int pointCountForTest() {
        return count;
    }

    private synchronized void recordPeaks(long fromUs,
                                          long toUs,
                                          float left,
                                          float right) {
        startUs[writeIndex] = fromUs;
        endUs[writeIndex] = toUs;
        leftPeak[writeIndex] = left;
        rightPeak[writeIndex] = right;
        writeIndex = (writeIndex + 1) % CAPACITY;
        count = Math.min(CAPACITY, count + 1);
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0f;
    }
}
