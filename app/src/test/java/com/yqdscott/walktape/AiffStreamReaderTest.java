package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Header parsing checked against a real file's bytes rather than against a synthesised ideal.
 *
 * <p>{@link #theHeaderFromAnActualFileParses} carries the first 54 bytes read off one of the
 * soundtrack AIFFs that would not play. Everything else here is synthesised to cover the variants
 * that file does not exercise.</p>
 */
public class AiffStreamReaderTest {

    /**
     * Read with {@code dd bs=1 count=64} from an AIFF the platform refused to open.
     *
     * <p>FORM/AIFF, a COMM giving 2 channels, 14202552 frames, 16 bits and 44100 Hz as an 80-bit
     * extended float, then SSND with both offset words zero.</p>
     */
    private static final int[] REAL_HEADER = {
            0x46, 0x4f, 0x52, 0x4d, 0x03, 0x63, 0x5d, 0xa4, 0x41, 0x49, 0x46, 0x46,
            0x43, 0x4f, 0x4d, 0x4d, 0x00, 0x00, 0x00, 0x12, 0x00, 0x02, 0x00, 0xd8,
            0xb6, 0xb8, 0x00, 0x10, 0x40, 0x0e, 0xac, 0x44, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x53, 0x53, 0x4e, 0x44, 0x03, 0x62, 0xda, 0xe8, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    @Test
    public void theHeaderFromAnActualFileParses() {
        byte[] header = bytes(REAL_HEADER);
        AiffStreamReader.Format format = AiffStreamReader.parse(header, header.length);

        assertEquals(44_100, format.sampleRate);
        assertEquals(2, format.channels);
        assertEquals(16, format.bitsPerSample);
        assertTrue("plain AIFF is big-endian", format.bigEndian);
        assertEquals(14_202_552L, format.frameCount);
        assertEquals("data starts after SSND's two offset words", 54L, format.dataOffset);
        assertEquals(4, format.bytesPerFrame());
        assertTrue(format.isPlayable());
        assertEquals("", format.unsupportedCompression);

        // 14202552 frames at 44100 is a little over five minutes.
        assertEquals(322_053_333L, format.durationUs(), 2_000L);
    }

    /** The sample rate is an 80-bit extended float, which is the one fiddly field. */
    @Test
    public void theEightyBitSampleRateIsDecodedForEveryCommonRate() {
        assertEquals(44_100, parseRate(0x400e, 0xac44000000000000L));
        assertEquals(48_000, parseRate(0x400e, 0xbb80000000000000L));
        assertEquals(88_200, parseRate(0x400f, 0xac44000000000000L));
        assertEquals(96_000, parseRate(0x400f, 0xbb80000000000000L));
        assertEquals(192_000, parseRate(0x4010, 0xbb80000000000000L));
        assertEquals(8_000, parseRate(0x400b, 0xfa00000000000000L));
    }

    @Test
    public void aiffCsSowtIsFlaggedAsLittleEndianRatherThanRejected() {
        AiffStreamReader.Format format = synthesised("AIFC", "sowt", 2, 16, 0x400e,
                0xac44000000000000L);
        assertFalse(format.bigEndian);
        assertTrue(format.isPlayable());
    }

    @Test
    public void aCompressedAiffCIsReportedRatherThanPlayedAsNoise() {
        AiffStreamReader.Format format = synthesised("AIFC", "ima4", 2, 16, 0x400e,
                0xac44000000000000L);
        assertEquals("ima4", format.unsupportedCompression);
        assertFalse("a compressed stream must not be handed to the PCM path",
                format.isPlayable());
    }

    @Test
    public void somethingThatIsNotAiffIsRejectedOutright() {
        assertNull(AiffStreamReader.parse(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0,
                'W', 'A', 'V', 'E'}, 12));
        assertNull(AiffStreamReader.parse(new byte[4], 4));
        assertNull(AiffStreamReader.parse(null, 0));
    }

    /** Sixteen-bit big-endian is a byte swap, and nothing may be lost doing it. */
    @Test
    public void sixteenBitSamplesAreSwappedWithoutLoss() {
        byte[] source = {0x12, 0x34, (byte) 0xff, (byte) 0xfe, (byte) 0x80, 0x00};
        byte[] destination = new byte[6];
        int written = AiffStreamReader.toLittleEndian16(source, 6, 16, true, destination);
        assertEquals(6, written);
        assertEquals(0x1234, (short) ((destination[1] & 0xff) << 8 | (destination[0] & 0xff)));
        assertEquals(-2, (short) ((destination[3] & 0xff) << 8 | (destination[2] & 0xff)));
        assertEquals(Short.MIN_VALUE,
                (short) ((destination[5] & 0xff) << 8 | (destination[4] & 0xff)));
    }

    @Test
    public void littleEndianSowtSamplesPassThroughUnchanged() {
        byte[] source = {0x34, 0x12, (byte) 0xfe, (byte) 0xff};
        byte[] destination = new byte[4];
        AiffStreamReader.toLittleEndian16(source, 4, 16, false, destination);
        assertEquals(0x1234, (short) ((destination[1] & 0xff) << 8 | (destination[0] & 0xff)));
        assertEquals(-2, (short) ((destination[3] & 0xff) << 8 | (destination[2] & 0xff)));
    }

    /** Longer words keep their top 16 bits, which is where the audible signal lives. */
    @Test
    public void twentyFourBitSamplesKeepTheirMostSignificantBits() {
        byte[] source = {0x12, 0x34, 0x56, (byte) 0x80, 0x00, 0x01};
        byte[] destination = new byte[4];
        int written = AiffStreamReader.toLittleEndian16(source, 6, 24, true, destination);
        assertEquals("three bytes in, two out", 4, written);
        assertEquals(0x1234, (short) ((destination[1] & 0xff) << 8 | (destination[0] & 0xff)));
        assertEquals(Short.MIN_VALUE,
                (short) ((destination[3] & 0xff) << 8 | (destination[2] & 0xff)));
    }

    /** AIFF's 8-bit is signed, where WAV's is not; getting this backwards inverts the waveform. */
    @Test
    public void eightBitSamplesAreTreatedAsSigned() {
        byte[] source = {0x7f, (byte) 0x80, 0x00};
        byte[] destination = new byte[6];
        AiffStreamReader.toLittleEndian16(source, 3, 8, true, destination);
        assertEquals(0x7f00, (short) ((destination[1] & 0xff) << 8 | (destination[0] & 0xff)));
        assertEquals(-32_768,
                (short) ((destination[3] & 0xff) << 8 | (destination[2] & 0xff)));
        assertEquals(0, (short) ((destination[5] & 0xff) << 8 | (destination[4] & 0xff)));
    }

    /** The probe has to name AIFF too, or the error message goes back to being useless. */
    @Test
    public void theContainerProbeNamesAiffAndItsParameters() {
        byte[] header = bytes(REAL_HEADER);
        AudioContainerProbe.Result probed = AudioContainerProbe.probe(header, header.length);
        assertEquals(AudioContainerProbe.Container.AIFF, probed.container);
        assertEquals(44_100, probed.sampleRate);
        assertEquals(16, probed.bitsPerSample);
        assertEquals(2, probed.channels);
        assertTrue(probed.likelyReason().contains("AIFF"));
        assertFalse(probed.isDsd());
    }

    // ---- helpers

    private static int parseRate(int exponent, long mantissa) {
        return synthesised("AIFF", null, 2, 16, exponent, mantissa).sampleRate;
    }

    private static AiffStreamReader.Format synthesised(String form, String compression,
                                                       int channels, int bits,
                                                       int exponent, long mantissa) {
        boolean aifc = compression != null;
        int commSize = aifc ? 22 : 18;
        byte[] header = new byte[12 + 8 + commSize + 16];
        put(header, 0, "FORM");
        put(header, 8, form);
        put(header, 12, "COMM");
        header[19] = (byte) commSize;
        header[20] = (byte) (channels >> 8);
        header[21] = (byte) channels;
        // frame count stays zero; duration falls back to the data size
        header[26] = (byte) (bits >> 8);
        header[27] = (byte) bits;
        header[28] = (byte) (exponent >> 8);
        header[29] = (byte) exponent;
        for (int index = 0; index < 8; index++) {
            header[30 + index] = (byte) (mantissa >>> (56 - index * 8));
        }
        int cursor = 20 + commSize;
        if (aifc) {
            put(header, 38, compression);
        }
        put(header, cursor, "SSND");
        header[cursor + 7] = 16;
        return AiffStreamReader.parse(header, header.length);
    }

    private static void put(byte[] target, int offset, String text) {
        for (int index = 0; index < text.length(); index++) {
            target[offset + index] = (byte) text.charAt(index);
        }
    }

    private static byte[] bytes(int[] values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
