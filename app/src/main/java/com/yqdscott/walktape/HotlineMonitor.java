package com.yqdscott.walktape;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;

/** Low-latency microphone monitor that recreates the TPS-L2 HOT LINE signal path. */
final class HotlineMonitor {

    enum StartResult {
        STARTED,
        NEED_HEADPHONES,
        AUDIO_UNAVAILABLE
    }

    interface Listener {
        void onMonitorStopped(String error);
    }

    private static final int FALLBACK_SAMPLE_RATE = 48_000;

    private final Context appContext;
    private final AudioManager audioManager;
    private final Listener listener;
    private MonitorThread worker;

    HotlineMonitor(Context context, Listener listener) {
        appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    synchronized StartResult start() {
        if (worker != null && worker.isAlive()) {
            return StartResult.STARTED;
        }
        if (!hasPrivateOutput()) {
            return StartResult.NEED_HEADPHONES;
        }

        int sampleRate = preferredSampleRate();
        int inputMinimum = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int outputMinimum = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (inputMinimum <= 0 || outputMinimum <= 0) {
            return StartResult.AUDIO_UNAVAILABLE;
        }

        AudioRecord input = null;
        AudioTrack output = null;
        try {
            int source = supportsUnprocessedInput()
                    ? MediaRecorder.AudioSource.UNPROCESSED
                    : MediaRecorder.AudioSource.VOICE_RECOGNITION;
            input = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(inputMinimum * 2, sampleRate / 5 * 2))
                    .build();

            AudioTrack.Builder outputBuilder = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(outputMinimum * 2, sampleRate / 10 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                outputBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            output = outputBuilder.build();

            if (input.getState() != AudioRecord.STATE_INITIALIZED
                    || output.getState() != AudioTrack.STATE_INITIALIZED) {
                release(input, output);
                return StartResult.AUDIO_UNAVAILABLE;
            }
            preferBuiltInMicrophone(input);

            int chunkFrames = Math.max(240, Math.min(960, inputMinimum / 2));
            MonitorThread next = new MonitorThread(input, output, sampleRate, chunkFrames);
            worker = next;
            next.start();
            return StartResult.STARTED;
        } catch (RuntimeException unavailable) {
            release(input, output);
            return StartResult.AUDIO_UNAVAILABLE;
        }
    }

    synchronized void stop() {
        MonitorThread active = worker;
        worker = null;
        if (active != null) {
            active.requestStop();
        }
    }

    synchronized boolean isRunning() {
        return worker != null && worker.isAlive();
    }

    private boolean hasPrivateOutput() {
        if (audioManager == null) {
            return false;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET
                    || type == AudioDeviceInfo.TYPE_HEARING_AID
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                return true;
            }
        }
        return false;
    }

    private int preferredSampleRate() {
        if (audioManager != null) {
            String value = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            try {
                int parsed = Integer.parseInt(value);
                if (parsed >= 8_000 && parsed <= 192_000) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Use Android's overwhelmingly common native output rate.
            }
        }
        return FALLBACK_SAMPLE_RATE;
    }

