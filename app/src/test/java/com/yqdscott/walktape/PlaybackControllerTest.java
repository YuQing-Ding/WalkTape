package com.yqdscott.walktape;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackControllerTest {

    @Test
    public void endOfStreamFlagsCannotMasqueradeAsDrm() {
        assertFalse(PlaybackController.isEncryptedSample(-1, -1));
        assertTrue(PlaybackController.isEncryptedSample(
                0, android.media.MediaExtractor.SAMPLE_FLAG_ENCRYPTED));
    }

    @Test
    public void packsSigned16BitAlacSamplesAsLittleEndianPcm() {
        int[] decoded = {0x1234, -2, -32_768, 32_767};
        byte[] pcm = new byte[8];

        PlaybackController.packAlacPcm(decoded, pcm.length, 2, pcm);

        assertArrayEquals(new byte[]{
                0x34, 0x12,
                (byte) 0xfe, (byte) 0xff,
                0x00, (byte) 0x80,
                (byte) 0xff, 0x7f
        }, pcm);
    }

    @Test
    public void packs24BitAlacByteStreamWithoutLosingHighBytes() {
        int[] decoded = {0x00, 0x7f, 0x80, 0xff, 0x22, 0xdd};
        byte[] pcm = new byte[6];

        PlaybackController.packAlacPcm(decoded, pcm.length, 3, pcm);

        assertArrayEquals(new byte[]{
                0x00, 0x7f, (byte) 0x80,
                (byte) 0xff, 0x22, (byte) 0xdd
        }, pcm);
    }

    @Test
    public void converts24BitAlacDirectlyToStereoFloatWithoutIntermediatePcmCopy() {
        int[] decodedBytes = {
                0xff, 0xff, 0x7f, 0x00, 0x00, 0x80,
                0x00, 0x00, 0x40, 0x00, 0x00, 0xc0
        };
        float[] stereo = new float[4];

        int frames = PlaybackController.unpackAlacPcmToStereo(
                decodedBytes, decodedBytes.length, 3, 2, 0, stereo);

        assertEquals(2, frames);
        assertEquals(0.9999999f, stereo[0], 0.000001f);
        assertEquals(-1f, stereo[1], 0.000001f);
        assertEquals(0.5f, stereo[2], 0.000001f);
        assertEquals(-0.5f, stereo[3], 0.000001f);
    }

    @Test
    public void directAlacConversionCanDiscardPreSeekFrames() {
        int[] decoded = {1_000, -1_000, 8_000, -8_000};
        float[] stereo = new float[2];

        int frames = PlaybackController.unpackAlacPcmToStereo(
                decoded, 8, 2, 2, 1, stereo);

        assertEquals(1, frames);
        assertEquals(8_000 / 32_768f, stereo[0], 0.000001f);
        assertEquals(-8_000 / 32_768f, stereo[1], 0.000001f);
    }

    @Test
    public void highResolutionConverterKeepsMusicAndRejectsUltrasonicAliases() {
        final int inputRate = 192_000;
        final int outputRate = 48_000;
        final int inputFrames = 19_200;

        float[] music = stereoSine(inputRate, inputFrames, 1_000f, 0.5f);
        PlaybackController.PcmRateConverter musicConverter =
                new PlaybackController.PcmRateConverter(inputRate, outputRate);
        float[] musicOutput = new float[musicConverter.maximumOutputFrames(inputFrames) * 2];
        int musicFrames = musicConverter.process(music, inputFrames, musicOutput);
        assertEquals(4_800, musicFrames);
        double musicRms = rms(musicOutput, musicFrames, 100);
        assertTrue("Pass-band tone lost too much level", musicRms > 0.32 && musicRms < 0.38);

        float[] ultrasonic = stereoSine(inputRate, inputFrames, 70_000f, 0.5f);
        PlaybackController.PcmRateConverter ultrasonicConverter =
                new PlaybackController.PcmRateConverter(inputRate, outputRate);
        float[] ultrasonicOutput = new float[
                ultrasonicConverter.maximumOutputFrames(inputFrames) * 2];
        int ultrasonicFrames = ultrasonicConverter.process(
                ultrasonic, inputFrames, ultrasonicOutput);
        assertTrue("Ultrasonic content must not fold into the TPS-L2 band",
                rms(ultrasonicOutput, ultrasonicFrames, 100) < 0.025);
    }

    @Test
    public void fusedPacked24DecimatorIsSampleIdenticalToTheReferencePath() {
        final int inputRate = 192_000;
        final int outputRate = 48_000;
        final int inputFrames = 2_037;
        byte[] packed = new byte[inputFrames * 2 * 3];
        float[] unpacked = new float[inputFrames * 2];
        int state = 0x13579bdf;
        for (int sample = 0; sample < unpacked.length; sample++) {
            state = state * 1_664_525 + 1_013_904_223;
            int value = state >> 8;
            int offset = sample * 3;
            packed[offset] = (byte) value;
            packed[offset + 1] = (byte) (value >>> 8);
            packed[offset + 2] = (byte) (value >>> 16);
            unpacked[sample] = value / 8_388_608f;
        }

        PlaybackController.PcmRateConverter reference =
                new PlaybackController.PcmRateConverter(inputRate, outputRate);
        PlaybackController.PcmRateConverter fused =
                new PlaybackController.PcmRateConverter(inputRate, outputRate);
        float[] expected = new float[reference.maximumOutputFrames(inputFrames) * 2];
        float[] actual = new float[fused.maximumOutputFrames(inputFrames) * 2];

        int expectedFrames = reference.process(unpacked, inputFrames, expected);
        int actualFrames = fused.processPcm24(packed, 0, inputFrames, 2, actual);

        assertEquals(expectedFrames, actualFrames);
        assertArrayEquals(expected, actual, 0f);
    }

    @Test
    public void fusedPcm16DecimatorIsSampleIdenticalToTheReferencePath() {
        final int inputRate = 192_000;
        final int outputRate = 48_000;
        final int inputFrames = 4_099;
        short[] pcm = new short[inputFrames * 2];
        float[] unpacked = new float[inputFrames * 2];
        int state = 0x2468ace1;
        for (int sample = 0; sample < unpacked.length; sample++) {
            state = state * 1_103_515_245 + 12_345;
            pcm[sample] = (short) (state >>> 16);
            unpacked[sample] = pcm[sample] / 32_768f;
        }

        PlaybackController.PcmRateConverter reference =
                new PlaybackController.PcmRateConverter(inputRate, outputRate);
        PlaybackController.PcmRateConverter fused =
                new PlaybackController.PcmRateConverter(inputRate, outputRate);
        float[] expected = new float[reference.maximumOutputFrames(inputFrames) * 2];
        float[] actual = new float[fused.maximumOutputFrames(inputFrames) * 2];

        int expectedFrames = reference.process(unpacked, inputFrames, expected);
        int actualFrames = fused.processPcm16(pcm, 0, inputFrames, 2, actual);

        assertEquals(expectedFrames, actualFrames);
        assertArrayEquals(expected, actual, 0f);
    }

    private static float[] stereoSine(int sampleRate,
                                      int frameCount,
                                      float frequency,
                                      float amplitude) {
        float[] result = new float[frameCount * 2];
        for (int frame = 0; frame < frameCount; frame++) {
            float sample = (float) Math.sin(frame * Math.PI * 2.0 * frequency / sampleRate)
                    * amplitude;
            result[frame * 2] = sample;
            result[frame * 2 + 1] = sample;
        }
        return result;
    }

    private static double rms(float[] stereo, int frameCount, int skipFrames) {
        double sum = 0.0;
        int samples = 0;
        for (int frame = Math.min(skipFrames, frameCount); frame < frameCount; frame++) {
            float value = stereo[frame * 2];
            sum += value * value;
            samples++;
        }
        return Math.sqrt(sum / Math.max(1, samples));
    }
}
