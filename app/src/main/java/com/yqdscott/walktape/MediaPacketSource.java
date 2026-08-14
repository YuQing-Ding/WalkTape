package com.yqdscott.walktape;

import android.media.MediaExtractor;
import android.os.Process;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Reads demuxed packets on a dedicated thread so storage latency never reaches the audio reserve.
 *
 * <p>{@link MediaExtractor} reads a {@code content://} URI through the media provider, which on
 * modern Android goes via FUSE. Those reads are individually fast but occasionally stall for tens
 * or hundreds of milliseconds, and the system lowers their priority once the app leaves the
 * foreground. High-resolution lossless material multiplies the byte rate and therefore the number
 * of opportunities to stall.</p>
 *
 * <p>Performing those reads inline with decoding and tape rendering makes every stall come
 * straight out of the PCM reserve, and a drained reserve never refills on its own. This class
 * moves them onto their own thread with a bounded packet queue, so a stall is absorbed by the
 * queue while the renderer keeps producing. The extractor is touched by the reader thread only;
 * seeks are posted and applied there, which keeps its single-threaded contract intact.</p>
 */
final class MediaPacketSource {

    /** One demuxed access unit, owned by the consumer until it is recycled. */
    static final class Packet {
        final ByteBuffer data;
        int size;
        long presentationUs;
        boolean endOfStream;
        boolean encrypted;

        private Packet(int capacity) {
            data = capacity > 0 ? ByteBuffer.allocateDirect(capacity) : null;
        }
    }

    /** Shared, never-recycled marker so end of stream cannot consume a pooled buffer. */
    private static final Packet END_OF_STREAM = createEndMarker();

    private static final long NO_SEEK = Long.MIN_VALUE;

    private final MediaExtractor extractor;
    private final Object lock = new Object();
    private final ArrayDeque<Packet> ready = new ArrayDeque<>();
    private final ArrayDeque<Packet> pool = new ArrayDeque<>();
    private final int packetCapacity;
    private final Thread reader;

    private int generation;
    private int appliedGeneration;
    private long seekRequestUs = NO_SEEK;
    private int seekMode = MediaExtractor.SEEK_TO_CLOSEST_SYNC;
    private boolean endOfStream;
    private boolean closed;
    private RuntimeException failure;

    MediaPacketSource(MediaExtractor extractor, int packetCapacity, int packetCount) {
        if (extractor == null || packetCapacity <= 0 || packetCount <= 0) {
            throw new IllegalArgumentException("A sized packet queue is required");
        }
        this.extractor = extractor;
        this.packetCapacity = packetCapacity;
        for (int index = 0; index < packetCount; index++) {
            pool.addLast(new Packet(packetCapacity));
        }
        reader = new Thread(this::readLoop, "WalkTape packet reader");
        reader.setDaemon(true);
        reader.start();
    }

    static boolean isEndOfStream(Packet packet) {
        return packet != null && packet.endOfStream;
    }

    int packetCapacity() {
        return packetCapacity;
    }

    /**
     * Takes the next packet, waiting up to {@code timeoutMs}.
     *
     * @return the next packet, {@code null} if none arrived in time, or the end-of-stream marker.
     */
    Packet poll(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (lock) {
            while (true) {
                throwIfFailedLocked();
                if (!ready.isEmpty()) {
                    Packet packet = ready.removeFirst();
                    lock.notifyAll();
                    return packet;
                }
                if (endOfStream) {
                    return END_OF_STREAM;
                }
                if (closed) {
                    return null;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return null;
                }
                lock.wait(remaining);
            }
        }
    }

    void recycle(Packet packet) {
        if (packet == null || packet == END_OF_STREAM) {
            return;
        }
        synchronized (lock) {
            packet.endOfStream = false;
            packet.encrypted = false;
            packet.size = 0;
            pool.addLast(packet);
            lock.notifyAll();
        }
    }

    /**
     * Repositions the extractor and discards every packet read before the request.
     *
     * <p>Returns once the reader thread has actually applied the seek, so subsequent packets are
     * guaranteed to come from the new position. The wait is bounded by one outstanding read.</p>
     */
    void seekTo(long positionUs, int mode) throws InterruptedException {
        synchronized (lock) {
            generation++;
            seekRequestUs = positionUs;
            seekMode = mode;
            endOfStream = false;
            while (!ready.isEmpty()) {
                pool.addLast(ready.removeFirst());
            }
            lock.notifyAll();
            while (appliedGeneration != generation && !closed && failure == null) {
                lock.wait();
            }
            throwIfFailedLocked();
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
        reader.interrupt();
        try {
            reader.join(1_000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void throwIfFailedLocked() {
        if (failure != null) {
            throw failure;
        }
    }

    private void readLoop() {
        // Above every ordinary thread so a screen-off scheduler still runs it promptly, but one
        // step below the renderer and the AudioTrack writer, which must always win a core first.
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try {
            while (true) {
                Packet packet;
                int myGeneration;
                synchronized (lock) {
                    while (!closed && seekRequestUs == NO_SEEK
                            && (pool.isEmpty() || endOfStream)) {
                        lock.wait();
                    }
                    if (closed) {
                        return;
                    }
                    if (seekRequestUs != NO_SEEK) {
                        extractor.seekTo(seekRequestUs, seekMode);
                        seekRequestUs = NO_SEEK;
                        endOfStream = false;
                        appliedGeneration = generation;
                        lock.notifyAll();
                        continue;
                    }
                    packet = pool.pollFirst();
                    myGeneration = generation;
                }

                // The blocking storage read happens outside the lock. A seek posted meanwhile is
                // detected below and the packet is returned to the pool unused.
                packet.data.clear();
                boolean sampleEncrypted = PlaybackController.isEncryptedSample(
                        extractor.getSampleTrackIndex(), extractor.getSampleFlags());
                int size = extractor.readSampleData(packet.data, 0);
                long presentationUs = size < 0 ? 0L : Math.max(0L, extractor.getSampleTime());
                if (size >= 0) {
                    extractor.advance();
                }

                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                    if (myGeneration != generation) {
                        pool.addLast(packet);
                        lock.notifyAll();
                        continue;
                    }
                    if (size < 0) {
                        endOfStream = true;
                        pool.addLast(packet);
                    } else {
                        packet.size = size;
                        packet.presentationUs = presentationUs;
                        packet.encrypted = sampleEncrypted;
                        packet.endOfStream = false;
                        packet.data.position(0);
                        packet.data.limit(size);
                        ready.addLast(packet);
                    }
                    lock.notifyAll();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            synchronized (lock) {
                if (!closed) {
                    failure = error;
                }
                lock.notifyAll();
            }
        }
    }

    private static Packet createEndMarker() {
        Packet marker = new Packet(0);
        marker.endOfStream = true;
        return marker;
    }
}
