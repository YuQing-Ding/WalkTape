package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.content.ContextCompat;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real-network verification. No lyric text is written to the test log. */
@RunWith(AndroidJUnit4.class)
public class LyricsDeviceTest {

    @Test
    public void reportsDeviceNetworkPermissionState() throws Exception {
        Assume.assumeTrue("true".equalsIgnoreCase(
                InstrumentationRegistry.getArguments().getString("probeNetworkPermission")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
        int requestedFlag = -1;
        if (info.requestedPermissions != null && info.requestedPermissionsFlags != null) {
            for (int index = 0; index < info.requestedPermissions.length; index++) {
                if (android.Manifest.permission.INTERNET.equals(info.requestedPermissions[index])) {
                    requestedFlag = info.requestedPermissionsFlags[index];
                    break;
                }
            }
        }
        Log.i("WalkTapeLyrics", "network-permission context="
                + ContextCompat.checkSelfPermission(context, android.Manifest.permission.INTERNET)
                + " packageManager=" + context.getPackageManager().checkPermission(
                android.Manifest.permission.INTERNET, context.getPackageName())
                + " requestedFlag=" + requestedFlag);
    }

    @Test
    public void blockedAppNetworkIsPresentedAsASettingsAction() throws Exception {
        Assume.assumeTrue("true".equalsIgnoreCase(
                InstrumentationRegistry.getArguments().getString("probeBlockedNetwork")));
        android.app.Instrumentation instrumentation =
                InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            CatalogModels.Track track = new CatalogModels.Track(
                    -9_991L, "Network permission probe", "WalkTape", "Probe",
                    180_000L, "content://walktape/test/network-probe", "");
            CatalogModels.Album album = new CatalogModels.Album(
                    -9_990L, "Probe", "WalkTape", "1979", "",
                    0xff202020, 0xffeeeeee, 0xffe6532d, 0,
                    Collections.singletonList(track));
            Field viewField = MainActivity.class.getDeclaredField("walkTapeView");
            viewField.setAccessible(true);
            WalkTapeView view = (WalkTapeView) viewField.get(activity);
            instrumentation.runOnMainSync(() -> {
                view.setAlbums(Collections.singletonList(album), true);
                activity.onLyricsRequested(album, track, true);
            });

            long deadline = SystemClock.elapsedRealtime() + 8_000L;
            while (track.lyricsState == CatalogModels.LyricsState.LOADING
                    && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(40L);
            }
            assertEquals(CatalogModels.LyricsState.ERROR, track.lyricsState);
            assertTrue("A validated phone network plus blocked app sockets should open settings",
                    track.lyricsOpenNetworkSettings);
            assertTrue(track.lyricsMessage.contains("网络权限"));
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }

    @Test
    public void resolvesAndCachesKnownLyricsWithoutBlockingTheUiThread() throws Exception {
        Assume.assumeTrue("true".equalsIgnoreCase(
                InstrumentationRegistry.getArguments().getString("runLiveLyrics")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LyricsRepository repository = new LyricsRepository(
                context, GeniusCredentials.CLIENT_ACCESS_TOKEN);
        CatalogModels.Track track = new CatalogModels.Track(
                -81L, "Imagine", "John Lennon", "Imagine",
                184_000L, "content://walktape/test/lyrics", "");
        CatalogModels.Album album = new CatalogModels.Album(
                -80L, "Imagine", "John Lennon", "1971", "",
                0xff202020, 0xffeeeeee, 0xffe6532d, 0,
                Collections.singletonList(track));
        try {
            AtomicReference<LyricsRepository.Result> first = new AtomicReference<>();
            CountDownLatch firstDone = new CountDownLatch(1);
            repository.request(album, track, true, result -> {
                first.set(result);
                firstDone.countDown();
            });
            assertTrue("Live lyrics request timed out", firstDone.await(25, TimeUnit.SECONDS));
            assertEquals(CatalogModels.LyricsState.READY, first.get().state);
            assertTrue("Lyrics response was unexpectedly short", first.get().lyrics.length() > 150);

            AtomicReference<LyricsRepository.Result> second = new AtomicReference<>();
            CountDownLatch secondDone = new CountDownLatch(1);
            long started = System.nanoTime();
            repository.request(album, track, false, result -> {
                second.set(result);
                secondDone.countDown();
            });
            assertTrue("Cached lyrics request timed out", secondDone.await(3, TimeUnit.SECONDS));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertEquals(CatalogModels.LyricsState.READY, second.get().state);
            assertTrue(second.get().fromCache);
            Log.i("WalkTapeLyrics", "provider=" + first.get().source
                    + " length=" + first.get().lyrics.length()
                    + " cacheMs=" + elapsedMs);
        } finally {
            repository.shutdown();
        }
    }
}
