package com.beatofthedrum.alacdecoder;

import android.os.Process;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded, allocation-free ALAC frame pipeline.
 *
 * <p>ALAC packets are independently decodable. Splitting consecutive packets over two decoder
 * instances lets high-rate files use two CPU cores while the owner consumes results in original
 * presentation order. The fixed slot pool puts a hard ceiling on memory and look-ahead.</p>
 */
public final class ParallelAlacFrameDecoder implements AutoCloseable {
    private static final long WORKER_JOIN_MS = 1_000L;

    private final byte[] codecSpecificData;
    private final ArrayBlockingQueue<Frame> freeFrames;
    private final ArrayBlockingQueue<Frame> jobs;
    private final Worker[] workers;
    private final int slotCount;
    private final int bitsPerSample;
    private final int channelCount;
    private final int sampleRate;
    private volatile boolean closed;

    public ParallelAlacFrameDecoder(byte[] codecSpecificData,
                                    int workerCount,
                                    int slotCount,
                                    int maximumPacketBytes) {
        if (codecSpecificData == null) {
            throw new IllegalArgumentException("Missing ALAC codec configuration");
        }
        if (workerCount < 1 || slotCount < workerCount || maximumPacketBytes < 1) {
            throw new IllegalArgumentException("Invalid ALAC pipeline dimensions");
        }
        this.codecSpecificData = codecSpecificData.clone();
        this.slotCount = slotCount;

        AlacFrameDecoder firstDecoder = new AlacFrameDecoder(this.codecSpecificData);
        bitsPerSample = firstDecoder.getBitsPerSample();
        channelCount = firstDecoder.getChannelCount();
        sampleRate = firstDecoder.getSampleRate();

        freeFrames = new ArrayBlockingQueue<>(slotCount);
        jobs = new ArrayBlockingQueue<>(slotCount);
        for (int index = 0; index < slotCount; index++) {
            freeFrames.add(new Frame(maximumPacketBytes, firstDecoder.getMaximumOutputInts()));
        }

        workers = new Worker[workerCount];
        for (int index = 0; index < workerCount; index++) {
            AlacFrameDecoder decoder = index == 0
                    ? firstDecoder : new AlacFrameDecoder(this.codecSpecificData);
            workers[index] = new Worker(index, decoder);
            workers[index].start();
        }
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

    public int getSlotCount() {
        return slotCount;
    }

    /** Copies one extractor packet into a reusable slot and schedules it for decoding. */
    public Frame submit(ByteBuffer packet, int packetSize, long presentationTimeUs)
            throws InterruptedException {
        if (packet == null || packetSize <= 0 || packetSize > packet.remaining()) {
            throw new IllegalArgumentException("Invalid ALAC packet");
        }
        if (closed) {
            throw new IllegalStateException("ALAC pipeline is closed");
        }
        Frame frame = freeFrames.take();
        boolean submitted = false;
        try {
            frame.prepare(packet, packetSize, presentationTimeUs);
            if (closed) {
                throw new InterruptedException("ALAC pipeline closed");
            }
            jobs.put(frame);
            submitted = true;
            return frame;
        } finally {
            if (!submitted) {
                freeFrames.offer(frame);
            }
        }
    }

    /** Returns a consumed frame to the bounded slot pool. */
    public void recycle(Frame frame) {
        if (frame == null || closed) {
            return;
        }
        frame.recycle();
        if (!freeFrames.offer(frame)) {
            throw new IllegalStateException("ALAC frame recycled twice");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Worker worker : workers) {
            worker.interrupt();
        }

        boolean restoreInterrupt = Thread.interrupted();
        long deadline = System.currentTimeMillis() + WORKER_JOIN_MS;
        for (Worker worker : workers) {
            while (worker.isAlive()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    worker.join(remaining);
                } catch (InterruptedException ignored) {
                    restoreInterrupt = true;
                }
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class Frame {
        private byte[] packet;
        private final int[] decoded;
        private int packetSize;
        private long presentationTimeUs;
        private boolean ready;
        private Throwable failure;
        private int decodedByteCount;

        private Frame(int maximumPacketBytes, int maximumOutputInts) {
            packet = new byte[maximumPacketBytes];
            decoded = new int[maximumOutputInts];
        }

        public int[] getDecodedSamples() {
            return decoded;
        }

        public long getPresentationTimeUs() {
            return presentationTimeUs;
        }

        public synchronized int awaitDecodedByteCount() throws InterruptedException {
            while (!ready) {
                wait();
            }
            if (failure != null) {
                if (failure instanceof RuntimeException) {
                    throw (RuntimeException) failure;
                }
                throw new IllegalStateException("ALAC frame decode failed", failure);
            }
            return decodedByteCount;
        }

        public synchronized boolean isReady() {
            return ready;
        }

        private void prepare(ByteBuffer source, int size, long presentationUs) {
            if (packet.length < size) {
                packet = new byte[Math.max(size, packet.length * 2)];
            }
            source.get(packet, 0, size);
            packetSize = size;
            presentationTimeUs = Math.max(0L, presentationUs);
            decodedByteCount = 0;
            failure = null;
            ready = false;
        }

        private synchronized void complete(int byteCount, Throwable error) {
            decodedByteCount = byteCount;
            failure = error;
            ready = true;
            notifyAll();
        }

        private synchronized void recycle() {
            if (!ready) {
                throw new IllegalStateException("ALAC frame is still decoding");
            }
            packetSize = 0;
            presentationTimeUs = 0L;
            decodedByteCount = 0;
            failure = null;
            ready = false;
        }
    }

    private final class Worker extends Thread {
        private final AlacFrameDecoder decoder;

        Worker(int index, AlacFrameDecoder decoder) {
            super("WalkTape ALAC decoder " + (index + 1));
            this.decoder = decoder;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            while (!closed) {
                Frame frame;
                try {
                    frame = jobs.take();
                } catch (InterruptedException ignored) {
                    continue;
                }
                int decodedBytes = 0;
                Throwable failure = null;
                try {
                    decodedBytes = decoder.decode(frame.packet, frame.packetSize, frame.decoded);
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    frame.complete(decodedBytes, failure);
                }
            }
        }
    }
}
