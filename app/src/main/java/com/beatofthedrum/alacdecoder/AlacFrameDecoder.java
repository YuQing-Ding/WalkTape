package com.beatofthedrum.alacdecoder;

import java.util.Arrays;

/**
 * Small public bridge to jALD's frame decoder.
 *
 * <p>Android's MediaExtractor already understands modern M4A containers. Feeding its compressed
 * ALAC packets directly to the decoder is both faster and much more compatible than asking jALD's
 * old QuickTime parser to read the whole file.</p>
 */
public final class AlacFrameDecoder {
    private static final int ALAC_CONFIG_SIZE = 24;
    private static final int CONFIG_PREFIX_SIZE = 24;

    private final AlacFile decoder;
    private final int bitsPerSample;
    private final int channelCount;
    private final int sampleRate;
    private final int maximumSamplesPerFrame;
    private final int maximumOutputInts;
    private byte[] packetBuffer = new byte[0];

    public AlacFrameDecoder(byte[] codecSpecificData) {
        if (codecSpecificData == null || codecSpecificData.length < ALAC_CONFIG_SIZE) {
            throw new IllegalArgumentException("Missing 24-byte ALAC codec configuration");
        }

        // MediaExtractor exposes the ALACSpecificConfig itself. jALD's core expects that same
        // structure after the 24-byte pseudo atom prefix made by its legacy demuxer.
        int configStart = codecSpecificData.length - ALAC_CONFIG_SIZE;
        maximumSamplesPerFrame = readBigEndianInt(codecSpecificData, configStart);
        bitsPerSample = codecSpecificData[configStart + 5] & 0xff;
        channelCount = codecSpecificData[configStart + 9] & 0xff;
        sampleRate = readBigEndianInt(codecSpecificData, configStart + 20);
        if (maximumSamplesPerFrame <= 0 || maximumSamplesPerFrame > 65_536
                || (bitsPerSample != 16 && bitsPerSample != 24)
                || channelCount < 1 || channelCount > 2 || sampleRate <= 0) {
            throw new IllegalArgumentException("Unsupported ALAC configuration: "
                    + bitsPerSample + "-bit, " + channelCount + " channels, "
                    + sampleRate + " Hz");
        }

        int[] decoderInfo = new int[CONFIG_PREFIX_SIZE + ALAC_CONFIG_SIZE];
        for (int index = 0; index < ALAC_CONFIG_SIZE; index++) {
            decoderInfo[CONFIG_PREFIX_SIZE + index] = codecSpecificData[configStart + index] & 0xff;
        }
        decoder = AlacDecodeUtils.create_alac(bitsPerSample, channelCount);
        AlacDecodeUtils.alac_set_info(decoder, decoderInfo);

        // jALD represents 16-bit output as one signed sample per int, but represents 24-bit
        // output as three byte-valued ints per sample. Size each queued frame for the actual ALAC
        // configuration instead of retaining the historical 73,728-int worst-case allocation.
        int intsPerSample = bitsPerSample == 24 ? 3 : 1;
        maximumOutputInts = maximumSamplesPerFrame * channelCount * intsPerSample
                + (channelCount == 1 ? intsPerSample : 0);
    }

    public int getBitsPerSample() {
        return bitsPerSample;
    }

    public int getBytesPerSample() {
        return bitsPerSample / 8;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getMaximumSamplesPerFrame() {
        return maximumSamplesPerFrame;
    }

    public int getMaximumOutputInts() {
        return maximumOutputInts;
    }

    /** Decodes one complete MediaExtractor sample and returns the number of PCM bytes produced. */
    public int decode(byte[] packet, int packetSize, int[] output) {
        if (packet == null || packetSize <= 0 || packetSize > packet.length) {
            throw new IllegalArgumentException("Invalid ALAC packet");
        }
        if (output == null || output.length < maximumOutputInts) {
            throw new IllegalArgumentException("ALAC output buffer must contain at least "
                    + maximumOutputInts + " ints");
        }
        int paddedSize = packetSize + 3;
        if (packetBuffer.length < paddedSize) {
            packetBuffer = new byte[Math.max(paddedSize, packetBuffer.length * 2 + 4096)];
        }
        System.arraycopy(packet, 0, packetBuffer, 0, packetSize);
        Arrays.fill(packetBuffer, packetSize, packetSize + 3, (byte) 0);
        return AlacDecodeUtils.decode_frame(decoder, packetBuffer, output, maximumOutputInts);
    }

    private static int readBigEndianInt(byte[] source, int offset) {
        return ((source[offset] & 0xff) << 24)
                | ((source[offset + 1] & 0xff) << 16)
                | ((source[offset + 2] & 0xff) << 8)
                | (source[offset + 3] & 0xff);
    }
}
