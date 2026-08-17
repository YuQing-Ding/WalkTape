package com.yqdscott.walktape;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Reads enough of a file's own header to say what it is when Android's extractor will not.
 *
 * <p>{@code MediaExtractor.setDataSource} answers a file it cannot sniff with one sentence —
 * "Failed to instantiate extractor." — and nothing else. That sentence is true of a 24-bit WAV, an
 * RF64 recording, a FLAC behind an ID3 tag and a DSD file alike, so on its own it cannot tell
 * anybody which of those they are holding. This class reads the container's own header and says.
 * </p>
 *
 * <p>It deliberately parses only headers, never audio: enough to name the container, recover sample
 * rate, channel count and word length, and note the specific things the platform extractors are
 * known to turn down. Everything it reports comes from bytes in the file rather than from the
 * platform's opinion of them.</p>
 */
final class AudioContainerProbe {

    /** Enough to walk past a large LIST/id3 chunk in a WAV and still reach {@code fmt }. */
    private static final int HEADER_BYTES = 64 * 1024;

    enum Container {
        RIFF_WAVE("RIFF/WAVE"),
        RF64("RF64"),
        WAVE64("Wave64"),
        FLAC("FLAC"),
        AIFF("AIFF/AIFF-C"),
        DSF("DSF (DSD)"),
        DFF("DFF/DSDIFF (DSD)"),
        UNKNOWN("unrecognised");

        final String label;

        Container(String label) {
            this.label = label;
        }
    }

    static final class Result {
        final Container container;
        /** Bytes of tag sitting in front of the real magic, which some sniffers will not skip. */
        final long leadingTagBytes;
        final int sampleRate;
        final int channels;
        final int bitsPerSample;
        /** "PCM", "IEEE float", "FLAC", "DSD", or empty when the header did not say. */
        final String sampleFormat;
        final int flacMaximumBlockSize;

        Result(Container container,
               long leadingTagBytes,
               int sampleRate,
               int channels,
               int bitsPerSample,
               String sampleFormat,
               int flacMaximumBlockSize) {
            this.container = container;
            this.leadingTagBytes = leadingTagBytes;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.sampleFormat = sampleFormat;
            this.flacMaximumBlockSize = flacMaximumBlockSize;
        }

        boolean isDsd() {
            return container == Container.DSF || container == Container.DFF;
        }

        /** What the header actually says, for a person reading an error message. */
        String describe() {
            StringBuilder text = new StringBuilder(container.label);
            if (leadingTagBytes > 0) {
                text.append("（前置标签 ").append(leadingTagBytes).append(" 字节）");
            }
            if (sampleRate > 0) {
                text.append(' ').append(formatRate(sampleRate));
            }
            if (bitsPerSample > 0) {
                text.append(' ').append(bitsPerSample).append(" bit");
            }
            if (!sampleFormat.isEmpty()) {
                text.append(' ').append(sampleFormat);
            }
            if (channels > 0) {
                text.append(' ').append(channels).append(" ch");
            }
            return text.toString();
        }

        /**
         * The most likely reason the platform refused it, or empty when nothing stands out.
         *
         * <p>Each of these is a documented limit of the AOSP extractors rather than a guess about
         * this particular file, so the wording says what was found and lets the reader judge.</p>
         */
        String likelyReason() {
            if (isDsd()) {
                return "DSD 容器，Android 的解封装器完全不认识";
            }
            if (container == Container.RF64) {
                return "RF64（大于 4 GB 的 WAV 变体），AOSP 只嗅探 RIFF";
            }
            if (container == Container.WAVE64) {
                return "Wave64，AOSP 没有对应的解封装器";
            }
            if (container == Container.AIFF) {
                return "AIFF，AOSP 根本没有这个容器的解封装器";
            }
            if (leadingTagBytes > 0) {
                return "文件头前面有 " + leadingTagBytes + " 字节标签，嗅探器要求魔数在偏移 0";
            }
            if (container == Container.FLAC && flacMaximumBlockSize > 4_096) {
                return "FLAC 最大块 " + flacMaximumBlockSize + " 采样，超出解封装器的 4096 上限";
            }
            if (bitsPerSample > 24) {
                return bitsPerSample + " bit 字长超出平台 PCM 支持";
            }
            if (bitsPerSample == 24 || bitsPerSample == 32) {
                return bitsPerSample + " bit 是平台解封装器最常见的拒绝原因";
            }
            if (sampleRate > 192_000) {
                return formatRate(sampleRate) + " 超出平台支持";
            }
            if (container == Container.UNKNOWN) {
                return "文件头不是任何已知的音频容器";
            }
            return "";
        }

        private static String formatRate(int hertz) {
            if (hertz % 1_000 == 0) {
                return (hertz / 1_000) + " kHz";
            }
            return String.format(Locale.US, "%.1f kHz", hertz / 1_000.0);
        }
    }

    private AudioContainerProbe() {
    }

