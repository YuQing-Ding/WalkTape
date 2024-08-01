package com.yqdscott.walktape;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.TarsosDSPAudioFloatConverter;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import be.tarsos.dsp.resample.RateTransposer;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_READ_EXTERNAL_STORAGE = 1;

    private MusicAdapter musicAdapter;
    private List<String> musicFiles;
    private SeekBar progressBar;
    private Handler handler;
    private Runnable progressRunnable;
    private Button playButton;
    private Button stopButton;
    private String currentFilePath;
    private boolean isPlaying = false;
    private int durationMs = 0;
    private int currentPositionMs = 0;
    private boolean isPaused = false;
    private boolean isSeeking = false;

    private MediaExtractor extractor;
    private MediaCodec codec;
    private AudioTrack audioTrack;
    private Thread playbackThread;

    private List<AudioProcessor> currentEffectChain;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_CODE_READ_EXTERNAL_STORAGE);
        } else {
            setupUI();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_READ_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupUI();
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupUI() {
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        musicFiles = new ArrayList<>();
        musicAdapter = new MusicAdapter(musicFiles, this::playMusicFile);
        recyclerView.setAdapter(musicAdapter);

        Button scanButton = findViewById(R.id.scan_button);
        scanButton.setOnClickListener(v -> scanMusicFiles());

        RadioGroup effectGroup = findViewById(R.id.effect_group);
        effectGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.effect_a) {
                currentEffectChain = getEffectChain(EffectType.TYPE_A);
            } else if (checkedId == R.id.effect_b) {
                currentEffectChain = getEffectChain(EffectType.TYPE_B);
            } else if (checkedId == R.id.effect_c) {
                currentEffectChain = getEffectChain(EffectType.TYPE_C);
            }
            // Always add gain and limiter to the effect chain
            currentEffectChain.add(new CustomGain(1.48f)); // Adjust gain as needed
            currentEffectChain.add(new CustomLimiter(0.9f));
        });

        RadioButton defaultEffect = findViewById(R.id.effect_a);
        defaultEffect.setChecked(true);
        currentEffectChain = getEffectChain(EffectType.TYPE_A);
        // Always add gain and limiter to the effect chain
        currentEffectChain.add(new CustomGain(1.48f)); // Adjust gain as needed
        currentEffectChain.add(new CustomLimiter(0.9f)); // Adjust limiter threshold as needed

        progressBar = findViewById(R.id.progress_bar);
        playButton = findViewById(R.id.play_button);
        stopButton = findViewById(R.id.stop_button);

        playButton.setOnClickListener(v -> togglePlayPause());
        stopButton.setOnClickListener(v -> stopMusic());

        handler = new Handler();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && audioTrack != null && !isSeeking) {
                    try {
                        currentPositionMs = (int) ((audioTrack.getPlaybackHeadPosition() / (float) audioTrack.getSampleRate()) * 1000);
                        progressBar.setProgress(currentPositionMs);
                    } catch (IllegalStateException e) {
                        // Handle exception if audioTrack is released
                        handler.removeCallbacks(this);
                    }
                    handler.postDelayed(this, 1000);
                }
            }
        };

        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isPlaying) {
                    isSeeking = true;
                    currentPositionMs = progress;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (isPlaying) {
                    isSeeking = false;
                    seekTo(currentPositionMs);
                }
            }
        });
    }

    private void scanMusicFiles() {
        ContentResolver contentResolver = getContentResolver();
        Cursor cursor = contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA},
                null,
                null,
                null
        );

        if (cursor != null) {
            musicFiles.clear();
            if (cursor.moveToFirst()) {
                int titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                do {
                    String title = cursor.getString(titleColumn);
                    String filePath = cursor.getString(dataColumn);
                    musicFiles.add(title + "\n" + filePath);
                } while (cursor.moveToNext());
            }
            cursor.close();
            musicAdapter.notifyDataSetChanged();
        } else {
            Toast.makeText(this, "No music files found", Toast.LENGTH_SHORT).show();
        }
    }

    private void playMusicFile(String filePath) {
        stopMusic(); // Stop any current playing music

        currentFilePath = filePath;
        durationMs = getMusicDuration(filePath);
        progressBar.setMax(durationMs);
        isPlaying = true;
        isPaused = false;
        startPlayback(filePath, 0);
        playButton.setText("Pause");
        handler.post(progressRunnable);
    }

    private int getMusicDuration(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(filePath);
            MediaFormat format = extractor.getTrackFormat(0);
            return (int) (format.getLong(MediaFormat.KEY_DURATION) / 1000);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            extractor.release();
        }
        return 0;
    }

    private void startPlayback(String filePath, int startPositionMs) {
        playbackThread = new Thread(() -> {
            extractor = new MediaExtractor();
            try {
                extractor.setDataSource(filePath);
                MediaFormat format = extractor.getTrackFormat(0);
                extractor.selectTrack(0);

                String mime = format.getString(MediaFormat.KEY_MIME);
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, null, null, 0);

                codec.start();

                int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
                int minBufferSize = AudioTrack.getMinBufferSize(22050, AudioFormat.CHANNEL_OUT_MONO, audioFormat);

                audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        22050, // Target Sample Rate
                        AudioFormat.CHANNEL_OUT_MONO, // Mono
                        audioFormat,
                        minBufferSize,
                        AudioTrack.MODE_STREAM
                );
                audioTrack.play();

                extractor.seekTo(startPositionMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                TarsosDSPAudioFormat tarsosFormat = new TarsosDSPAudioFormat(sampleRate, 16, channelCount, true, false);
                TarsosDSPAudioFormat targetFormat = new TarsosDSPAudioFormat(22050, 16, 1, true, false);

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                boolean isEOS = false;

                RateTransposer rateTransposer = new RateTransposer((float) 22050 / sampleRate);
                TarsosDSPAudioFloatConverter converter = TarsosDSPAudioFloatConverter.getConverter(tarsosFormat);

                // Add a short delay to ensure that AudioTrack initialization is complete
                Thread.sleep(500);

                while (!Thread.interrupted() && !isEOS) {
                    if (isPaused) {
                        synchronized (playbackThread) {
                            try {
                                playbackThread.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }

                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                isEOS = true;
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }

                    int outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            break;
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            MediaFormat newFormat = codec.getOutputFormat();
                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            break;
                        default:
                            ByteBuffer outBuffer = codec.getOutputBuffer(outIndex);
                            if (outBuffer != null) {
                                byte[] chunk = new byte[bufferInfo.size];
                                outBuffer.get(chunk);
                                outBuffer.clear();

                                // Convert to Floating Point
                                short[] shortBuffer = new short[chunk.length / 2];
                                ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer);
                                float[] floatBuffer = new float[shortBuffer.length];
                                for (int i = 0; i < shortBuffer.length; i++) {
                                    floatBuffer[i] = shortBuffer[i] / (float) Short.MAX_VALUE;
                                }

                                // Stereo to Mono
                                if (channelCount == 2) {
                                    float[] monoBuffer = new float[floatBuffer.length / 2];
                                    for (int i = 0; i < monoBuffer.length; i++) {
                                        monoBuffer[i] = (floatBuffer[2 * i] + floatBuffer[2 * i + 1]) / 2;
                                    }
                                    floatBuffer = monoBuffer;
                                }

                                // resample
                                AudioEvent audioEvent = new AudioEvent(targetFormat);
                                audioEvent.setFloatBuffer(floatBuffer);
                                rateTransposer.process(audioEvent);

                                // Applied Effects Chain
                                for (AudioProcessor processor : currentEffectChain) {
                                    processor.process(audioEvent);
                                }

                                // Convert back to short integer
                                floatBuffer = audioEvent.getFloatBuffer();
                                shortBuffer = new short[floatBuffer.length];
                                for (int i = 0; i < floatBuffer.length; i++) {
                                    shortBuffer[i] = (short) (floatBuffer[i] * Short.MAX_VALUE);
                                }

                                // Write to audio track
                                audioTrack.write(shortBuffer, 0, shortBuffer.length);
                            }
                            codec.releaseOutputBuffer(outIndex, false);
                            break;
                    }
                }

            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            } finally {
                if (audioTrack != null) {
                    try {
                        audioTrack.stop();
                    } catch (IllegalStateException e) {
                        // Ignore the exception if the audio track is already stopped
                    }
                    audioTrack.release();
                }
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
                if (extractor != null) {
                    extractor.release();
                }
            }
        });
        playbackThread.start();
    }


    private void seekTo(int positionMs) {
        if (playbackThread != null && playbackThread.isAlive()) {
            playbackThread.interrupt();
            try {
                playbackThread.join(); // Ensure the thread has finished
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        startPlayback(currentFilePath, positionMs);
        progressBar.setProgress(positionMs);
    }

    private void togglePlayPause() {
        if (isPlaying) {
            pauseMusic();
        } else {
            if (currentFilePath == null) {
                Toast.makeText(this, "No music file is currently playing", Toast.LENGTH_SHORT).show();
                return;
            }
            resumeMusic();
        }
    }

    private void pauseMusic() {
        isPaused = true;
        isPlaying = false;
        playButton.setText("Play");
        handler.removeCallbacks(progressRunnable);
    }

    private void resumeMusic() {
        isPaused = false;
        isPlaying = true;
        playButton.setText("Pause");
        handler.post(progressRunnable);
        synchronized (playbackThread) {
            playbackThread.notify();
        }
    }

    private void stopMusic() {
        if (playbackThread != null && playbackThread.isAlive()) {
            playbackThread.interrupt();
            try {
                playbackThread.join(); // Ensure the thread has finished
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (IllegalStateException e) {
                // Ignore the exception if the audio track is already stopped
            }
            audioTrack.release();
            audioTrack = null;
        }
        isPlaying = false;
        currentFilePath = null;
        handler.removeCallbacks(progressRunnable);
        progressBar.setProgress(0);
        playButton.setText("Play");
    }

    private List<AudioProcessor> getEffectChain(EffectType type) {
        List<AudioProcessor> effectChain = new ArrayList<>();
        switch (type) {
            case TYPE_A:
                effectChain.add(new CustomWowFlutter(1.0f, 0.0219f)); 
                effectChain.add(new CustomWowFlutter(4.0f, 0.0219f)); 
                effectChain.add(new CustomNoise(0.15f));
                effectChain.add(new CustomDistortion(1.8f));
                effectChain.add(new CustomSuperBass(0.6f,150f));
                break;
            case TYPE_B://Emulates Sony TPS-L2 sound (for now)
                effectChain.add(new CustomWowFlutter(0.1f, 0.00219f)); 
                effectChain.add(new CustomWowFlutter(0.4f, 0.00219f)); 
                effectChain.add(new CustomNoise(0.45f));
                effectChain.add(new CustomDistortion(0.5f));
                effectChain.add(new CustomTapeHiss(0.003f));
                effectChain.add(new CustomTapeSqueal(0.01f, 0.136f)); 
                effectChain.add(new CustomSuperBass(0.5f,150f));
                break;
            case TYPE_C:
                effectChain.add(new CustomWowFlutter(4.0f, 0.219f));
                effectChain.add(new CustomWowFlutter(8.0f, 0.219f));
                effectChain.add(new CustomNoise(0.5f));
                effectChain.add(new CustomDistortion(0.4f));
                effectChain.add(new CustomTapeHiss(0.008f));
                effectChain.add(new CustomTapeSqueal(0.02f, 0.136f));
                effectChain.add(new CustomSuperBass(0.8f,150f));
                break;
        }
        // Always add gain and limiter to the effect chain
        effectChain.add(new CustomGain(1.48f)); // Adjust gain as needed
        effectChain.add(new CustomLimiter(0.9f)); // Adjust limiter threshold as needed
        return effectChain;
    }

    private enum EffectType {
        TYPE_A, TYPE_B, TYPE_C
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopMusic();
    }
}
