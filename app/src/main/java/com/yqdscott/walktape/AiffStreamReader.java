package com.yqdscott.walktape;

/**
 * AIFF and AIFF-C header parsing, because Android has no extractor for either.
 *
 * <p>This is not a limitation that can be worked around by asking the platform differently: AOSP
 * ships extractors for MP4, MP3, FLAC, Ogg, WAV, Matroska, MPEG-2 and AMR, and nothing for AIFF at
 * all. Apple's uncompressed format simply cannot be opened, whatever it contains — the files this
 * was written against are ordinary 44.1 kHz 16-bit stereo.</p>
 *
 * <p>The format itself is straightforward and old: an IFF {@code FORM} wrapper, a {@code COMM}
 * chunk giving channels, frame count, word length and the sample rate as an 80-bit IEEE extended
 * float, and an {@code SSND} chunk holding big-endian PCM after two offset words. AIFF-C adds a
 * four-character compression type, of which only the uncompressed ones are handled here; anything
 * else is reported rather than silently played as noise.</p>
 */
final class AiffStreamReader {

    /** Everything needed to play the file, all of it read out of the header. */
    static final class Format {
        final int sampleRate;
        final int channels;
        final int bitsPerSample;
        /** AIFF is big-endian; AIFF-C's {@code sowt} is the little-endian variant. */
        final boolean bigEndian;
        final long frameCount;
        /** Byte offset of the first sample, past SSND's offset and blockSize words. */
        final long dataOffset;
        final long dataBytes;
        /** Empty when the stream is plain PCM, otherwise the codec this class will not decode. */
        final String unsupportedCompression;

        Format(int sampleRate, int channels, int bitsPerSample, boolean bigEndian,
               long frameCount, long dataOffset, long dataBytes,
               String unsupportedCompression) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.bigEndian = bigEndian;
            this.frameCount = frameCount;
            this.dataOffset = dataOffset;
            this.dataBytes = dataBytes;
            this.unsupportedCompression = unsupportedCompression;
        }

        boolean isPlayable() {
            return unsupportedCompression.isEmpty()
                    && sampleRate > 0 && channels > 0 && channels <= 8
                    && (bitsPerSample == 8 || bitsPerSample == 16
                        || bitsPerSample == 24 || bitsPerSample == 32)
                    && dataOffset > 0;
        }

        int bytesPerFrame() {
            return channels * (bitsPerSample / 8);
        }