    static Result probe(Context context, Uri uri) {
        byte[] header = new byte[HEADER_BYTES];
        int filled = 0;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                return unknown();
            }
            while (filled < header.length) {
                int read = stream.read(header, filled, header.length - filled);
                if (read < 0) {
                    break;
                }
                filled += read;
            }
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            return unknown();
        }
        return probe(header, filled);
    }

    private static Result unknown() {
        return new Result(Container.UNKNOWN, 0, 0, 0, 0, "", 0);
    }

    /** Package-private so the parsing can be tested without a device or a file. */
    static Result probe(byte[] header, int length) {
        if (header == null || length < 16) {
            return unknown();
        }
        long offset = skipLeadingTag(header, length);
        if (offset >= length - 16) {
            return unknown();
        }
        int base = (int) offset;

        if (matches(header, base, "RIFF") && matches(header, base + 8, "WAVE")) {
            return parseRiff(header, length, base, Container.RIFF_WAVE, offset);
        }
        if (matches(header, base, "RF64") && matches(header, base + 8, "WAVE")) {
            return parseRiff(header, length, base, Container.RF64, offset);
        }
        if (matches(header, base, "riff") && (header[base + 4] & 0xff) == 0x2e
                && (header[base + 5] & 0xff) == 0x91) {
            // Wave64's "riff" GUID. Its chunk layout differs enough that the parameters are not
            // recovered here; naming the container is what matters.
            return new Result(Container.WAVE64, offset, 0, 0, 0, "PCM", 0);
        }
        if (matches(header, base, "fLaC")) {
            return parseFlac(header, length, base, offset);
        }
        if (matches(header, base, "DSD ")) {
            return parseDsf(header, length, base, offset);
        }
        if (matches(header, base, "FRM8")) {
            return new Result(Container.DFF, offset, 0, 0, 1, "DSD", 0);
        }
        if (matches(header, base, "FORM")
                && (matches(header, base + 8, "AIFF") || matches(header, base + 8, "AIFC"))) {
            AiffStreamReader.Format aiff = AiffStreamReader.parse(header, length);
            if (aiff == null) {
                return new Result(Container.AIFF, offset, 0, 0, 0, "PCM", 0);
            }
            return new Result(Container.AIFF, offset, aiff.sampleRate, aiff.channels,
                    aiff.bitsPerSample, aiff.unsupportedCompression.isEmpty()
                            ? "PCM" : aiff.unsupportedCompression, 0);
        }
        return unknown();
    }

    /**
     * Length of an ID3v2 tag sitting in front of the audio, or zero.
     *
     * <p>Worth doing because a FLAC with an ID3 tag glued to the front is still a perfectly good
     * FLAC, and the only thing wrong with it is that the sniffer looks for {@code fLaC} at offset
     * zero and finds {@code ID3} instead.</p>
     */
    private static long skipLeadingTag(byte[] header, int length) {
        if (length < 10 || !matches(header, 0, "ID3")) {
            return 0;
        }
        // Four seven-bit bytes, most significant first.
        long size = 0;
        for (int index = 6; index < 10; index++) {
            size = (size << 7) | (header[index] & 0x7f);
        }
        long total = size + 10;
        if ((header[5] & 0x10) != 0) {
            total += 10; // footer
        }
        return total;
    }

    private static Result parseRiff(byte[] header, int length, int base, Container container,
                                    long tagBytes) {
        int cursor = base + 12;
        while (cursor + 8 <= length) {
            int chunkSize = littleInt(header, cursor + 4);
            int body = cursor + 8;
            if (matches(header, cursor, "fmt ") && body + 16 <= length) {
                int formatTag = littleShort(header, body);
                int channels = littleShort(header, body + 2);
                int sampleRate = littleInt(header, body + 4);
                int bits = littleShort(header, body + 14);
                if (formatTag == 0xFFFE && body + 26 <= length) {
                    // WAVE_FORMAT_EXTENSIBLE carries the real tag in the first two GUID bytes.
                    formatTag = littleShort(header, body + 24);
                }
                String format = formatTag == 3 ? "IEEE float"
                        : formatTag == 1 ? "PCM"
                        : "格式标签 0x" + Integer.toHexString(formatTag);
                return new Result(container, tagBytes, sampleRate, channels, bits, format, 0);
            }
            if (chunkSize < 0) {
                break;
            }
            cursor = body + chunkSize + (chunkSize & 1);
            if (cursor <= body) {
                break;
            }
        }
        return new Result(container, tagBytes, 0, 0, 0, "", 0);
    }

    /** STREAMINFO is always the first metadata block, immediately after the magic. */
    private static Result parseFlac(byte[] header, int length, int base, long tagBytes) {
        int block = base + 4;
        if (block + 4 + 34 > length) {
            return new Result(Container.FLAC, tagBytes, 0, 0, 0, "FLAC", 0);
        }
        int type = header[block] & 0x7f;
        if (type != 0) {
            return new Result(Container.FLAC, tagBytes, 0, 0, 0, "FLAC", 0);
        }
        int info = block + 4;
        int maximumBlockSize = ((header[info + 2] & 0xff) << 8) | (header[info + 3] & 0xff);
        // 20 bits sample rate, 3 bits channels-1, 5 bits bits-per-sample-1.
        int packed = ((header[info + 10] & 0xff) << 16)
                | ((header[info + 11] & 0xff) << 8)
                | (header[info + 12] & 0xff);
        int sampleRate = packed >>> 4;
        int channels = ((packed >>> 1) & 0x07) + 1;
        int bits = (((packed & 0x01) << 4)
                | ((header[info + 13] & 0xff) >>> 4)) + 1;
        return new Result(Container.FLAC, tagBytes, sampleRate, channels, bits, "FLAC",
                maximumBlockSize);
    }

    private static Result parseDsf(byte[] header, int length, int base, long tagBytes) {
        // DSD chunk is 28 bytes, then "fmt " with the parameters at fixed offsets.
        int fmt = base + 28;
        if (fmt + 52 > length || !matches(header, fmt, "fmt ")) {
            return new Result(Container.DSF, tagBytes, 0, 0, 1, "DSD", 0);
        }
        int channels = littleInt(header, fmt + 20);
        int sampleRate = littleInt(header, fmt + 24);
        int bits = littleInt(header, fmt + 28);
        return new Result(Container.DSF, tagBytes, sampleRate, channels, bits, "DSD", 0);
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

    private static int littleShort(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int littleInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }
}
