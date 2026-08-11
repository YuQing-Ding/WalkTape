package com.yqdscott.walktape;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

import com.beatofthedrum.alacdecoder.ParallelAlacFrameDecoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Decode-to-PCM transport. Every supported source is converted to stereo float PCM before it
 * reaches {@link AudioTrack}, allowing the selected machine model to process the audible signal.
 */
public final class PlaybackController {

    // Public AudioFormat values added after our minSdk; numeric constants keep old devices safe.
    private static final int PCM_24_BIT_PACKED = 21;
    private static final int PCM_32_BIT = 22;
    private static final int AUDIO_PRIME_MS = 450;
    private static final int TONE_CHANGE_PRIME_MS = 120;
    private static final int AUDIO_BUFFER_MS = 2_000;
    private static final int PCM_QUEUE_MS = 2_000;
    private static final int AUDIO_WRITE_FRAMES = 2_048;
    private static final long AUDIO_WRITER_JOIN_MS = 2_000L;
    private static final int ALAC_DECODE_WORKERS = 2;
    private static final int ALAC_PIPELINE_SLOTS = 12;

    public enum HotlineResult {
        STARTED,
        STOPPED,
        NEED_HEADPHONES,
        AUDIO_UNAVAILABLE
    }

    public interface Listener {
        void onPrepared(long durationMs);

        void onCompleted();

        void onError(String message);

        default void onHotlineStopped(String message) {
        }
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HotlineMonitor hotlineMonitor;
    private final PowerManager.WakeLock playbackWakeLock;

    private volatile DecoderSession session;
    private volatile CatalogModels.Track currentTrack;
    private volatile float ducking = 1f;
    private volatile boolean highTape = true;
    private volatile DolbyMode dolbyMode = DolbyMode.OFF;
    private volatile TapeMachineProfile machineProfile =
            TapeMachineProfile.sonyTpsL2Reference();
    private volatile TapeStockProfile tapeProfile = TapeStockProfile.sonyChf1978();
    private volatile MachineConditionProfile conditionProfile =
            MachineConditionProfile.calibrated();

    public PlaybackController(Context context, Listener listener) {
        appContext = context.getApplicationContext();
        this.listener = listener;
        hotlineMonitor = new HotlineMonitor(appContext, this::onHotlineMonitorStopped);
        PowerManager powerManager =
                (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        playbackWakeLock = powerManager == null ? null : powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "WalkTape:RealtimePlayback");
        if (playbackWakeLock != null) {
            playbackWakeLock.setReferenceCounted(false);
        }
    }

    public synchronized void loadAndPlay(CatalogModels.Track track) {
        DecoderSession previous = session;
        session = null;
        if (previous != null) {
            previous.requestStop();
        }
        currentTrack = track;
        if (track == null || track.contentUri == null) {
            updatePlaybackWakeLock(false);
            return;
        }
        updatePlaybackWakeLock(true);

        // Wait for the old decoder on the new worker thread, never on the UI thread. This keeps
        // rapid NEXT/PREVIOUS taps from leaving two AudioTracks competing for the device.
        DecoderSession next = new DecoderSession(Uri.parse(track.contentUri), track.durationMs,
                previous);
        next.setDucking(ducking);
        next.setMachineProfile(machineProfile);
        next.setTapeProfile(tapeProfile);
        next.setConditionProfile(conditionProfile);
        next.setHighTape(highTape);
        next.setDolbyMode(dolbyMode);
        session = next;
        next.start();
    }

    public boolean toggle() {
        DecoderSession active = session;
        if (active == null) {
            updatePlaybackWakeLock(false);
            return false;
        }
        if (active.isFinished()) {
            CatalogModels.Track track = currentTrack;
            if (track != null) {
                loadAndPlay(track);
                return true;
            }
            updatePlaybackWakeLock(false);
            return false;
        }
        boolean playing = active.toggle();
        updatePlaybackWakeLock(playing);
        return playing;
    }

    public boolean isPlaying() {
        DecoderSession active = session;
        return active != null && active.isPlaying();
    }

    public long getPositionMs() {
        DecoderSession active = session;
        return active == null ? 0 : active.getPositionMs();
    }

    public long getDurationMs() {
        DecoderSession active = session;
        return active == null ? 0 : active.durationMs;
    }

    /** Returns the final post-tape/post-machine peaks aligned to the audible AudioTrack head. */
    public void getAudioMeterLevels(float[] destination) {
        if (destination == null || destination.length < 2) {
            throw new IllegalArgumentException("Two meter channels are required");
        }
        DecoderSession active = session;
        if (active == null || !active.isPlaying()) {
            destination[0] = 0f;
            destination[1] = 0f;
            return;
        }
        active.getAudioMeterLevels(destination);
    }

    public void seekToFraction(float fraction) {
        DecoderSession active = session;
        if (active == null || active.durationMs <= 0) {
            return;
        }
        float bounded = Math.max(0f, Math.min(1f, fraction));
        active.seekTo((long) (bounded * active.durationMs));
    }

    public void setHotlineDucking(boolean enabled) {
        ducking = enabled ? 0.24f : 1f;
        DecoderSession active = session;
        if (active != null) {
            active.setDucking(ducking);
        }
    }

    public HotlineResult setHotlineEnabled(boolean enabled) {
        if (!enabled) {
            hotlineMonitor.stop();
            setHotlineDucking(false);
            return HotlineResult.STOPPED;
        }
        HotlineMonitor.StartResult result = hotlineMonitor.start();
        if (result == HotlineMonitor.StartResult.STARTED) {
            setHotlineDucking(true);
            return HotlineResult.STARTED;
        }
        setHotlineDucking(false);
        return result == HotlineMonitor.StartResult.NEED_HEADPHONES
                ? HotlineResult.NEED_HEADPHONES : HotlineResult.AUDIO_UNAVAILABLE;
    }

    public boolean isHotlineActive() {
        return hotlineMonitor.isRunning();
    }

    public void setHighTape(boolean enabled) {
        highTape = enabled;
        DecoderSession active = session;
        if (active != null) {
            active.setHighTape(enabled);
        }
    }

    public void setDolbyMode(DolbyMode mode) {
        DolbyMode selected = mode == null ? DolbyMode.OFF : mode;
        dolbyMode = selected;
        DecoderSession active = session;
        if (active != null) {
            active.setDolbyMode(selected);
        }
    }

    DolbyMode getDolbyModeForTest() {
        return dolbyMode;
    }

    public void setMachineProfile(TapeMachineProfile profile) {
        TapeMachineProfile selected = profile == null
                ? TapeMachineProfile.sonyTpsL2Reference()
                : TapeMachineProfile.forId(profile.id);
        machineProfile = selected;
        DecoderSession active = session;
        if (active != null) {
            active.setMachineProfile(selected);
        }
    }

    TapeMachineProfile getMachineProfileForTest() {
        return machineProfile;
    }

    public void setTapeProfile(TapeStockProfile profile) {
        TapeStockProfile selected = profile == null
                ? TapeStockProfile.sonyChf1978()
                : TapeStockProfile.forId(profile.id);
        tapeProfile = selected;
        DecoderSession active = session;
        if (active != null) {
            active.setTapeProfile(selected);
        }
    }

    TapeStockProfile getTapeProfileForTest() {
        return tapeProfile;
    }

    public void setConditionProfile(MachineConditionProfile profile) {
        MachineConditionProfile selected = profile == null
                ? MachineConditionProfile.calibrated()
                : MachineConditionProfile.forId(profile.id);
        conditionProfile = selected;
        DecoderSession active = session;
        if (active != null) {
            active.setConditionProfile(selected);
        }
    }

    MachineConditionProfile getConditionProfileForTest() {
        return conditionProfile;
    }

    public synchronized void release() {
        hotlineMonitor.stop();
        DecoderSession previous = session;
        session = null;
        currentTrack = null;
        if (previous != null) {
            previous.requestStop();
        }
        updatePlaybackWakeLock(false);
    }

    private void onHotlineMonitorStopped(String message) {
        setHotlineDucking(false);
        if (message != null && !message.isEmpty()) {
            mainHandler.post(() -> listener.onHotlineStopped(message));
        }
    }

    private boolean isCurrent(DecoderSession candidate) {
        return session == candidate;
    }

    /** Package-private regression diagnostic; a settled player must retain only its live session. */
    int retainedDecoderSessionCount() {
        DecoderSession active = session;
        return active == null ? 0 : active.retainedSessionCount();
    }

    boolean isPlaybackWakeLockHeldForTest() {
        return playbackWakeLock != null && playbackWakeLock.isHeld();
    }

    @SuppressLint("WakelockTimeout")
    private void updatePlaybackWakeLock(boolean needed) {
        PowerManager.WakeLock lock = playbackWakeLock;
        if (lock == null) {
            return;
        }
        try {
            if (needed && !lock.isHeld()) {
                // Media playback has no predetermined duration. Every terminal path releases the
                // non-reference-counted lock, while an arbitrary timeout would recreate the exact
                // long-track screen-off underrun this lock exists to prevent.
                lock.acquire();
            } else if (!needed && lock.isHeld()) {
                lock.release();
            }
        } catch (RuntimeException ignored) {
            // Audio remains usable on unusual vendor builds that reject application wake locks.
        }
    }

    private void postPrepared(DecoderSession source, long durationMs) {
        mainHandler.post(() -> {
            if (isCurrent(source)) {
                listener.onPrepared(durationMs);
            }
        });
    }

    private void postCompleted(DecoderSession source) {
        synchronized (this) {
            if (isCurrent(source)) {
                updatePlaybackWakeLock(false);
            }
        }
        mainHandler.post(() -> {
            if (isCurrent(source)) {
                listener.onCompleted();
            }
        });
    }