    private boolean supportsUnprocessedInput() {
        return audioManager != null && "true".equalsIgnoreCase(audioManager.getProperty(
                AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
    }

    private void preferBuiltInMicrophone(AudioRecord input) {
        if (audioManager == null) {
            return;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                input.setPreferredDevice(device);
                return;
            }
        }
    }

    private synchronized void finished(MonitorThread source, String error) {
        if (worker != source) {
            return;
        }
        worker = null;
        listener.onMonitorStopped(error);
    }

    private static void release(AudioRecord input, AudioTrack output) {
        if (input != null) {
            input.release();
        }
        if (output != null) {
            output.release();
        }
    }

    private final class MonitorThread extends Thread {
        private final AudioRecord input;
        private final AudioTrack output;
        private final short[] samples;
        private final MicDsp dsp;
        private volatile boolean stopping;

        MonitorThread(AudioRecord input, AudioTrack output, int sampleRate, int chunkFrames) {
            super("WalkTape HOT LINE monitor");
            this.input = input;
            this.output = output;
            samples = new short[chunkFrames];
            dsp = new MicDsp(sampleRate);
        }

        @Override
        public void run() {
            String failure = null;
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                input.startRecording();
                if (input.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("麦克风没有进入监听状态");
                }
                boolean outputStarted = false;
                while (!stopping) {
                    int count = input.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                    if (count < 0) {
                        if (!stopping) {
                            throw new IllegalStateException("麦克风读取失败：" + count);
                        }
                        break;
                    }
                    if (count == 0) {
                        continue;
                    }
                    dsp.process(samples, count);
                    int written = 0;
                    while (written < count && !stopping) {
                        int result = output.write(samples, written, count - written,
                                AudioTrack.WRITE_BLOCKING);
                        if (result < 0) {
                            throw new IllegalStateException("监听输出失败：" + result);
                        }
                        written += result;
                    }
                    if (!outputStarted && written > 0 && !stopping) {
                        output.play();
                        outputStarted = true;
                    }
                }
            } catch (RuntimeException error) {
                if (!stopping) {
                    failure = error.getMessage() == null ? "HOT LINE 音频通路已中断"
                            : error.getMessage();
                }
            } finally {
                try {
                    input.stop();
                } catch (RuntimeException ignored) {
                    // Already stopped while unblocking a read.
                }
                try {
                    output.pause();
                    output.flush();
                } catch (RuntimeException ignored) {
                    // Release remains safe after a route change.
                }
                release(input, output);
                finished(this, failure);
            }
        }

        void requestStop() {
            stopping = true;
            try {
                input.stop();
            } catch (RuntimeException ignored) {
                // The worker may still be between construction and startRecording().
            }
            interrupt();
        }
    }

    /** Electret-like HOT LINE preamp: speech band limiting, fixed gain and a soft analog knee. */
    static final class MicDsp {
        private final float highPassPole;
        private final float lowPassBlend;
        private final float preampGain;
        private float previousInput;
        private float highPassState;
        private float lowPassState;

        MicDsp(int sampleRate) {
            // S801 routes C-1004Q through the documented C801/R814-R815 coupling branch. The
            // electret capsule's unpublished capacitance is represented by the measured healthy
            // speech-band target, while gain/loading is derived from the visible resistor chain.
            double inputResistance = TpsL2Schematic.value("R814")
                    + TpsL2Schematic.value("R815");
            double couplingCorner = 1.0 / (Math.PI * 2.0 * inputResistance
                    * TpsL2Schematic.value("C801"));
            highPassPole = (float) Math.exp(-2.0 * Math.PI
                    * Math.max(105.0, couplingCorner) / sampleRate);
            double outputResistance = 1.0 / (1.0 / TpsL2Schematic.value("R810")
                    + 1.0 / TpsL2Schematic.value("R811"));
            double capsuleAndWiringFarads = TpsL2Schematic.value("P-MIC-C");
            double electricalCorner = 1.0 / (Math.PI * 2.0 * outputResistance
                    * capsuleAndWiringFarads);
            lowPassBlend = 1f - (float) Math.exp(-2.0 * Math.PI
                    * Math.min(5_800.0, electricalCorner) / sampleRate);
            float dividerGain = TpsL2Schematic.value("R810")
                    / (TpsL2Schematic.value("R810") + TpsL2Schematic.value("R812"));
            preampGain = 1.72f + dividerGain * 0.768f;
        }

        void process(short[] pcm, int sampleCount) {
            for (int index = 0; index < sampleCount; index++) {
                float input = pcm[index] / 32_768f;
                highPassState = input - previousInput + highPassPole * highPassState;
                previousInput = input;
                lowPassState += lowPassBlend * (highPassState - lowPassState);

                float driven = lowPassState * preampGain;
                float shaped = driven / (1f + 0.42f * Math.abs(driven));
                int output = Math.round(shaped * 29_500f);
                pcm[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, output));
            }
        }
    }
}
