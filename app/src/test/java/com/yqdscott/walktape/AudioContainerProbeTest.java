package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

/**
 * Header parsing for the containers Android's own extractors refuse to open.
 *
 * <p>Built from synthesised headers rather than from files, so the cases that matter — 24-bit,
 * RF64, a tag in front of the magic, DSD — can all be exercised without shipping test audio.</p>
 */
public class AudioContainerProbeTest {

    @Test
    public void plainSixteenBitWaveIsReadBackExactly() {
        AudioContainerProbe.Result result = probe(wave("RIFF", 1, 2, 44_100, 16));
        assertEquals(AudioContainerProbe.Container.RIFF_WAVE, result.container);
        assertEquals(44_100, result.sampleRate);
        assertEquals(2, result.channels);
        assertEquals(16, result.bitsPerSample);
        assertEquals("PCM", result.sampleFormat);
        assertEquals("nothing about this file is unusual", "", result.likelyReason());
    }

    @Test
    public void twentyFourBitWaveIsNamedAsTheLikelyCause() {
        AudioContainerProbe.Result result = probe(wave("RIFF", 1, 2, 96_000, 24));
        assertEquals(24, result.bitsPerSample);
        assertEquals(96_000, result.sampleRate);
        assertTrue(result.describe().contains("96 kHz"));
        assertTrue(result.describe().contains("24 bit"));
        assertTrue(result.likelyReason().contains("24 bit"));
    }

    @Test
    public void rf64IsRecognisedRatherThanCalledUnknown() {
        AudioContainerProbe.Result result = probe(wave("RF64", 1, 2, 48_000, 24));
        assertEquals(AudioContainerProbe.Container.RF64, result.container);
        assertEquals(48_000, result.sampleRate);
        assertTrue(result.likelyReason().contains("RF64"));
    }

    /** Extensible WAV hides the real format tag in the sub-format GUID. */
    @Test
    public void extensibleWaveReportsItsSubFormatRatherThanFffe() {
        AudioContainerProbe.Result result = probe(wave("RIFF", 0xFFFE, 2, 192_000, 32));
        assertEquals(192_000, result.sampleRate);
        assertEquals(32, result.bitsPerSample);
        assertEquals("IEEE float", result.sampleFormat);
    }