    private void postError(DecoderSession source, String message) {
        synchronized (this) {
            if (isCurrent(source)) {
                updatePlaybackWakeLock(false);
            }
        }
        mainHandler.post(() -> {
            if (isCurrent(source)) {
                listener.onError(message);
            }
        });
    }

    private final class DecoderSession extends Thread {
        private static final long NO_SEEK = Long.MIN_VALUE;
        private static final long CODEC_TIMEOUT_US = 10_000L;

        private final Uri uri;
        // Cleared as soon as hand-off completes. Keeping this final used to retain every previous
        // session (and all of its large PCM/resampler arrays) for the lifetime of the current
        // track, producing a genuine switch-by-switch memory leak and eventual GC audio stalls.
        private volatile DecoderSession predecessor;
        private final Object controlLock = new Object();

        private volatile boolean stopRequested;
        private volatile boolean paused;
        private volatile boolean prepared;
        private volatile boolean finished;
        private volatile long durationMs;
        private volatile long fallbackPositionMs;
        private volatile float sessionDucking = 1f;
        private volatile boolean sessionHighTape;
        private volatile DolbyMode sessionDolbyMode = DolbyMode.OFF;
        private volatile TapeMachineProfile sessionProfile =
                TapeMachineProfile.sonyTpsL2Reference();
        private volatile TapeStockProfile sessionTapeProfile = TapeStockProfile.sonyChf1978();
        private volatile MachineConditionProfile sessionConditionProfile =
                MachineConditionProfile.calibrated();
        private volatile AudioTrack audioTrack;
        private volatile PcmWriter pcmWriter;
        private volatile TapeMachineDsp dsp;
        private volatile String dspProfileId;
        private volatile String dspTapeProfileId;
        private volatile String dspConditionProfileId;
        private volatile boolean outputStarted;

        private MediaExtractor extractor;
        private MediaCodec codec;
        private String sourceMime;
        private String decoderName;
        private long pendingSeekUs = NO_SEEK;
        private boolean pendingSeekPreservesDsp;
        private boolean consumedSeekPreservesDsp;
        private long discardBeforeUs = NO_SEEK;
        private int inputSampleRate;
        private int outputSampleRate;
        private int outputChannels;
        private int outputEncoding;
        private volatile boolean playbackAnchorSet;
        private volatile long playbackAnchorFrames;
        private volatile long mediaAnchorUs;
        private volatile long stereoFramesWritten;
        private volatile int audioPrimeMs = AUDIO_PRIME_MS;
        private final AudioLevelTimeline audioLevels = new AudioLevelTimeline();
        private boolean preparedCallbackSent;
        private float[] stereoBuffer = new float[0];
        private float[] resampledBuffer = new float[0];
        private short[] pcm16Buffer = new short[0];
        private float[] pcmFloatBuffer = new float[0];
        private int[] pcm32Buffer = new int[0];
        private byte[] pcm24Buffer = new byte[0];
        private PcmRateConverter rateConverter;

        DecoderSession(Uri uri, long catalogDurationMs, DecoderSession predecessor) {
            super("WalkTape PCM decoder");
            this.uri = uri;
            this.predecessor = predecessor;
            durationMs = Math.max(0, catalogDurationMs);
        }

        @Override
        public void run() {
            try {
                awaitPredecessor();
                if (stopRequested) {
                    return;
                }
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                extractor = new MediaExtractor();
                extractor.setDataSource(appContext, uri, null);
                int audioTrackIndex = findAudioTrack(extractor);
                if (audioTrackIndex < 0) {
                    throw new PlaybackFailure("文件里没有可播放的音轨");
                }

                MediaFormat sourceFormat = extractor.getTrackFormat(audioTrackIndex);
                sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME);
                if (sourceMime == null || !sourceMime.startsWith("audio/")) {
                    throw new PlaybackFailure("无法识别音频编码");
                }
                long extractedDurationUs = formatLong(sourceFormat, MediaFormat.KEY_DURATION, 0L);
                if (extractedDurationUs > 0) {
                    durationMs = extractedDurationUs / 1_000L;
                }
                extractor.selectTrack(audioTrackIndex);
                rejectEncryptedSample();

                if (isAlac(sourceMime)) {
                    // Pixel devices do not expose ALAC through MediaCodec. MediaExtractor still
                    // demuxes the M4A reliably, so decode its ALAC packets in software and feed
                    // the same selected machine renderer as every other format.
                    decodeAlac(sourceFormat);
                } else if (isRawPcm(sourceMime)) {
                    decodeRawPcm(sourceFormat);
                } else {
                    decodeCompressed(sourceFormat);
                }

                if (!stopRequested) {
                    finished = true;
                    prepared = false;
                    fallbackPositionMs = durationMs;
                    postCompleted(this);
                }
            } catch (Throwable error) {
                if (!stopRequested) {
                    finished = true;
                    prepared = false;
                    postError(this, readableFailure(error));
                }
            } finally {
                releaseDecoderResources();
            }
        }

        boolean toggle() {
            synchronized (controlLock) {
                if (!prepared || finished || stopRequested) {
                    return false;
                }
                paused = !paused;
                AudioTrack output = audioTrack;
                if (output != null) {
                    try {
                        if (paused) {
                            output.pause();
                        } else if (outputStarted) {
                            output.play();
                        }
                    } catch (IllegalStateException ignored) {
                        // The decoder thread will recreate or release the output as needed.
                    }
                }
                controlLock.notifyAll();
                return !paused;
            }
        }

        boolean isPlaying() {
            return prepared && !paused && !finished && !stopRequested;
        }

        boolean isFinished() {
            return finished;
        }

        long getPositionMs() {
            if (finished && durationMs > 0) {
                return durationMs;
            }
            AudioTrack output = audioTrack;
            if (!playbackAnchorSet || output == null || outputSampleRate <= 0) {
                return Math.max(0, fallbackPositionMs);
            }
            try {
                long head = Integer.toUnsignedLong(output.getPlaybackHeadPosition());
                long elapsedFrames = unsignedFrameDifference(head, playbackAnchorFrames);
                long calculated = mediaAnchorUs / 1_000L
                        + elapsedFrames * 1_000L / outputSampleRate;
                if (durationMs > 0) {
                    calculated = Math.min(durationMs, calculated);
                }
                return Math.max(0, calculated);
            } catch (IllegalStateException ignored) {
                return Math.max(0, fallbackPositionMs);
            }
        }

        void getAudioMeterLevels(float[] destination) {
            audioLevels.sample(getPositionMs() * 1_000L, destination);
        }

        void seekTo(long positionMs) {
            requestSeek(positionMs, AUDIO_PRIME_MS, false);
        }

        private void requestSeek(long positionMs, int primeMs, boolean preserveDsp) {
            synchronized (controlLock) {
                boolean alreadyPending = pendingSeekUs != NO_SEEK;
                pendingSeekUs = Math.max(0, positionMs) * 1_000L;
                // A full machine/tape rebuild remains sticky if a HIGH/LOW update lands in the
                // same UI frame. Otherwise the later lightweight request could accidentally turn
                // the pending rebuild into a state-preserving seek.
                pendingSeekPreservesDsp = alreadyPending
                        ? pendingSeekPreservesDsp && preserveDsp : preserveDsp;
                audioPrimeMs = Math.max(1, primeMs);
                fallbackPositionMs = Math.max(0, positionMs);
                playbackAnchorSet = false;
                stereoFramesWritten = 0L;
                audioLevels.clear();

                PcmWriter writer = pcmWriter;
                if (writer != null) {
                    // Invalidate both queued PCM and any block currently crossing into AudioTrack.
                    // resetAfterSeek() clears once more after the extractor/codec has moved, which
                    // closes the tiny race where a completed DSP block observes the first flush.
                    writer.flushForSeek();
                }

                // AudioTrack.write() is blocking and otherwise cannot observe the pending seek
                // until the old buffer has played. Pause/flush makes that write return now; the
                // decoder thread performs the actual seek and restarts from the new timestamp.
                AudioTrack output = audioTrack;
                if (output != null) {
                    try {
                        output.pause();
                        output.flush();
                        setOutputStartThreshold(output, outputSampleRate, primeMs);
                        outputStarted = false;
                    } catch (IllegalStateException ignored) {
                        // The worker reports a real error if the output is no longer usable.
                    }
                }
                controlLock.notifyAll();
            }
        }

        void setDucking(float volume) {
            sessionDucking = volume;
            AudioTrack output = audioTrack;
            if (output != null) {
                try {
                    output.setVolume(volume);
                } catch (IllegalStateException ignored) {
                    // Applied when the output is configured.
                }
            }
        }

        void setHighTape(boolean enabled) {
            boolean changed = sessionHighTape != enabled;
            sessionHighTape = enabled;
            TapeMachineDsp renderer = dsp;
            if (renderer != null) {
                renderer.setHighTape(enabled);
            }
            if (changed && prepared && !finished && !stopRequested) {
                // DSP runs several seconds ahead to survive screen-off scheduling. Re-render from
                // the audible playhead so one physical switch press is heard immediately instead
                // of waiting behind PCM that already contains the previous HIGH/LOW curve.
                requestSeek(getPositionMs(), TONE_CHANGE_PRIME_MS, true);
            }
        }

