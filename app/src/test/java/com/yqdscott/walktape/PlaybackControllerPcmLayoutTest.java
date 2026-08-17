package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;

import android.media.AudioFormat;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The four-byte-per-sample case of the raw PCM width measurement.
 *
 * <p>A Sony hi-res build of Android 7.1.1 hands this app raw samples tagged with encoding 100,
 * which no AudioFormat constant describes, so the layout is measured from the samples instead.
 * Three of them are four bytes wide and only their contents tell them apart; reading one as
 * another plays as noise or 48 dB down, which is why this is worth pinning down.</p>
 */
public class PlaybackControllerPcmLayoutTest {

    @Test
    public void floatSamplesAreRecognisedAsFloat() {
        ByteBuffer buffer = allocate(64);
        for (int index = 0; index < 64; index++) {
            buffer.putFloat((float) Math.sin(index * 0.2) * 0.8f);
        }
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT,
                PlaybackController.measureThirtyTwoBitLayout(buffer, 64 * 4, 0));
    }

    @Test
    public void fullScaleIntegersAreRecognisedAsThirtyTwoBit() {
        ByteBuffer buffer = allocate(64);
        for (int index = 0; index < 64; index++) {
            buffer.putInt((int) (Math.sin(index * 0.2) * 0.8 * Integer.MAX_VALUE));
        }
        assertEquals(22, PlaybackController.measureThirtyTwoBitLayout(buffer, 64 * 4, 0));
    }

    @Test
    public void twentyFourBitsInThirtyTwoBitWordsAreRecognised() {
        ByteBuffer buffer = allocate(64);
        for (int index = 0; index < 64; index++) {
            buffer.putInt((int) (Math.sin(index * 0.2) * 0.8 * 8_388_608.0));
        }
        assertEquals(-24, PlaybackController.measureThirtyTwoBitLayout(buffer, 64 * 4, 24));
    }

    /**
     * A quiet integer stream reinterpreted as float is nothing but denormals: every one of them is
     * finite and far below unity, so bounded-ness alone would call this float and play silence.
     */
    @Test
    public void quietIntegersAreNotMistakenForFloat() {
        ByteBuffer buffer = allocate(64);
        for (int index = 0; index < 64; index++) {
            buffer.putInt((int) (Math.sin(index * 0.2) * 30_000.0));
        }
        assertEquals(-24, PlaybackController.measureThirtyTwoBitLayout(buffer, 64 * 4, 24));
    }

    /** Silence carries no scale of its own, so the container's word length decides. */
    @Test
    public void silenceFallsBackToTheHeaderWordLength() {
        ByteBuffer silence = allocate(64);
        assertEquals(-24, PlaybackController.measureThirtyTwoBitLayout(silence, 64 * 4, 24));
        assertEquals(22, PlaybackController.measureThirtyTwoBitLayout(silence, 64 * 4, 32));
    }

    private static ByteBuffer allocate(int samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer;
    }
}