    /** A big LIST chunk before {@code fmt } must not stop the parse. */
    @Test
    public void aLeadingListChunkIsWalkedPast() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("RIFF"));
        out.write(littleInt(0));
        out.write(ascii("WAVE"));
        out.write(ascii("LIST"));
        out.write(littleInt(2_048));
        out.write(new byte[2_048]);
        writeFmt(out, 1, 2, 88_200, 24);
        AudioContainerProbe.Result result = probe(out.toByteArray());
        assertEquals(88_200, result.sampleRate);
        assertEquals(24, result.bitsPerSample);
        assertTrue(result.describe().contains("88.2 kHz"));
    }

    @Test
    public void flacStreamInfoIsUnpackedFromItsBitFields() {
        AudioContainerProbe.Result result = probe(flac(0, 4_096, 96_000, 2, 24));
        assertEquals(AudioContainerProbe.Container.FLAC, result.container);
        assertEquals(96_000, result.sampleRate);
        assertEquals(2, result.channels);
        assertEquals(24, result.bitsPerSample);
        assertEquals(4_096, result.flacMaximumBlockSize);
        assertEquals(0, result.leadingTagBytes);
    }

    @Test
    public void anOversizedFlacBlockIsReportedAheadOfTheWordLength() {
        AudioContainerProbe.Result result = probe(flac(0, 16_384, 44_100, 2, 16));
        assertEquals(16_384, result.flacMaximumBlockSize);
        assertTrue(result.likelyReason().contains("16384"));
    }

    /**
     * A FLAC behind an ID3 tag is a perfectly good FLAC that the sniffer cannot see.
     *
     * <p>The sniffer wants {@code fLaC} at offset zero. This is the case where the file is fine and
     * the message must not blame the file's contents.</p>
     */
    @Test
    public void flacBehindAnId3TagIsStillRecognisedAsFlac() {
        int tag = 10 + 1_234;
        AudioContainerProbe.Result result = probe(flac(1_234, 4_096, 44_100, 2, 16));
        assertEquals(AudioContainerProbe.Container.FLAC, result.container);
        assertEquals(tag, result.leadingTagBytes);
        assertEquals(44_100, result.sampleRate);
        assertTrue("the reason must point at the tag, not at the audio: " + result.likelyReason(),
                result.likelyReason().contains("标签")
                        && result.likelyReason().contains(String.valueOf(tag)));
        assertTrue(result.describe().contains(String.valueOf(tag)));
    }

    @Test
    public void dsdContainersAreNamedSoTheMessageCanSaySoOutright() {
        AudioContainerProbe.Result dsf = probe(dsf(2, 2_822_400));
        assertEquals(AudioContainerProbe.Container.DSF, dsf.container);
        assertTrue(dsf.isDsd());
        assertEquals(2_822_400, dsf.sampleRate);
        assertEquals(2, dsf.channels);
        assertTrue(dsf.likelyReason().contains("DSD"));

        byte[] dff = new byte[64];
        System.arraycopy(ascii("FRM8"), 0, dff, 0, 4);
        AudioContainerProbe.Result result = probe(dff);
        assertEquals(AudioContainerProbe.Container.DFF, result.container);
        assertTrue(result.isDsd());
    }

    @Test
    public void somethingThatIsNotAudioIsReportedAsUnknownRatherThanGuessed() {
        byte[] rubbish = new byte[512];
        for (int index = 0; index < rubbish.length; index++) {
            rubbish[index] = (byte) (index * 7);
        }
        AudioContainerProbe.Result result = probe(rubbish);
        assertEquals(AudioContainerProbe.Container.UNKNOWN, result.container);
        assertFalse(result.isDsd());

        // Too short to say anything at all.
        assertEquals(AudioContainerProbe.Container.UNKNOWN,
                AudioContainerProbe.probe(new byte[4], 4).container);
        assertEquals(AudioContainerProbe.Container.UNKNOWN,
                AudioContainerProbe.probe(null, 0).container);
    }

    // ---- synthesised headers

    private static AudioContainerProbe.Result probe(byte[] header) {
        return AudioContainerProbe.probe(header, header.length);
    }

    private static byte[] wave(String magic, int formatTag, int channels, int rate, int bits) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(ascii(magic));
            out.write(littleInt(0));
            out.write(ascii("WAVE"));
            writeFmt(out, formatTag, channels, rate, bits);
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void writeFmt(ByteArrayOutputStream out, int formatTag, int channels,
                                 int rate, int bits) throws IOException {
        boolean extensible = formatTag == 0xFFFE;
        int size = extensible ? 40 : 16;
        out.write(ascii("fmt "));
        out.write(littleInt(size));
        out.write(littleShort(formatTag));
        out.write(littleShort(channels));
        out.write(littleInt(rate));
        out.write(littleInt(rate * channels * bits / 8));
        out.write(littleShort(channels * bits / 8));
        out.write(littleShort(bits));
        if (extensible) {
            out.write(littleShort(22));      // cbSize
            out.write(littleShort(bits));    // valid bits
            out.write(littleInt(3));         // channel mask
            out.write(littleShort(3));       // sub-format: IEEE float
            out.write(new byte[14]);         // rest of the GUID
        }
        out.write(ascii("data"));
        out.write(littleInt(0));
    }

    private static byte[] flac(int tagPayload, int maximumBlock, int rate, int channels,
                               int bits) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (tagPayload > 0) {
                out.write(ascii("ID3"));
                out.write(new byte[]{4, 0, 0});
                // Four seven-bit bytes, most significant first.
                out.write(new byte[]{
                        (byte) ((tagPayload >> 21) & 0x7f),
                        (byte) ((tagPayload >> 14) & 0x7f),
                        (byte) ((tagPayload >> 7) & 0x7f),
                        (byte) (tagPayload & 0x7f)});
                out.write(new byte[tagPayload]);
            }
            out.write(ascii("fLaC"));
            out.write(new byte[]{0, 0, 0, 34});           // STREAMINFO, 34 bytes
            out.write(littleShortBig(4_096));             // minimum block
            out.write(littleShortBig(maximumBlock));      // maximum block
            out.write(new byte[6]);                       // frame sizes
            long packed = ((long) rate << 44)
                    | ((long) (channels - 1) << 41)
                    | ((long) (bits - 1) << 36);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((packed >>> shift) & 0xff));
            }
            out.write(new byte[16]);                      // MD5
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] dsf(int channels, int rate) {
        byte[] header = new byte[128];
        System.arraycopy(ascii("DSD "), 0, header, 0, 4);
        System.arraycopy(ascii("fmt "), 0, header, 28, 4);
        System.arraycopy(littleInt(channels), 0, header, 28 + 20, 4);
        System.arraycopy(littleInt(rate), 0, header, 28 + 24, 4);
        System.arraycopy(littleInt(1), 0, header, 28 + 28, 4);
        return header;
    }

    private static byte[] ascii(String text) {
        byte[] bytes = new byte[text.length()];
        for (int index = 0; index < text.length(); index++) {
            bytes[index] = (byte) text.charAt(index);
        }
        return bytes;
    }

    private static byte[] littleShort(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8)};
    }

    private static byte[] littleShortBig(int value) {
        return new byte[]{(byte) (value >> 8), (byte) value};
    }

    private static byte[] littleInt(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8),
                (byte) (value >> 16), (byte) (value >> 24)};
    }
}