        void setDolbyMode(DolbyMode mode) {
            DolbyMode selected = mode == null ? DolbyMode.OFF : mode;
            boolean changed = sessionDolbyMode != selected;
            sessionDolbyMode = selected;
            TapeMachineDsp renderer = dsp;
            if (renderer != null) {
                renderer.setDolbyMode(selected);
            }
            if (changed && prepared && !finished && !stopRequested) {
                // Dolby changes the encoded tape drive and its complementary replay path. Flush
                // already-rendered PCM and rebuild from the audible head, just like moving the
                // physical selector before continuing the tape.
                // C-type adds two complementary sliding-band stages. Give the exact renderer the
                // same reserve as a fresh start so the selector cannot restart AudioTrack with
                // only the lightweight tone-switch cushion, especially after 192 kHz resampling.
                requestSeek(getPositionMs(), AUDIO_PRIME_MS, false);
            }
        }

        void setMachineProfile(TapeMachineProfile profile) {
            TapeMachineProfile selected = profile == null
                    ? TapeMachineProfile.sonyTpsL2Reference()
                    : TapeMachineProfile.forId(profile.id);
            boolean changed = !selected.id.equals(sessionProfile.id);
            sessionProfile = selected;
            if (changed && prepared && !finished && !stopRequested) {
                // Build the new renderer on the decoder thread, then re-enter at the audible
                // playhead so no queued PCM from the previous machine remains audible.
                requestSeek(getPositionMs(), TONE_CHANGE_PRIME_MS, false);
            }
        }

        void setTapeProfile(TapeStockProfile profile) {
            TapeStockProfile selected = profile == null
                    ? TapeStockProfile.sonyChf1978()
                    : TapeStockProfile.forId(profile.id);
            boolean changed = !selected.id.equals(sessionTapeProfile.id);
            sessionTapeProfile = selected;
            if (changed && prepared && !finished && !stopRequested) {
                // Re-enter at the audible playhead so queued PCM cannot mix two tape stocks.
                requestSeek(getPositionMs(), TONE_CHANGE_PRIME_MS, false);
            }
        }

        void setConditionProfile(MachineConditionProfile profile) {
            MachineConditionProfile selected = profile == null
                    ? MachineConditionProfile.calibrated()
                    : MachineConditionProfile.forId(profile.id);
            boolean changed = !selected.id.equals(sessionConditionProfile.id);
            sessionConditionProfile = selected;
            if (changed && prepared && !finished && !stopRequested) {
                // Condition affects already-rendered transport/head PCM, so rebuild at the audible
                // playhead instead of mixing old and new unit tolerances in the look-ahead queue.
                requestSeek(getPositionMs(), TONE_CHANGE_PRIME_MS, false);
            }
        }

        void requestStop() {
            stopRequested = true;
            interrupt();
            PcmWriter writer = pcmWriter;
            if (writer != null) {
                writer.requestStop();
            }
            synchronized (controlLock) {
                controlLock.notifyAll();
            }
            AudioTrack output = audioTrack;
            if (output != null) {
                try {
                    output.pause();
                    output.flush();
                } catch (IllegalStateException ignored) {
                    // The decoder thread owns final release.
                }
            }
        }

