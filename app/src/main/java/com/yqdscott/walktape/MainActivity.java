package com.yqdscott.walktape;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity implements
        WalkTapeView.Listener,
        PlaybackController.Listener {

    private static final int REQUEST_MUSIC_LIBRARY = 41;
    private static final int REQUEST_HOTLINE_MICROPHONE = 42;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (playbackController != null && walkTapeView != null) {
                long duration = playbackController.getDurationMs();
                if (duration > 0) {
                    walkTapeView.setPlaybackPosition(playbackController.getPositionMs(), duration);
                    walkTapeView.setPlaying(playbackController.isPlaying());
                }
            }
            progressHandler.postDelayed(this, 180);
        }
    };

    private WalkTapeView walkTapeView;
    private MusicLibrary musicLibrary;
    private LyricsRepository lyricsRepository;
    private PlaybackController playbackController;
    private boolean immersivePlayer;
    private boolean libraryStarted;
    private boolean permissionRequestIssued;
    private boolean manualRefreshRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow(false);

        musicLibrary = new MusicLibrary(this);
        lyricsRepository = new LyricsRepository(this, GeniusCredentials.CLIENT_ACCESS_TOKEN);
        playbackController = new PlaybackController(this, this);
        walkTapeView = new WalkTapeView(this);
        walkTapeView.setListener(this);
        setContentView(walkTapeView);

        walkTapeView.showLibraryLoading();
        walkTapeView.post(this::ensureAutomaticLibraryStart);
        progressHandler.post(progressTicker);
    }

    @Override
    public void onImportRequested() {
        if (hasMusicPermission()) {
            startMusicLibraryIfNeeded();
            manualRefreshRequested = true;
            walkTapeView.setLibrarySyncing(true);
            musicLibrary.refresh();
            return;
        }
        permissionRequestIssued = true;
        ActivityCompat.requestPermissions(this,
                new String[]{musicPermission()}, REQUEST_MUSIC_LIBRARY);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_HOTLINE_MICROPHONE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            walkTapeView.setHotlineActive(false);
            if (!granted) {
                Toast.makeText(this, "HOT LINE 需要麦克风权限才能把现场声音送进耳机",
                        Toast.LENGTH_LONG).show();
            } else {
                // The runtime-permission sheet ends the original press. Never leave the mic live
                // after that gesture; the listener explicitly presses again to begin monitoring.
                Toast.makeText(this, "麦克风权限已授予；请重新按住 HOT LINE 说话",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != REQUEST_MUSIC_LIBRARY) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            walkTapeView.showLibraryLoading();
            startMusicLibraryIfNeeded();
        } else {
            walkTapeView.showMusicPermissionRequired();
            Toast.makeText(this, "授予音乐访问权限后，WalkTape 才能自动建立磁带架",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onTrackSelected(CatalogModels.Album album, CatalogModels.Track track) {
        if (track == null || track.contentUri == null) {
            return;
        }
        immersivePlayer = true;
        configureWindow(true);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        PlaybackKeepAliveService.showPlaying(this, album, track);
        playbackController.loadAndPlay(track);
    }

    @Override
    public void onReturnToPlayer() {
        // Re-entering from the mini player is presentation-only. Reusing onTrackSelected() here
        // would unnecessarily tear down the healthy decoder and restart the current song.
        immersivePlayer = true;
        configureWindow(true);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    public void onPlayPauseRequested() {
        CatalogModels.Track track = walkTapeView.getNowPlayingTrack();
        if (track == null || track.contentUri == null) {
            return;
        }
        boolean playing = playbackController.toggle();
        walkTapeView.setPlaying(playing);
        if (playing) {
            PlaybackKeepAliveService.showPlaying(
                    this, walkTapeView.getNowPlayingAlbum(), track);
        } else {
            PlaybackKeepAliveService.stop(this);
        }
    }

    @Override
    public void onStopRequested() {
        CatalogModels.Track track = walkTapeView.getNowPlayingTrack();
        if (track == null || track.contentUri == null) {
            return;
        }
        if (playbackController.isPlaying()) {
            playbackController.toggle();
        }
        playbackController.seekToFraction(0f);
        PlaybackKeepAliveService.stop(this);
        walkTapeView.setPlaying(false);
        walkTapeView.setPlaybackPosition(0, Math.max(1, playbackController.getDurationMs()));
    }

    @Override
    public void onSkipRequested(int direction) {
        CatalogModels.Track track = walkTapeView.getNowPlayingTrack();
        if (track == null) {
            return;
        }
        PlaybackKeepAliveService.showPlaying(
                this, walkTapeView.getNowPlayingAlbum(), track);
        playbackController.loadAndPlay(track);
    }

    @Override
    public void onSeekRequested(float fraction) {
        CatalogModels.Track track = walkTapeView.getNowPlayingTrack();
        if (track != null && track.contentUri != null) {
            playbackController.seekToFraction(fraction);
        }
    }

    @Override
    public void onExitPlayer() {
        // Leaving the machine view only changes the presentation. The tape keeps running until
        // the listener pauses it, presses STOP, or deliberately selects another track.
        immersivePlayer = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        configureWindow(false);
    }

    @Override
    public void onHotlineChanged(boolean active) {
        if (!active) {
            playbackController.setHotlineEnabled(false);
            walkTapeView.setHotlineActive(false);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            walkTapeView.setHotlineActive(false);
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_HOTLINE_MICROPHONE);
            return;
        }
        startHotlineMonitoring();
    }

    @Override
    public void onToneChanged(boolean highTape) {
        playbackController.setHighTape(highTape);
    }

    @Override
    public void onAlbumArtworkRequested(long albumId) {
        if (musicLibrary != null) {
            musicLibrary.requestArtwork(albumId);
        }
    }

    @Override
    public void onLyricsRequested(CatalogModels.Album album,
                                  CatalogModels.Track track,
                                  boolean forceRefresh) {
        if (lyricsRepository == null || album == null || track == null) {
            return;
        }
        beginLyricsRequest(album, track, forceRefresh);
    }

    private void beginLyricsRequest(CatalogModels.Album album,
                                    CatalogModels.Track track,
                                    boolean forceRefresh) {
        walkTapeView.setTrackLyricsLoading(track.id);
        lyricsRepository.request(album, track, forceRefresh,
                result -> walkTapeView.setTrackLyrics(track.id, result));
    }

    @Override
    public void onLyricsSourceRequested(CatalogModels.Track track) {
        if (track == null || track.lyricsSourceUrl == null
                || !track.lyricsSourceUrl.startsWith("https://")) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(track.lyricsSourceUrl)));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "没有可打开歌词来源的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPrepared(long durationMs) {
        walkTapeView.setPlaying(true);
        walkTapeView.setPlaybackPosition(0, durationMs);
    }

    @Override
    public void onCompleted() {
        PlaybackKeepAliveService.stop(this);
        walkTapeView.setPlaying(false);
        long duration = Math.max(1, playbackController.getDurationMs());
        walkTapeView.setPlaybackPosition(duration, duration);
    }

    @Override
    public void onError(String message) {
        PlaybackKeepAliveService.stop(this);
        walkTapeView.setPlaying(false);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onHotlineStopped(String message) {
        walkTapeView.setHotlineActive(false);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void startHotlineMonitoring() {
        PlaybackController.HotlineResult result = playbackController.setHotlineEnabled(true);
        boolean started = result == PlaybackController.HotlineResult.STARTED;
        walkTapeView.setHotlineActive(started);
        if (result == PlaybackController.HotlineResult.NEED_HEADPHONES) {
            Toast.makeText(this, "请先连接有线、USB 或蓝牙耳机；扬声器监听会产生啸叫",
                    Toast.LENGTH_LONG).show();
        } else if (result == PlaybackController.HotlineResult.AUDIO_UNAVAILABLE) {
            Toast.makeText(this, "当前音频线路无法启动 HOT LINE",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void ensureAutomaticLibraryStart() {
        if (hasMusicPermission()) {
            startMusicLibraryIfNeeded();
            return;
        }
        walkTapeView.showMusicPermissionRequired();
        if (!permissionRequestIssued) {
            permissionRequestIssued = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{musicPermission()}, REQUEST_MUSIC_LIBRARY);
        }
    }

    private void startMusicLibraryIfNeeded() {
        if (libraryStarted || !hasMusicPermission()) {
            return;
        }
        libraryStarted = true;
        walkTapeView.showLibraryLoading();
        musicLibrary.start(new MusicLibrary.Callback() {
            @Override
            public void onLoaded(List<CatalogModels.Album> albums, MusicLibrary.Update update) {
                walkTapeView.setAlbums(albums, true);
                if (manualRefreshRequested) {
                    manualRefreshRequested = false;
                    String detail = update.added + " 首新增 · "
                            + update.removed + " 首移除 · "
                            + update.changed + " 首更新";
                    Toast.makeText(MainActivity.this,
                            "曲库同步完成：" + detail,
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onArtworkLoaded(long albumId, android.graphics.Bitmap artwork) {
                walkTapeView.setAlbumArtwork(albumId, artwork);
            }

            @Override
            public void onSyncStateChanged(boolean syncing) {
                walkTapeView.setLibrarySyncing(syncing);
                if (!syncing && manualRefreshRequested) {
                    manualRefreshRequested = false;
                    Toast.makeText(MainActivity.this, "曲库已经是最新状态",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                manualRefreshRequested = false;
                walkTapeView.showLibraryError();
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean hasMusicPermission() {
        return ContextCompat.checkSelfPermission(this, musicPermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    private String musicPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Manifest.permission.READ_MEDIA_AUDIO;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (musicLibrary != null && hasMusicPermission()) {
            startMusicLibraryIfNeeded();
        }
    }

    @Override
    protected void onPause() {
        if (playbackController != null && playbackController.isHotlineActive()) {
            playbackController.setHotlineEnabled(false);
            if (walkTapeView != null) {
                walkTapeView.setHotlineActive(false);
            }
        }
        super.onPause();
    }

    private void configureWindow(boolean immersive) {
        Window window = getWindow();
        window.setStatusBarColor(0xff0a0b0b);
        window.setNavigationBarColor(0xff090a0a);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = immersive
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(attributes);
        }
        int flags = immersive
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersivePlayer) {
            configureWindow(true);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        walkTapeView.requestLayout();
        walkTapeView.invalidate();
        if (immersivePlayer) {
            configureWindow(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (!walkTapeView.handleBackPressed()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        progressHandler.removeCallbacksAndMessages(null);
        PlaybackKeepAliveService.stop(this);
        if (playbackController != null) {
            playbackController.release();
        }
        if (musicLibrary != null) {
            musicLibrary.shutdown();
        }
        if (lyricsRepository != null) {
            lyricsRepository.shutdown();
        }
        super.onDestroy();
    }
}
