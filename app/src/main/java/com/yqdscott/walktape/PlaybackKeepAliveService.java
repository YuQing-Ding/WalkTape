package com.yqdscott.walktape;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Gives the custom decoder/DSP process proper media-playback foreground priority.
 *
 * <p>The audio engine still lives in {@link PlaybackController}; this service is deliberately
 * tiny and owns no decoder memory. Combined with the controller's partial wake lock, Android can
 * turn the display off without suspending the thread that must continuously fill AudioTrack.</p>
 */
public final class PlaybackKeepAliveService extends Service {

    private static final String CHANNEL_ID = "walktape-playback";
    private static final int NOTIFICATION_ID = 1979;
    private static final String ACTION_PLAYING =
            "com.yqdscott.walktape.action.PLAYBACK_PLAYING";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_ARTIST = "artist";

    static void showPlaying(Context context,
                            CatalogModels.Album album,
                            CatalogModels.Track track) {
        if (context == null || track == null) {
            return;
        }
        Intent intent = new Intent(context, PlaybackKeepAliveService.class)
                .setAction(ACTION_PLAYING)
                .putExtra(EXTRA_TITLE, track.title)
                .putExtra(EXTRA_ARTIST, track.artist.isEmpty()
                        ? (album == null ? "" : album.artist) : track.artist);
        ContextCompat.startForegroundService(context, intent);
    }

    static void stop(Context context) {
        if (context == null) {
            return;
        }
        context.stopService(new Intent(context, PlaybackKeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WalkTape playback",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the tape transport stable while the screen is off");
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = intent == null ? "" : intent.getStringExtra(EXTRA_TITLE);
        String artist = intent == null ? "" : intent.getStringExtra(EXTRA_ARTIST);
        startForeground(NOTIFICATION_ID, buildNotification(title, artist));
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String title, String artist) {
        Intent launch = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_walktape)
                .setContentTitle(title == null || title.trim().isEmpty()
                        ? "WalkTape is playing" : title)
                .setContentText(artist == null ? "" : artist)
                .setContentIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopForegroundCompat();
        super.onDestroy();
    }
}