        private void awaitPredecessor() {
            DecoderSession previous = predecessor;
            predecessor = null;
            if (previous == null) {
                return;
            }

            // A cancelled intermediate session is still the serial hand-off barrier for the next
            // one. Ignore its interrupt while the resource-owning predecessor exits; otherwise a
            // rapid A -> B -> C switch lets C overtake B and run beside A. This wait never touches
            // the UI thread, and requestStop() has already paused/flushed AudioTrack so a blocking
            // write returns promptly.
            boolean interrupted = false;
            while (previous.isAlive()) {
                try {
                    previous.join(32L);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        int retainedSessionCount() {
            int count = 1;
            DecoderSession ancestor = predecessor;
            while (ancestor != null && count < 1_000) {
                count++;
                ancestor = ancestor.predecessor;
            }
            return count;
        }

        private void decodeRawPcm(MediaFormat format) throws Exception {
            int sampleRate = formatInt(format, MediaFormat.KEY_SAMPLE_RATE, 44_100);
            int channels = formatInt(format, MediaFormat.KEY_CHANNEL_COUNT, 2);
            int encoding = formatInt(format, MediaFormat.KEY_PCM_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (encoding == AudioFormat.ENCODING_DEFAULT) {
                encoding = AudioFormat.ENCODING_PCM_16BIT;
            }
            configureOutput(sampleRate, channels, encoding);

            ByteBuffer source = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.nativeOrder());
            while (!stopRequested) {
                waitWhilePaused();
                long seekUs = consumeSeek();
                if (seekUs != NO_SEEK) {
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    resetAfterSeek(seekUs);
                }
                if (stopRequested || paused) {
                    continue;
                }

                rejectEncryptedSample();
                source.clear();
                int size = extractor.readSampleData(source, 0);
                if (size < 0) {
                    if (waitForAudioDrain()) {
                        return;
                    }
                    continue;
                }
                long presentationUs = Math.max(0L, extractor.getSampleTime());
                writePcm(source, 0, size, encoding, channels, presentationUs);
                extractor.advance();
            }
        }

        private void decodeAlac(MediaFormat sourceFormat) throws Exception {
            decoderName = "WalkTape ALAC 2-core software decoder";
            ByteBuffer codecData = sourceFormat.getByteBuffer("csd-0");
            if (codecData == null || !codecData.hasRemaining()) {
                throw new PlaybackFailure("这个 ALAC / M4A 文件缺少解码参数");
            }
            ByteBuffer configView = codecData.duplicate();
            byte[] config = new byte[configView.remaining()];
            configView.get(config);

            int advertisedPacketBytes = formatInt(
                    sourceFormat, MediaFormat.KEY_MAX_INPUT_SIZE, 0) + 16;
            int inputCapacity = Math.max(256 * 1024, advertisedPacketBytes);
            int queuedPacketCapacity = Math.max(32 * 1024, advertisedPacketBytes);
            ParallelAlacFrameDecoder decoder;
            try {
                decoder = new ParallelAlacFrameDecoder(config, ALAC_DECODE_WORKERS,
                        ALAC_PIPELINE_SLOTS, queuedPacketCapacity);
            } catch (IllegalArgumentException invalidConfig) {
                throw new PlaybackFailure("不支持这个 ALAC / M4A："
                        + invalidConfig.getMessage(), invalidConfig);
            }
            int sampleRate = decoder.getSampleRate();
            int channels = decoder.getChannelCount();
            int bitsPerSample = decoder.getBitsPerSample();
            int bytesPerSample = decoder.getBytesPerSample();
            int encoding = bitsPerSample == 16
                    ? AudioFormat.ENCODING_PCM_16BIT : PCM_24_BIT_PACKED;
            configureOutput(sampleRate, channels, encoding);

            ByteBuffer source = ByteBuffer.allocateDirect(inputCapacity);
            ArrayDeque<ParallelAlacFrameDecoder.Frame> pending = new ArrayDeque<>(
                    ALAC_PIPELINE_SLOTS);
            boolean inputEnded = false;

            try {
                while (!stopRequested) {
                    waitWhilePaused();
                    long seekUs = consumeSeek();
                    if (seekUs != NO_SEEK) {
                        // Extractor has already been read ahead. Retire every old-generation frame
                        // before repositioning so a scrub/profile change cannot leak stale PCM.
                        decoder.close();
                        pending.clear();
                        extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        resetAfterSeek(seekUs);
                        decoder = new ParallelAlacFrameDecoder(config, ALAC_DECODE_WORKERS,
                                ALAC_PIPELINE_SLOTS, queuedPacketCapacity);
                        inputEnded = false;
                    }
                    if (stopRequested || paused) {
                        continue;
                    }

                    // ALAC packets are independent. Keep a fixed look-ahead window full while two
                    // decoder instances process consecutive frames on separate cores. Consumption
                    // remains strictly in MediaExtractor order, so PCM and timestamps are stable.
                    while (!inputEnded && pending.size() < decoder.getSlotCount()
                            && !stopRequested && !paused && !hasPendingSeek()) {
                        rejectEncryptedSample();
                        source.clear();
                        int packetSize = extractor.readSampleData(source, 0);
                        if (packetSize < 0) {
                            inputEnded = true;
                            break;
                        }
                        if (packetSize > source.capacity()) {
                            throw new PlaybackFailure("ALAC 压缩帧超过了解码缓冲区");
                        }
                        long presentationUs = Math.max(0L, extractor.getSampleTime());
                        source.position(0);
                        source.limit(packetSize);
                        pending.addLast(decoder.submit(source, packetSize, presentationUs));
                        extractor.advance();
                    }

                    if (hasPendingSeek()) {
                        continue;
                    }
                    if (pending.isEmpty()) {
                        if (inputEnded) {
                            if (waitForAudioDrain()) {
                                return;
                            }
                            inputEnded = false;
                        }
                        continue;
                    }

                    ParallelAlacFrameDecoder.Frame frame = pending.removeFirst();
                    try {
                        int byteCount;
                        try {
                            byteCount = frame.awaitDecodedByteCount();
                        } catch (RuntimeException decodeFailure) {
                            throw new PlaybackFailure("ALAC 音频帧解码失败", decodeFailure);
                        }
                        int[] decoded = frame.getDecodedSamples();
                        int maximumBytes = bytesPerSample == 2
                                ? decoded.length * 2 : decoded.length;
                        if (byteCount <= 0 || byteCount > maximumBytes) {
                            throw new PlaybackFailure("ALAC 解码器返回了异常大小的 PCM 数据");
                        }
                        if (!hasPendingSeek()) {
                            writeAlacPcm(decoded, byteCount, bytesPerSample, channels,
                                    frame.getPresentationTimeUs());
                        }
                    } finally {
                        if (frame.isReady()) {
                            decoder.recycle(frame);
                        }
                    }
                }
            } finally {
                decoder.close();
            }
        }

        private void decodeCompressed(MediaFormat sourceFormat) throws Exception {
            startDecoderWithBestPcm(sourceFormat);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            boolean outputEnded = false;
            while (!stopRequested) {
                if (outputEnded) {
                    if (waitForAudioDrain()) {
                        return;
                    }
                }
                waitWhilePaused();
                long seekUs = consumeSeek();
                if (seekUs != NO_SEEK) {
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec.flush();
                    inputEnded = false;
                    outputEnded = false;
                    resetAfterSeek(seekUs);
                }
                if (stopRequested || paused) {
                    continue;
                }

                if (!inputEnded) {
                    int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null) {
                            throw new PlaybackFailure("解码器没有提供输入缓冲区");
                        }
                        input.clear();
                        rejectEncryptedSample();
                        int sampleSize = extractor.readSampleData(input, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            long presentationUs = Math.max(0L, extractor.getSampleTime());
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    int sampleRate = formatInt(outputFormat, MediaFormat.KEY_SAMPLE_RATE,
                            formatInt(sourceFormat, MediaFormat.KEY_SAMPLE_RATE, 44_100));
                    int channels = formatInt(outputFormat, MediaFormat.KEY_CHANNEL_COUNT,
                            formatInt(sourceFormat, MediaFormat.KEY_CHANNEL_COUNT, 2));
                    int encoding = formatInt(outputFormat, MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT);
                    configureOutput(sampleRate, channels, encoding);
                } else if (outputIndex >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (audioTrack == null) {
                                MediaFormat outputFormat = codec.getOutputFormat(outputIndex);
                                configureOutput(
                                        formatInt(outputFormat, MediaFormat.KEY_SAMPLE_RATE, 44_100),
                                        formatInt(outputFormat, MediaFormat.KEY_CHANNEL_COUNT, 2),
                                        formatInt(outputFormat, MediaFormat.KEY_PCM_ENCODING,
                                                AudioFormat.ENCODING_PCM_16BIT));
                            }
                            ByteBuffer output = codec.getOutputBuffer(outputIndex);
                            if (output == null) {
                                throw new PlaybackFailure("解码器没有提供 PCM 输出");
                            }
                            writePcm(output, info.offset, info.size, outputEncoding,
                                    outputChannels, info.presentationTimeUs);
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false);
                    }
                }
            }
        }

        private void startDecoderWithBestPcm(MediaFormat sourceFormat) throws Exception {
            codec = createDecoder(sourceFormat, sourceMime);
            sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,
                    AudioFormat.ENCODING_PCM_FLOAT);
            try {
                codec.configure(sourceFormat, null, null, 0);
                codec.start();
                return;
            } catch (Exception floatPcmUnavailable) {
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                    // Continue with the mandatory 16-bit decoder output path.
                }
                codec = null;
                sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT);
                try {
                    codec = createDecoder(sourceFormat, sourceMime);
                    codec.configure(sourceFormat, null, null, 0);
                    codec.start();
                } catch (Exception fallbackFailure) {
                    fallbackFailure.addSuppressed(floatPcmUnavailable);
                    throw fallbackFailure;
                }
            }
        }

        private MediaCodec createDecoder(MediaFormat format, String mime) throws Exception {
            String name = null;
            try {
                name = new MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format);
            } catch (RuntimeException ignored) {
                // Some vendor codec lists reject optional container keys; MIME lookup remains valid.
            }
            try {
                MediaCodec selected;
                if (name != null) {
                    selected = MediaCodec.createByCodecName(name);
                } else {
                    selected = MediaCodec.createDecoderByType(mime);
                    MediaCodecInfo info = selected.getCodecInfo();
                    name = info == null ? mime : info.getName();
                }
                decoderName = name;
                return selected;
            } catch (Exception unavailable) {
                String detail = friendlyMime(mime) + "（" + mime + "）";
                throw new PlaybackFailure("当前系统没有可用的 " + detail + " 解码器", unavailable);
            }
        }

        private void configureOutput(int sampleRate, int channels, int encoding) throws Exception {
            if (sampleRate <= 0 || channels <= 0) {
                throw new PlaybackFailure("解码器返回了无效的 PCM 格式");
            }
            if (encoding == AudioFormat.ENCODING_DEFAULT) {
                encoding = AudioFormat.ENCODING_PCM_16BIT;
            }
            if (bytesPerSample(encoding) == 0) {
                throw new PlaybackFailure("暂不支持解码器输出的 PCM 编码：" + encoding);
            }
            int renderSampleRate = chooseRenderSampleRate(sampleRate);
            if (audioTrack != null && inputSampleRate == sampleRate
                    && outputSampleRate == renderSampleRate
                    && outputChannels == channels && outputEncoding == encoding) {
                return;
            }

            releaseAudioTrack();
            audioLevels.clear();
            int minimum = AudioTrack.getMinBufferSize(renderSampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            if (minimum <= 0) {
                throw new PlaybackFailure("设备无法建立 " + renderSampleRate + " Hz 音频输出");
            }
            // AudioTrack is the final hardware-facing reserve. A separate Java PCM ring below
            // lets decoding/DSP run ahead without making the time-critical writer wait for a
            // MediaCodec, ALAC packet, GC pause or a 192 kHz resampling burst.
            int bufferBytes = Math.max(minimum * 2,
                    renderSampleRate * 2 * Float.BYTES * AUDIO_BUFFER_MS / 1_000);
            AudioTrack output = buildAudioTrack(renderSampleRate, bufferBytes);
            if (output.getState() != AudioTrack.STATE_INITIALIZED) {
                output.release();
                throw new PlaybackFailure("AudioTrack 初始化失败");
            }

            setOutputStartThreshold(output, renderSampleRate, AUDIO_PRIME_MS);
            inputSampleRate = sampleRate;
            outputSampleRate = renderSampleRate;
            outputChannels = channels;
            outputEncoding = encoding;
            rateConverter = sampleRate == renderSampleRate
                    ? null : new PcmRateConverter(sampleRate, renderSampleRate);
            TapeMachineDsp renderer = TapeMachineDspFactory.create(
                    sessionProfile, sessionTapeProfile, sessionConditionProfile,
                    renderSampleRate);
            renderer.setHighTape(sessionHighTape);
            renderer.setDolbyMode(sessionDolbyMode);
            dsp = renderer;
            dspProfileId = sessionProfile.id;
            dspTapeProfileId = sessionTapeProfile.id;
            dspConditionProfileId = sessionConditionProfile.id;
            audioTrack = output;
            outputStarted = false;
            audioPrimeMs = AUDIO_PRIME_MS;
            output.setVolume(sessionDucking);
            PcmWriter writer = new PcmWriter(output, renderSampleRate);
            pcmWriter = writer;
            writer.start();
            prepared = true;
            if (!preparedCallbackSent) {
                preparedCallbackSent = true;
                postPrepared(this, durationMs);
            }
        }

        private AudioTrack buildAudioTrack(int sampleRate,
                                           int bufferBytes) {
            // Do not request PERFORMANCE_MODE_POWER_SAVING here. Pixel routes that request to a
            // deep-buffer path and down-clocks the CPU aggressively enough that a sustained
            // 192 kHz source can outrun a realtime tape renderer after thermal settling.
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        }

        private void setOutputStartThreshold(AudioTrack output,
                                             int sampleRate,
                                             int primeMs) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || output == null
                    || sampleRate <= 0) {
                return;
            }
            int requestedFrames = Math.max(1, sampleRate * Math.max(1, primeMs) / 1_000);
            int capacityFrames = output.getBufferCapacityInFrames();
            output.setStartThresholdInFrames(Math.min(requestedFrames, capacityFrames));
        }

        private void writePcm(ByteBuffer source,
                              int offset,
                              int size,
                              int encoding,
                              int channels,
                              long presentationUs) throws Exception {
            presentationUs = Math.max(0L, presentationUs);
            int bytesPerSample = bytesPerSample(encoding);
            if (bytesPerSample == 0 || channels <= 0 || size <= 0) {
                return;
            }
            ByteBuffer pcm = source.duplicate().order(ByteOrder.nativeOrder());
            int safeOffset = Math.max(0, offset);
            int safeLimit = Math.min(source.capacity(), safeOffset + size);
            if (safeLimit <= safeOffset) {
                return;
            }
            pcm.position(safeOffset);
            pcm.limit(safeLimit);

            int availableFrames = pcm.remaining() / (bytesPerSample * channels);
            int framesToSkip = 0;
            if (discardBeforeUs != NO_SEEK && presentationUs < discardBeforeUs) {
                long missingUs = discardBeforeUs - presentationUs;
                framesToSkip = (int) Math.min(availableFrames,
                        (missingUs * inputSampleRate + 999_999L) / 1_000_000L);
            }
            if (framesToSkip >= availableFrames) {
                return;
            }

            int sourceFrames = availableFrames - framesToSkip;
            int sourceSamples = sourceFrames * 2;
            if (stereoBuffer.length < sourceSamples) {
                stereoBuffer = new float[Math.max(sourceSamples,
                        stereoBuffer.length * 2 + 2048)];
            }

            pcm.position(safeOffset + framesToSkip * bytesPerSample * channels);
            int inputSamples = sourceFrames * channels;
            if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                if (pcm16Buffer.length < inputSamples) {
                    pcm16Buffer = new short[Math.max(inputSamples,
                            pcm16Buffer.length * 2 + 2048)];
                }
                ShortBuffer shortView = pcm.slice().order(ByteOrder.nativeOrder()).asShortBuffer();
                shortView.get(pcm16Buffer, 0, inputSamples);
                PcmRateConverter converter = rateConverter;
                if (converter != null) {
                    int maximumFrames = converter.maximumOutputFrames(sourceFrames);
                    int maximumSamples = maximumFrames * 2;
                    if (resampledBuffer.length < maximumSamples) {
                        resampledBuffer = new float[Math.max(maximumSamples,
                                resampledBuffer.length * 2 + 2048)];
                    }
                    int renderFrames = converter.processPcm16(
                            pcm16Buffer, 0, sourceFrames, channels, resampledBuffer);
                    long firstAudibleUs = presentationUs
                            + framesToSkip * 1_000_000L / inputSampleRate;
                    discardBeforeUs = NO_SEEK;
                    if (renderFrames > 0) {
                        renderTapePcm(resampledBuffer, renderFrames, firstAudibleUs);
                    }
                    return;
                }
                int sourceIndex = 0;
                int destination = 0;
                for (int frame = 0; frame < sourceFrames; frame++) {
                    float left = pcm16Buffer[sourceIndex] / 32_768f;
                    float right = channels == 1
                            ? left : pcm16Buffer[sourceIndex + 1] / 32_768f;
                    stereoBuffer[destination++] = left;
                    stereoBuffer[destination++] = right;
                    sourceIndex += channels;
                }
            } else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                if (pcmFloatBuffer.length < inputSamples) {
                    pcmFloatBuffer = new float[Math.max(inputSamples,
                            pcmFloatBuffer.length * 2 + 2048)];
                }
                FloatBuffer floatView = pcm.slice().order(ByteOrder.nativeOrder()).asFloatBuffer();
                floatView.get(pcmFloatBuffer, 0, inputSamples);
                copyFloatChannelsToStereo(pcmFloatBuffer, sourceFrames, channels, stereoBuffer);
            } else if (encoding == PCM_32_BIT) {
                if (pcm32Buffer.length < inputSamples) {
                    pcm32Buffer = new int[Math.max(inputSamples,
                            pcm32Buffer.length * 2 + 2048)];
                }
                IntBuffer intView = pcm.slice().order(ByteOrder.nativeOrder()).asIntBuffer();
                intView.get(pcm32Buffer, 0, inputSamples);
                int sourceIndex = 0;
                int destination = 0;
                for (int frame = 0; frame < sourceFrames; frame++) {
                    float left = (float) (pcm32Buffer[sourceIndex] / 2_147_483_648.0);
                    float right = channels == 1 ? left
                            : (float) (pcm32Buffer[sourceIndex + 1] / 2_147_483_648.0);
                    stereoBuffer[destination++] = left;
                    stereoBuffer[destination++] = right;
                    sourceIndex += channels;
                }
            } else if (encoding == PCM_24_BIT_PACKED) {
                int byteCount = inputSamples * 3;
                if (pcm24Buffer.length < byteCount) {
                    pcm24Buffer = new byte[Math.max(byteCount,
                            pcm24Buffer.length * 2 + 4096)];
                }
                pcm.get(pcm24Buffer, 0, byteCount);
                PcmRateConverter converter = rateConverter;
                if (converter != null) {
                    int maximumFrames = converter.maximumOutputFrames(sourceFrames);
                    int maximumSamples = maximumFrames * 2;
                    if (resampledBuffer.length < maximumSamples) {
                        resampledBuffer = new float[Math.max(maximumSamples,
                                resampledBuffer.length * 2 + 2048)];
                    }
                    int renderFrames = converter.processPcm24(
                            pcm24Buffer, 0, sourceFrames, channels, resampledBuffer);
                    long firstAudibleUs = presentationUs
                            + framesToSkip * 1_000_000L / inputSampleRate;
                    discardBeforeUs = NO_SEEK;
                    if (renderFrames > 0) {
                        renderTapePcm(resampledBuffer, renderFrames, firstAudibleUs);
                    }
                    return;
                }
                int sourceIndex = 0;
                int destination = 0;
                for (int frame = 0; frame < sourceFrames; frame++) {
                    float left = pcm24ToFloat(pcm24Buffer, sourceIndex);
                    float right = channels == 1 ? left
                            : pcm24ToFloat(pcm24Buffer, sourceIndex + 3);
                    stereoBuffer[destination++] = left;
                    stereoBuffer[destination++] = right;
                    sourceIndex += channels * 3;
                }
            } else {
                int destination = 0;
                for (int frame = 0; frame < sourceFrames; frame++) {
                    float left = 0f;
                    float right = 0f;
                    for (int channel = 0; channel < channels; channel++) {
                        float value = readPcmSample(pcm, encoding);
                        if (channel == 0) {
                            left = value;
                        } else if (channel == 1) {
                            right = value;
                        }
                    }
                    if (channels == 1) {
                        right = left;
                    }
                    stereoBuffer[destination++] = left;
                    stereoBuffer[destination++] = right;
                }
            }

            long firstAudibleUs = presentationUs
                    + framesToSkip * 1_000_000L / inputSampleRate;
            discardBeforeUs = NO_SEEK;
            renderStereoPcm(sourceFrames, firstAudibleUs);
        }

        private void writeAlacPcm(int[] decoded,
                                  int byteCount,
                                  int bytesPerSample,
                                  int channels,
                                  long presentationUs) throws Exception {
            presentationUs = Math.max(0L, presentationUs);
            if (bytesPerSample <= 0 || channels <= 0 || byteCount <= 0) {
                return;
            }
            int availableFrames = byteCount / (bytesPerSample * channels);
            int framesToSkip = 0;
            if (discardBeforeUs != NO_SEEK && presentationUs < discardBeforeUs) {
                long missingUs = discardBeforeUs - presentationUs;
                framesToSkip = (int) Math.min(availableFrames,
                        (missingUs * inputSampleRate + 999_999L) / 1_000_000L);
            }
            if (framesToSkip >= availableFrames) {
                return;
            }

            int sourceFrames = availableFrames - framesToSkip;
            int sourceSamples = sourceFrames * 2;
            if (stereoBuffer.length < sourceSamples) {
                stereoBuffer = new float[Math.max(sourceSamples,
                        stereoBuffer.length * 2 + 2048)];
            }
            unpackAlacPcmToStereo(decoded, byteCount, bytesPerSample, channels,
                    framesToSkip, stereoBuffer);
            long firstAudibleUs = presentationUs
                    + framesToSkip * 1_000_000L / inputSampleRate;
            discardBeforeUs = NO_SEEK;
            renderStereoPcm(sourceFrames, firstAudibleUs);
        }

        private void renderStereoPcm(int sourceFrames, long firstAudibleUs) throws Exception {

            float[] renderBuffer = stereoBuffer;
            int renderFrames = sourceFrames;
            PcmRateConverter converter = rateConverter;
            if (converter != null) {
                int maximumFrames = converter.maximumOutputFrames(sourceFrames);
                int maximumSamples = maximumFrames * 2;
                if (resampledBuffer.length < maximumSamples) {
                    resampledBuffer = new float[Math.max(maximumSamples,
                            resampledBuffer.length * 2 + 2048)];
                }
                renderFrames = converter.process(stereoBuffer, sourceFrames, resampledBuffer);
                if (renderFrames <= 0) {
                    return;
                }
                renderBuffer = resampledBuffer;
            }
            renderTapePcm(renderBuffer, renderFrames, firstAudibleUs);
        }

        private void renderTapePcm(float[] renderBuffer,
                                   int renderFrames,
                                   long firstAudibleUs) throws Exception {
            int requiredSamples = renderFrames * 2;
            TapeMachineDsp renderer = dsp;
            if (renderer == null) {
                throw new PlaybackFailure("磁带机渲染器未初始化");
            }
            // A scrub can arrive while PCM is being converted. Do not spend a DSP pass or enqueue
            // stale audio on either side of that race.
            if (hasPendingSeek()) {
                return;
            }
            renderer.process(renderBuffer, renderFrames);
            if (hasPendingSeek()) {
                return;
            }
            if (writeStereoFloat(renderBuffer, requiredSamples, firstAudibleUs)) {
                synchronized (controlLock) {
                    // If a seek/profile rebuild lands after enqueue, requestSeek() either waits
                    // for this short scan and clears it afterwards, or this branch observes the
                    // pending seek and does not reintroduce stale visual levels.
                    if (pendingSeekUs == NO_SEEK && !stopRequested) {
                        audioLevels.recordPcm(firstAudibleUs, outputSampleRate,
                                renderBuffer, renderFrames);
                    }
                }
            }
        }

        private void copyFloatChannelsToStereo(float[] source,
                                               int frameCount,
                                               int channels,
                                               float[] destination) {
            int sourceIndex = 0;
            int output = 0;
            for (int frame = 0; frame < frameCount; frame++) {
                float left = finiteOrZero(source[sourceIndex]);
                float right = channels == 1 ? left : finiteOrZero(source[sourceIndex + 1]);
                destination[output++] = left;
                destination[output++] = right;
                sourceIndex += channels;
            }
        }

        private boolean writeStereoFloat(float[] samples, int sampleCount, long presentationUs)
                throws Exception {
            PcmWriter writer = pcmWriter;
            if (writer == null) {
                throw new PlaybackFailure("音频输出已关闭");
            }
            if (!writer.enqueue(samples, sampleCount, presentationUs)) {
                return false;
            }
            fallbackPositionMs = Math.max(0, presentationUs / 1_000L);
            return true;
        }

        /** @return true after drain, false when a seek should restart decoding. */
        private boolean waitForAudioDrain() throws InterruptedException, PlaybackFailure {
            PcmWriter writer = pcmWriter;
            if (writer != null && !writer.finishInputAndAwaitDrained()) {
                return false;
            }
            AudioTrack output = audioTrack;
            if (output == null || !playbackAnchorSet) {
                return true;
            }
            synchronized (controlLock) {
                if (!outputStarted && stereoFramesWritten > 0 && !paused && !stopRequested) {
                    output.play();
                    outputStarted = true;
                }
            }
            while (!stopRequested) {
                waitWhilePaused();
                if (hasPendingSeek()) {
                    return false;
                }
                long played = unsignedFrameDifference(
                        Integer.toUnsignedLong(output.getPlaybackHeadPosition()),
                        playbackAnchorFrames);
                if (played >= stereoFramesWritten) {
                    return true;
                }
                Thread.sleep(8L);
            }
            return true;
        }

        private void waitWhilePaused() throws InterruptedException {
            synchronized (controlLock) {
                while (paused && pendingSeekUs == NO_SEEK && !stopRequested) {
                    controlLock.wait();
                }
            }
        }

        private boolean hasPendingSeek() {
            synchronized (controlLock) {
                return pendingSeekUs != NO_SEEK;
            }
        }

        private long consumeSeek() {
            synchronized (controlLock) {
                long result = pendingSeekUs;
                pendingSeekUs = NO_SEEK;
                consumedSeekPreservesDsp = pendingSeekPreservesDsp;
                pendingSeekPreservesDsp = false;
                return result;
            }
        }

        private void resetAfterSeek(long seekUs) {
            PcmWriter writer = pcmWriter;
            if (writer != null) {
                // seekTo() clears immediately for responsive scrubbing. Clear once more after the
                // extractor/codec has moved so an already-running DSP block cannot leak across it.
                writer.flushForSeek();
            }
            discardBeforeUs = seekUs;
            playbackAnchorSet = false;
            stereoFramesWritten = 0;
            fallbackPositionMs = seekUs / 1_000L;
            audioLevels.clear();
            TapeMachineDsp renderer = dsp;
            TapeMachineProfile selectedProfile = sessionProfile;
            TapeStockProfile selectedTapeProfile = sessionTapeProfile;
            MachineConditionProfile selectedConditionProfile = sessionConditionProfile;
            if (renderer == null || dspProfileId == null
                    || !dspProfileId.equals(selectedProfile.id)
                    || dspTapeProfileId == null
                    || !dspTapeProfileId.equals(selectedTapeProfile.id)
                    || dspConditionProfileId == null
                    || !dspConditionProfileId.equals(selectedConditionProfile.id)) {
                renderer = TapeMachineDspFactory.create(selectedProfile,
                        selectedTapeProfile, selectedConditionProfile, outputSampleRate);
                dsp = renderer;
                dspProfileId = selectedProfile.id;
                dspTapeProfileId = selectedTapeProfile.id;
                dspConditionProfileId = selectedConditionProfile.id;
                consumedSeekPreservesDsp = false;
            }
            if (renderer != null) {
                if (!consumedSeekPreservesDsp) {
                    renderer.reset();
                }
                renderer.setHighTape(sessionHighTape);
                renderer.setDolbyMode(sessionDolbyMode);
            }
            consumedSeekPreservesDsp = false;
            PcmRateConverter converter = rateConverter;
            if (converter != null) {
                converter.reset();
            }
            AudioTrack output = audioTrack;
            if (output != null) {
                synchronized (controlLock) {
                    try {
                        output.pause();
                        output.flush();
                        // The next successful PCM write primes the track before restarting it.
                        outputStarted = false;
                    } catch (IllegalStateException ignored) {
                        // A subsequent write reports a real output failure if necessary.
                    }
                }
            }
        }

        /**
         * The decoder/DSP producer and the hardware writer deliberately run independently. A
         * MediaCodec, ALAC, resampler or GC burst can now consume the Java reserve without ever
         * making AudioTrack wait for that work on its time-critical thread.
         */
        private final class PcmWriter extends Thread {
            private final AudioTrack output;
            private final Object queueLock = new Object();
            private final float[] ring;
            private final float[] writeBuffer = new float[AUDIO_WRITE_FRAMES * 2];

            private int readIndex;
            private int writeIndex;
            private int queuedSamples;
            private int inFlightSamples;
            private volatile int generation;
            private boolean anchorPending;
            private long anchorUs;
            private volatile boolean stopped;
            private PlaybackFailure failure;

            PcmWriter(AudioTrack output, int sampleRate) {
                super("WalkTape audio writer");
                this.output = output;
                int queueSamples = Math.max(writeBuffer.length * 4,
                        sampleRate * 2 * PCM_QUEUE_MS / 1_000);
                ring = new float[queueSamples & ~1];
            }

            boolean enqueue(float[] source, int requestedSamples, long presentationUs)
                    throws InterruptedException, PlaybackFailure {
                int sampleCount = Math.min(source.length, requestedSamples) & ~1;
                if (sampleCount <= 0) {
                    return true;
                }

                int copied = 0;
                int expectedGeneration;
                synchronized (queueLock) {
                    expectedGeneration = generation;
                    while (copied < sampleCount) {
                        throwIfFailedLocked();
                        while (queuedSamples == ring.length
                                && expectedGeneration == generation
                                && !stopped && !stopRequested) {
                            queueLock.wait();
                            throwIfFailedLocked();
                        }
                        if (stopped || stopRequested || expectedGeneration != generation) {
                            return false;
                        }
                        if (copied == 0 && queuedSamples == 0 && inFlightSamples == 0
                                && !anchorPending && !playbackAnchorSet) {
                            anchorPending = true;
                            anchorUs = Math.max(0L, presentationUs);
                        }

                        int available = ring.length - queuedSamples;
                        int contiguous = ring.length - writeIndex;
                        int amount = Math.min(sampleCount - copied,
                                Math.min(available, contiguous));
                        System.arraycopy(source, copied, ring, writeIndex, amount);
                        copied += amount;
                        writeIndex = (writeIndex + amount) % ring.length;
                        queuedSamples += amount;
                        queueLock.notifyAll();
                    }
                }
                return true;
            }

            void flushForSeek() {
                synchronized (queueLock) {
                    generation++;
                    readIndex = 0;
                    writeIndex = 0;
                    queuedSamples = 0;
                    inFlightSamples = 0;
                    anchorPending = false;
                    queueLock.notifyAll();
                }
            }

            boolean finishInputAndAwaitDrained()
                    throws InterruptedException, PlaybackFailure {
                synchronized (queueLock) {
                    int expectedGeneration = generation;
                    throwIfFailedLocked();
                    while ((queuedSamples > 0 || inFlightSamples > 0)
                            && expectedGeneration == generation
                            && !stopped && !stopRequested) {
                        queueLock.wait();
                        throwIfFailedLocked();
                    }
                    throwIfFailedLocked();
                    return expectedGeneration == generation && !stopped && !stopRequested;
                }
            }

            void requestStop() {
                synchronized (queueLock) {
                    stopped = true;
                    generation++;
                    readIndex = 0;
                    writeIndex = 0;
                    queuedSamples = 0;
                    inFlightSamples = 0;
                    anchorPending = false;
                    queueLock.notifyAll();
                }
                interrupt();
            }

            void awaitStop(long timeoutMs) {
                long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
                boolean interrupted = false;
                while (isAlive()) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0L) {
                        break;
                    }
                    try {
                        join(remaining);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void run() {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                    while (!stopped && !stopRequested) {
                        int samples;
                        int blockGeneration;
                        boolean ownsAnchor;
                        long blockAnchorUs;
                        synchronized (queueLock) {
                            while (queuedSamples == 0 && !stopped && !stopRequested) {
                                queueLock.wait();
                            }
                            if (stopped || stopRequested) {
                                return;
                            }
                            blockGeneration = generation;
                            samples = Math.min(queuedSamples, writeBuffer.length);
                            int first = Math.min(samples, ring.length - readIndex);
                            System.arraycopy(ring, readIndex, writeBuffer, 0, first);
                            if (first < samples) {
                                System.arraycopy(ring, 0, writeBuffer, first, samples - first);
                            }
                            readIndex = (readIndex + samples) % ring.length;
                            queuedSamples -= samples;
                            inFlightSamples = samples;
                            ownsAnchor = anchorPending;
                            blockAnchorUs = anchorUs;
                            anchorPending = false;
                            queueLock.notifyAll();
                        }

                        writeBlock(samples, blockGeneration, ownsAnchor, blockAnchorUs);
                        synchronized (queueLock) {
                            if (blockGeneration == generation) {
                                inFlightSamples = 0;
                            }
                            queueLock.notifyAll();
                        }
                    }
                } catch (InterruptedException ignored) {
                    // requestStop() interrupts pause/queue waits; ownership thread releases output.
                } catch (Throwable error) {
                    PlaybackFailure playbackFailure = error instanceof PlaybackFailure
                            ? (PlaybackFailure) error
                            : new PlaybackFailure("AudioTrack 写入失败："
                                    + (error.getMessage() == null
                                    ? error.getClass().getSimpleName() : error.getMessage()), error);
                    synchronized (queueLock) {
                        if (!stopped && !stopRequested) {
                            failure = playbackFailure;
                        }
                    }
                } finally {
                    synchronized (queueLock) {
                        stopped = true;
                        queuedSamples = 0;
                        inFlightSamples = 0;
                        queueLock.notifyAll();
                    }
                }
            }

            private void writeBlock(int sampleCount,
                                    int blockGeneration,
                                    boolean ownsAnchor,
                                    long blockAnchorUs) throws Exception {
                int written = 0;
                while (written < sampleCount && !stopped && !stopRequested
                        && blockGeneration == generation) {
                    waitWhilePaused();
                    if (stopped || stopRequested || blockGeneration != generation
                            || hasPendingSeek()) {
                        return;
                    }

                    if (ownsAnchor) {
                        synchronized (controlLock) {
                            if (blockGeneration == generation && pendingSeekUs == NO_SEEK
                                    && !stopped && !stopRequested && !playbackAnchorSet) {
                                playbackAnchorFrames = Integer.toUnsignedLong(
                                        output.getPlaybackHeadPosition());
                                mediaAnchorUs = blockAnchorUs;
                                playbackAnchorSet = true;
                                stereoFramesWritten = 0L;
                            }
                        }
                        ownsAnchor = false;
                    }

                    int result = output.write(writeBuffer, written, sampleCount - written,
                            AudioTrack.WRITE_BLOCKING);
                    if (result < 0) {
                        if (stopped || stopRequested || blockGeneration != generation
                                || hasPendingSeek()) {
                            return;
                        }
                        throw new PlaybackFailure("AudioTrack 写入失败：" + result);
                    }
                    if (result == 0) {
                        Thread.yield();
                        continue;
                    }
                    written += result;

                    synchronized (controlLock) {
                        if (blockGeneration != generation || stopped || stopRequested) {
                            return;
                        }
                        stereoFramesWritten += result / 2L;
                        long primeFrames = outputSampleRate * audioPrimeMs / 1_000L;
                        if (!outputStarted && !paused && pendingSeekUs == NO_SEEK
                                && playbackAnchorSet && stereoFramesWritten >= primeFrames) {
                            output.play();
                            outputStarted = true;
                            audioPrimeMs = AUDIO_PRIME_MS;
                        }
                    }
                }
            }

            private void throwIfFailedLocked() throws PlaybackFailure {
                if (failure != null) {
                    throw failure;
                }
            }
        }

        private void rejectEncryptedSample() throws PlaybackFailure {
            if (extractor != null && isEncryptedSample(
                    extractor.getSampleTrackIndex(), extractor.getSampleFlags())) {
                throw new PlaybackFailure("这首歌带有 DRM/加密，无法作为本地磁带渲染");
            }
        }

        private String readableFailure(Throwable error) {
            if (error instanceof PlaybackFailure && error.getMessage() != null) {
                return error.getMessage();
            }
            StringBuilder message = new StringBuilder("无法解码");
            if (sourceMime != null) {
                message.append(' ').append(friendlyMime(sourceMime));
                message.append("（").append(sourceMime).append('）');
            } else {
                message.append("这首音频");
            }
            if (decoderName != null) {
                message.append("，解码器 ").append(decoderName);
            }
            String detail;
            if (error instanceof MediaCodec.CodecException) {
                detail = ((MediaCodec.CodecException) error).getDiagnosticInfo();
            } else {
                detail = error.getMessage();
            }
            if (detail != null && !detail.trim().isEmpty()) {
                message.append("：").append(detail.trim());
            }
            return message.toString();
        }

        private void releaseDecoderResources() {
            audioLevels.clear();
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException ignored) {
                    // Codec may have failed before start completed.
                }
                codec.release();
                codec = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
            releaseAudioTrack();
            dsp = null;
            rateConverter = null;
            predecessor = null;
            // Drop reusable PCM workspaces immediately. A terminated Thread object can remain
            // reachable briefly from ART; it must not pin megabytes of decoded audio until the
            // next full GC after a track change.
            stereoBuffer = null;
            resampledBuffer = null;
            pcm16Buffer = null;
            pcmFloatBuffer = null;
            pcm32Buffer = null;
            pcm24Buffer = null;
        }

        private void releaseAudioTrack() {
            PcmWriter writer = pcmWriter;
            pcmWriter = null;
            AudioTrack output = audioTrack;
            audioTrack = null;
            outputStarted = false;
            if (writer != null) {
                writer.requestStop();
            }
            if (output == null) {
                if (writer != null) {
                    writer.awaitStop(AUDIO_WRITER_JOIN_MS);
                }
                return;
            }
            try {
                output.pause();
                output.flush();
            } catch (IllegalStateException ignored) {
                // release() is valid even if initialization or playback failed.
            }
            if (writer != null) {
                writer.awaitStop(AUDIO_WRITER_JOIN_MS);
            }
            try {
                output.stop();
            } catch (IllegalStateException ignored) {
                // A paused or never-started track can reject stop(); release remains valid.
            }
            output.release();
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isRawPcm(String mime) {
        return "audio/raw".equals(mime)
                || "audio/pcm".equals(mime)
                || "audio/wav".equals(mime)
                || "audio/x-wav".equals(mime);
    }

    private static boolean isAlac(String mime) {
        return "audio/alac".equals(mime) || "audio/x-alac".equals(mime);
    }

    static void packAlacPcm(int[] source,
                            int byteCount,
                            int bytesPerSample,
                            byte[] destination) {
        if (byteCount < 0 || byteCount > destination.length) {
            throw new IllegalArgumentException("Invalid ALAC PCM byte count");
        }
        if (bytesPerSample == 2) {
            int sampleCount = byteCount / 2;
            if (sampleCount > source.length) {
                throw new IllegalArgumentException("ALAC sample buffer is too small");
            }
            int output = 0;
            for (int sample = 0; sample < sampleCount; sample++) {
                int value = source[sample];
                destination[output++] = (byte) value;
                destination[output++] = (byte) (value >>> 8);
            }
            return;
        }
        if (bytesPerSample == 3) {
            if (byteCount > source.length) {
                throw new IllegalArgumentException("ALAC sample buffer is too small");
            }
            for (int index = 0; index < byteCount; index++) {
                destination[index] = (byte) source[index];
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported ALAC sample width: " + bytesPerSample);
    }

    static int unpackAlacPcmToStereo(int[] source,
                                     int byteCount,
                                     int bytesPerSample,
                                     int channels,
                                     int frameOffset,
                                     float[] destination) {
        if (source == null || destination == null || byteCount < 0
                || (bytesPerSample != 2 && bytesPerSample != 3)
                || channels < 1 || channels > 2 || frameOffset < 0) {
            throw new IllegalArgumentException("Invalid ALAC PCM buffer");
        }
        int totalFrames = byteCount / (bytesPerSample * channels);
        if (frameOffset > totalFrames) {
            throw new IllegalArgumentException("ALAC frame offset exceeds packet");
        }
        int frameCount = totalFrames - frameOffset;
        if (destination.length < frameCount * 2) {
            throw new IllegalArgumentException("Stereo output buffer is too small");
        }

        int output = 0;
        if (bytesPerSample == 2) {
            int requiredSamples = totalFrames * channels;
            if (requiredSamples > source.length) {
                throw new IllegalArgumentException("ALAC sample buffer is too small");
            }
            int sourceIndex = frameOffset * channels;
            for (int frame = 0; frame < frameCount; frame++) {
                float left = source[sourceIndex] / 32_768f;
                float right = channels == 1 ? left : source[sourceIndex + 1] / 32_768f;
                destination[output++] = left;
                destination[output++] = right;
                sourceIndex += channels;
            }
            return frameCount;
        }

        if (byteCount > source.length) {
            throw new IllegalArgumentException("ALAC byte buffer is too small");
        }
        int sourceIndex = frameOffset * channels * 3;
        for (int frame = 0; frame < frameCount; frame++) {
            float left = alac24ToFloat(source, sourceIndex);
            float right = channels == 1 ? left : alac24ToFloat(source, sourceIndex + 3);
            destination[output++] = left;
            destination[output++] = right;
            sourceIndex += channels * 3;
        }
        return frameCount;
    }

    private static float alac24ToFloat(int[] source, int offset) {
        int packed = (source[offset] & 0xff)
                | ((source[offset + 1] & 0xff) << 8)
                | ((source[offset + 2] & 0xff) << 16);
        if ((packed & 0x0080_0000) != 0) {
            packed |= 0xff00_0000;
        }
        return packed / 8_388_608f;
    }

    private static float pcm24ToFloat(byte[] source, int offset) {
        int packed = (source[offset] & 0xff)
                | ((source[offset + 1] & 0xff) << 8)
                | (source[offset + 2] << 16);
        return packed / 8_388_608f;
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0f;
    }

    private static String friendlyMime(String mime) {
        if (mime == null) {
            return "音频";
        }
        switch (mime) {
            case "audio/mp4a-latm":
            case "audio/aac":
                return "AAC / M4A";
            case "audio/flac":
            case "audio/x-flac":
                return "FLAC";
            case "audio/mpeg":
                return "MP3";
            case "audio/vorbis":
                return "Ogg Vorbis";
            case "audio/opus":
                return "Opus";
            case "audio/alac":
            case "audio/x-alac":
                return "ALAC / M4A";
            case "audio/raw":
            case "audio/pcm":
            case "audio/wav":
            case "audio/x-wav":
                return "WAV / PCM";
            default:
                return mime;
        }
    }

    private static int formatInt(MediaFormat format, String key, int fallback) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : fallback;
        } catch (ClassCastException | NullPointerException ignored) {
            return fallback;
        }
    }

    private static long formatLong(MediaFormat format, String key, long fallback) {
        try {
            return format.containsKey(key) ? format.getLong(key) : fallback;
        } catch (ClassCastException | NullPointerException ignored) {
            return fallback;
        }
    }

    private static int bytesPerSample(int encoding) {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                return 1;
            case AudioFormat.ENCODING_PCM_16BIT:
                return 2;
            case AudioFormat.ENCODING_PCM_FLOAT:
            case PCM_32_BIT:
                return 4;
            case PCM_24_BIT_PACKED:
                return 3;
            default:
                return 0;
        }
    }

    private static float readPcmSample(ByteBuffer source, int encoding) {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                return ((source.get() & 0xff) - 128) / 128f;
            case AudioFormat.ENCODING_PCM_16BIT:
                return source.getShort() / 32_768f;
            case AudioFormat.ENCODING_PCM_FLOAT:
                float value = source.getFloat();
                return Float.isFinite(value) ? value : 0f;
            case PCM_24_BIT_PACKED:
                int low = source.get() & 0xff;
                int middle = source.get() & 0xff;
                int high = source.get();
                int packed = low | (middle << 8) | (high << 16);
                return packed / 8_388_608f;
            case PCM_32_BIT:
                return (float) (source.getInt() / 2_147_483_648.0);
            default:
                return 0f;
        }
    }

    private static long unsignedFrameDifference(long current, long origin) {
        return current >= origin ? current - origin : (1L << 32) - origin + current;
    }

    static boolean isEncryptedSample(int sampleTrackIndex, int sampleFlags) {
        // At end-of-stream MediaExtractor reports no current track. Some MP4/ALAC extractors also
        // return -1 for flags there; bit-testing -1 used to manufacture a false DRM result exactly
        // when automatic-next needed the completion callback.
        return sampleTrackIndex >= 0
                && (sampleFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0;
    }

    private static int chooseRenderSampleRate(int sourceSampleRate) {
        if (sourceSampleRate <= 48_000) {
            return sourceSampleRate;
        }
        // Preserve the 44.1 kHz family exactly; the 48 kHz family and uncommon high-resolution
        // rates use the Android-native 48 kHz output clock.
        return sourceSampleRate % 44_100 == 0 ? 44_100 : 48_000;
    }

    /**
     * Streaming anti-alias FIR decimator for high-resolution sources.
     *
     * <p>No current reference machine reproduces content above 15 kHz, so running the nonlinear
     * and mechanical model at 96/192 kHz wastes most of the phone's CPU on an inaudible band.
     * This converter keeps
     * the complete machine bandwidth, rejects ultrasonic aliases, and preserves phase continuity
     * across decoder buffers.</p>
     */
    static final class PcmRateConverter {
        private static final int TAP_COUNT = 16;
        private static final int RING_SIZE = 32;
        private static final int RING_MASK = RING_SIZE - 1;

        private final int inputRate;
        private final int outputRate;
        private final float[] coefficients = new float[TAP_COUNT];
        private final float[] ringLeft = new float[RING_SIZE];
        private final float[] ringRight = new float[RING_SIZE];
        private int ringWrite;
        private int phase;

        PcmRateConverter(int inputRate, int outputRate) {
            if (inputRate <= outputRate || outputRate <= 0) {
                throw new IllegalArgumentException("Expected a positive downsampling ratio");
            }
            this.inputRate = inputRate;
            this.outputRate = outputRate;
            designLowPass();
        }

        int maximumOutputFrames(int inputFrames) {
            return (int) (((long) Math.max(0, inputFrames) * outputRate + phase)
                    / inputRate) + 1;
        }

        int process(float[] input, int inputFrames, float[] output) {
            if (inputFrames < 0 || inputFrames * 2 > input.length) {
                throw new IllegalArgumentException("Invalid input frame count");
            }
            int outputFrames = 0;
            for (int frame = 0; frame < inputFrames; frame++) {
                int source = frame * 2;
                ringLeft[ringWrite] = input[source];
                ringRight[ringWrite] = input[source + 1];
                ringWrite = (ringWrite + 1) & RING_MASK;

                phase += outputRate;
                if (phase < inputRate) {
                    continue;
                }
                phase -= inputRate;
                int destination = outputFrames * 2;
                if (destination + 1 >= output.length) {
                    throw new IllegalArgumentException("Output buffer is too small");
                }
                float left = 0f;
                float right = 0f;
                int newest = (ringWrite - 1) & RING_MASK;
                for (int tap = 0; tap < TAP_COUNT; tap++) {
                    int history = (newest - tap) & RING_MASK;
                    float coefficient = coefficients[tap];
                    left += ringLeft[history] * coefficient;
                    right += ringRight[history] * coefficient;
                }
                output[destination] = left;
                output[destination + 1] = right;
                outputFrames++;
            }
            return outputFrames;
        }

        /** Fused signed-16 conversion and FIR decimation; see {@link #processPcm24}. */
        int processPcm16(short[] input,
                         int inputOffset,
                         int inputFrames,
                         int channels,
                         float[] output) {
            if (inputFrames < 0 || inputOffset < 0 || channels <= 0
                    || inputOffset + inputFrames * channels > input.length) {
                throw new IllegalArgumentException("Invalid signed 16-bit input");
            }
            int outputFrames = 0;
            int source = inputOffset;
            for (int frame = 0; frame < inputFrames; frame++) {
                float left = input[source] / 32_768f;
                float right = channels == 1 ? left : input[source + 1] / 32_768f;
                source += channels;
                ringLeft[ringWrite] = left;
                ringRight[ringWrite] = right;
                ringWrite = (ringWrite + 1) & RING_MASK;

                phase += outputRate;
                if (phase < inputRate) {
                    continue;
                }
                phase -= inputRate;
                int destination = outputFrames * 2;
                if (destination + 1 >= output.length) {
                    throw new IllegalArgumentException("Output buffer is too small");
                }
                float filteredLeft = 0f;
                float filteredRight = 0f;
                int newest = (ringWrite - 1) & RING_MASK;
                for (int tap = 0; tap < TAP_COUNT; tap++) {
                    int history = (newest - tap) & RING_MASK;
                    float coefficient = coefficients[tap];
                    filteredLeft += ringLeft[history] * coefficient;
                    filteredRight += ringRight[history] * coefficient;
                }
                output[destination] = filteredLeft;
                output[destination + 1] = filteredRight;
                outputFrames++;
            }
            return outputFrames;
        }

        /**
         * Fused packed-24 conversion and FIR decimation. It is sample-identical to unpacking into
         * a temporary stereo float array and calling {@link #process(float[], int, float[])}, but
         * avoids writing and rereading every 192 kHz float before the 48 kHz tape renderer.
         */
        int processPcm24(byte[] input,
                         int inputOffset,
                         int inputFrames,
                         int channels,
                         float[] output) {
            if (inputFrames < 0 || inputOffset < 0 || channels <= 0
                    || inputOffset + inputFrames * channels * 3 > input.length) {
                throw new IllegalArgumentException("Invalid packed 24-bit input");
            }
            int outputFrames = 0;
            int source = inputOffset;
            int frameBytes = channels * 3;
            for (int frame = 0; frame < inputFrames; frame++) {
                int leftPacked = (input[source] & 0xff)
                        | ((input[source + 1] & 0xff) << 8)
                        | (input[source + 2] << 16);
                float left = leftPacked / 8_388_608f;
                float right = left;
                if (channels > 1) {
                    int rightSource = source + 3;
                    int rightPacked = (input[rightSource] & 0xff)
                            | ((input[rightSource + 1] & 0xff) << 8)
                            | (input[rightSource + 2] << 16);
                    right = rightPacked / 8_388_608f;
                }
                source += frameBytes;
                ringLeft[ringWrite] = left;
                ringRight[ringWrite] = right;
                ringWrite = (ringWrite + 1) & RING_MASK;

                phase += outputRate;
                if (phase < inputRate) {
                    continue;
                }
                phase -= inputRate;
                int destination = outputFrames * 2;
                if (destination + 1 >= output.length) {
                    throw new IllegalArgumentException("Output buffer is too small");
                }
                float filteredLeft = 0f;
                float filteredRight = 0f;
                int newest = (ringWrite - 1) & RING_MASK;
                for (int tap = 0; tap < TAP_COUNT; tap++) {
                    int history = (newest - tap) & RING_MASK;
                    float coefficient = coefficients[tap];
                    filteredLeft += ringLeft[history] * coefficient;
                    filteredRight += ringRight[history] * coefficient;
                }
                output[destination] = filteredLeft;
                output[destination + 1] = filteredRight;
                outputFrames++;
            }
            return outputFrames;
        }

        void reset() {
            Arrays.fill(ringLeft, 0f);
            Arrays.fill(ringRight, 0f);
            ringWrite = 0;
            phase = 0;
        }

        private void designLowPass() {
            double cutoffHz = Math.min(12_500.0, outputRate * 0.34);
            double normalisedCutoff = cutoffHz / inputRate;
            double centre = (TAP_COUNT - 1) * 0.5;
            double sum = 0.0;
            for (int tap = 0; tap < TAP_COUNT; tap++) {
                double distance = tap - centre;
                double ideal = Math.abs(distance) < 1e-12
                        ? 2.0 * normalisedCutoff
                        : Math.sin(2.0 * Math.PI * normalisedCutoff * distance)
                        / (Math.PI * distance);
                double window = 0.42
                        - 0.5 * Math.cos(2.0 * Math.PI * tap / (TAP_COUNT - 1))
                        + 0.08 * Math.cos(4.0 * Math.PI * tap / (TAP_COUNT - 1));
                coefficients[tap] = (float) (ideal * window);
                sum += coefficients[tap];
            }
            for (int tap = 0; tap < TAP_COUNT; tap++) {
                coefficients[tap] /= (float) sum;
            }
        }
    }

    private static final class PlaybackFailure extends Exception {
        PlaybackFailure(String message) {
            super(message);
        }

        PlaybackFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
