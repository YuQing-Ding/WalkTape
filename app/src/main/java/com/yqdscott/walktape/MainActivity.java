package com.yqdscott.walktape;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.android.AndroidFFMPEGLocator;
import be.tarsos.dsp.io.android.AudioDispatcherFactory;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_READ_EXTERNAL_STORAGE = 1;

    private MusicAdapter musicAdapter;
    private List<String> musicFiles;
    private AudioDispatcher dispatcher;
    private List<AudioProcessor> currentEffectChain;
    private AudioTrack audioTrack;
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
    private Thread dispatcherThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        new AndroidFFMPEGLocator(this); // Ensure ffmpeg is located for TarsosDSP

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
        });

        RadioButton defaultEffect = findViewById(R.id.effect_a);
        defaultEffect.setChecked(true);
        currentEffectChain = getEffectChain(EffectType.TYPE_A);

        progressBar = findViewById(R.id.progress_bar);
        playButton = findViewById(R.id.play_button);
        stopButton = findViewById(R.id.stop_button);

        playButton.setOnClickListener(v -> togglePlayPause());
        stopButton.setOnClickListener(v -> stopMusic());

        handler = new Handler();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && audioTrack != null) {
                    currentPositionMs = (int) ((audioTrack.getPlaybackHeadPosition() / (float) audioTrack.getSampleRate()) * 1000);
                    progressBar.setProgress(currentPositionMs);
                    handler.postDelayed(this, 1000);
                }
            }
        };

        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isPlaying) {
                    seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    @SuppressLint("NotifyDataSetChanged")
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
        startAudioDispatcher(filePath, 0);
        isPlaying = true;
        isPaused = false;
        playButton.setText("Pause");
        handler.post(progressRunnable);
    }

    private int getMusicDuration(String filePath) {
        // Using ffmpeg to get the duration
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "/data/user/0/com.yqdscott.walktape/cache/ffmpeg",
                    "-i", filePath
            );
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Duration:")) {
                    String duration = line.split("Duration:")[1].split(",")[0].trim();
                    String[] parts = duration.split(":");
                    return (Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + (int) Float.parseFloat(parts[2])) * 1000;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private void startAudioDispatcher(String filePath, int startPositionMs) {
        dispatcherThread = new Thread(() -> {
            dispatcher = AudioDispatcherFactory.fromPipe(filePath, 22050, 99999, 128);
            for (AudioProcessor processor : currentEffectChain) {
                dispatcher.addAudioProcessor(processor);
            }
            dispatcher.addAudioProcessor(new CustomGain(2f));
            dispatcher.addAudioProcessor(new CustomLimiter(0.8f));
            dispatcher.addAudioProcessor(new AudioProcessor() {
                private int bufferSize;

                @Override
                public boolean process(AudioEvent audioEvent) {
                    if (audioTrack == null) {
                        bufferSize = AudioTrack.getMinBufferSize((int) audioEvent.getSampleRate(),
                                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 16;
                        audioTrack = new AudioTrack.Builder()
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build())
                                .setAudioFormat(new AudioFormat.Builder()
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setSampleRate((int) audioEvent.getSampleRate())
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                        .build())
                                .setBufferSizeInBytes(bufferSize)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build();
                        audioTrack.play();
                    }

                    try {
                        short[] shortBuffer = new short[audioEvent.getBufferSize()];
                        float[] floatBuffer = audioEvent.getFloatBuffer();
                        for (int i = 0; i < floatBuffer.length; i++) {
                            shortBuffer[i] = (short) (floatBuffer[i] * Short.MAX_VALUE);
                        }
                        audioTrack.write(shortBuffer, 0, shortBuffer.length);
                    } catch (Exception e) {
                        if (audioTrack != null) {
                            audioTrack.stop();
                            audioTrack.release();
                            audioTrack = null;
                        }
                        return false;
                    }
                    return true;
                }

                @Override
                public void processingFinished() {
                    if (audioTrack != null) {
                        audioTrack.stop();
                        audioTrack.release();
                        audioTrack = null;
                    }
                }
            });
            dispatcher.run();
        });
        dispatcherThread.start();
    }

    private void seekTo(int positionMs) {
        if (dispatcher != null) {
            dispatcher.stop();
        }
        currentPositionMs = positionMs;
        startAudioDispatcher(currentFilePath, positionMs);
        progressBar.setProgress(positionMs);
    }

    private void togglePlayPause() {
        if (isPlaying) {
            pauseMusic();
        } else {
            resumeMusic();
        }
    }

    private void pauseMusic() {
        if (dispatcher != null) {
            dispatcher.stop();
        }
        isPaused = true;
        isPlaying = false;
        playButton.setText("Play");
        handler.removeCallbacks(progressRunnable);
    }

    private void resumeMusic() {
        startAudioDispatcher(currentFilePath, currentPositionMs);
        isPaused = false;
        isPlaying = true;
        playButton.setText("Pause");
        handler.post(progressRunnable);
    }

    private void stopMusic() {
        if (dispatcher != null) {
            dispatcher.stop();
        }
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
        isPlaying = false;
        handler.removeCallbacks(progressRunnable);
        progressBar.setProgress(0);
        playButton.setText("Play");
        if (dispatcherThread != null && dispatcherThread.isAlive()) {
            dispatcherThread.interrupt();
        }
    }

    private enum EffectType {
        TYPE_A, TYPE_B, TYPE_C
    }

    private List<AudioProcessor> getEffectChain(EffectType type) {
        List<AudioProcessor> effectChain = new ArrayList<>();
        switch (type) {
            case TYPE_A:
                effectChain.add(new CustomWowFlutter(0.006f, 0.004f));
                effectChain.add(new CustomNoise(0.2f));
                effectChain.add(new CustomDistortion(1.5f));
                effectChain.add(new CustomTapeHiss(0.00015f));
                break;
            case TYPE_B:
                effectChain.add(new CustomNoise(0.2f));
                effectChain.add(new CustomDistortion(1.25f));
                effectChain.add(new CustomTapeHiss(0.01f));
                effectChain.add(new CustomWowFlutter(0.001f, 0.002f));
                break;
            case TYPE_C:
                effectChain.add(new CustomWowFlutter(0.004f, 0.001f));
                effectChain.add(new CustomNoise(0.002f));
                effectChain.add(new CustomDistortion(0.004f));
                effectChain.add(new CustomDropout(0.002f));
                break;
        }
        return effectChain;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopMusic();
    }
}