        long durationUs() {
            if (sampleRate <= 0) {
                return 0L;
            }
            long frames = frameCount > 0 ? frameCount
                    : (bytesPerFrame() > 0 ? dataBytes / bytesPerFrame() : 0L);
            return frames * 1_000_000L / sampleRate;
        }
    }

    private AiffStreamReader() {
    }

    /**
     * Parses the chunks in a header buffer. Returns null when this is not an AIFF at all.
     *
     * <p>Pure so it can be tested without a file: everything it needs is in the first few hundred
     * bytes, and the caller only has to have read that far.</p>
     */
    static Format parse(byte[] header, int length) {
        if (header == null || length < 12
                || !matches(header, 0, "FORM")
                || !(matches(header, 8, "AIFF") || matches(header, 8, "AIFC"))) {
            return null;
        }
        int sampleRate = 0;
        int channels = 0;
        int bits = 0;
        boolean bigEndian = true;
        long frameCount = 0;
        long dataOffset = 0;
        long dataBytes = 0;
        String unsupported = "";

        int cursor = 12;
        while (cursor + 8 <= length) {
            long chunkSize = bigInt(header, cursor + 4) & 0xffffffffL;
            int body = cursor + 8;
            if (matches(header, cursor, "COMM") && body + 18 <= length) {
                channels = bigShort(header, body);
                frameCount = bigInt(header, body + 2) & 0xffffffffL;
                bits = bigShort(header, body + 6);
                sampleRate = (int) Math.round(extendedFloat(header, body + 8));
                if (chunkSize >= 22 && body + 22 <= length) {
                    String compression = fourCharacters(header, body + 18);
                    // NONE and sowt are both uncompressed; sowt only reverses the byte order.
                    if ("sowt".equals(compression)) {
                        bigEndian = false;
                    } else if (!"NONE".equals(compression) && !"twos".equals(compression)
                            && !"in24".equals(compression) && !"in32".equals(compression)) {
                        unsupported = compression;
                    }
                }
            } else if (matches(header, cursor, "SSND") && body + 8 <= length) {
                long ssndOffset = bigInt(header, body) & 0xffffffffL;
                dataOffset = body + 8 + ssndOffset;
                dataBytes = Math.max(0L, chunkSize - 8 - ssndOffset);
            }
            if (chunkSize <= 0) {
                break;
            }
            long next = body + chunkSize + (chunkSize & 1L);
            if (next <= cursor || next > Integer.MAX_VALUE) {
                break;
            }
            cursor = (int) next;
        }
        if (sampleRate <= 0 && channels <= 0 && dataOffset <= 0) {
            return null;
        }
        return new Format(sampleRate, channels, bits, bigEndian, frameCount, dataOffset,
                dataBytes, unsupported);
    }

    /**
     * Rewrites one buffer of AIFF samples into little-endian 16-bit in place.
     *
     * <p>Sixteen-bit input is a byte swap. Longer words are truncated to their top 16 bits, which
     * is a deliberate choice rather than laziness: everything downstream runs through a modelled
     * cassette whose own particle noise sits around -50 dB, so the discarded bits are some 40 dB
     * below a noise floor this app goes out of its way to reproduce.</p>
     *
     * @return the number of bytes written to {@code destination}
     */
    static int toLittleEndian16(byte[] source, int sourceLength, int bitsPerSample,
                                boolean bigEndian, byte[] destination) {
        int bytesPerSample = bitsPerSample / 8;
        if (bytesPerSample <= 0) {
            return 0;
        }
        int samples = sourceLength / bytesPerSample;
        int written = 0;
        for (int index = 0; index < samples; index++) {
            int at = index * bytesPerSample;
            int value;
            if (bytesPerSample == 1) {
                // AIFF 8-bit is signed, unlike WAV's unsigned.
                value = source[at] << 8;
            } else if (bigEndian) {
                value = ((source[at] & 0xff) << 8) | (source[at + 1] & 0xff);
            } else {
                value = ((source[at + bytesPerSample - 1] & 0xff) << 8)
                        | (source[at + bytesPerSample - 2] & 0xff);
            }
            destination[written++] = (byte) value;
            destination[written++] = (byte) (value >> 8);
        }
        return written;
    }

    private static String fourCharacters(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return "";
        }
        StringBuilder text = new StringBuilder(4);
        for (int index = 0; index < 4; index++) {
            text.append((char) (data[offset + index] & 0xff));
        }
        return text.toString();
    }

    /** The 80-bit IEEE 754 extended float AIFF stores its sample rate in. */
    private static double extendedFloat(byte[] data, int offset) {
        int exponent = bigShort(data, offset);
        boolean negative = (exponent & 0x8000) != 0;
        exponent &= 0x7fff;
        long mantissa = 0;
        for (int index = 0; index < 8; index++) {
            mantissa = (mantissa << 8) | (data[offset + 2 + index] & 0xffL);
        }
        if (exponent == 0 && mantissa == 0) {
            return 0.0;
        }
        // The mantissa's top bit is the explicit integer bit and is set for every normal value,
        // so a signed long reads it as negative. Widen it as unsigned before scaling, or 44100 Hz
        // comes back as -21436.
        double unsigned = (mantissa >>> 1) * 2.0 + (mantissa & 1L);
        double value = unsigned * Math.pow(2.0, exponent - 16_383 - 63);
        return negative ? -value : value;
    }

    private static boolean matches(byte[] data, int offset, String magic) {
        if (offset < 0 || offset + magic.length() > data.length) {
            return false;
        }
        for (int index = 0; index < magic.length(); index++) {
            if ((data[offset + index] & 0xff) != magic.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static int bigShort(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static int bigInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }
}
