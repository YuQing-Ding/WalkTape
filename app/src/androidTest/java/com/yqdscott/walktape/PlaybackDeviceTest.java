package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.AudioTrack;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** End-to-end device check: source decoder -> TPS-L2 DSP -> silent AudioTrack. */
@RunWith(AndroidJUnit4.class)
public class PlaybackDeviceTest {

    private static final String PLAYBACK_TAG = "WalkTapePlayback";

    @Test
    public void decodesRendersAndSeeksRealDeviceAudio() throws Exception {
        String requestedUri = InstrumentationRegistry.getArguments().getString("mediaUri");
        Assume.assumeNotNull(requestedUri);
        Uri uri = Uri.parse(requestedUri);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long durationMs = readDurationMs(context, uri);

        CountDownLatch prepared = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicLong preparedDurationMs = new AtomicLong();
        PlaybackController controller = new PlaybackController(context,
                new PlaybackController.Listener() {
                    @Override
                    public void onPrepared(long value) {
                        preparedDurationMs.set(value);
                        prepared.countDown();
                    }

                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void onError(String message) {
                        error.set(message);
                        prepared.countDown();
                    }
                });
        try {
            // Keep this real AudioTrack verification inaudible without changing the production API.
            Field ducking = PlaybackController.class.getDeclaredField("ducking");
            ducking.setAccessible(true);
            ducking.setFloat(controller, 0f);

            controller.loadAndPlay(new CatalogModels.Track(
                    1L, "Device decode check", durationMs, uri.toString(), ""));
            assertTrue("Playback did not prepare in time", prepared.await(12, TimeUnit.SECONDS));
            assertNull(error.get(), error.get());
            assertTrue("Playback reported no duration", preparedDurationMs.get() > 0L);

            waitForPosition(controller, 250L, error, 8_000L);
            assertNull(error.get(), error.get());
            assertTrue("TPS-L2 playback clock did not advance", controller.getPositionMs() >= 250L);
            int underrunsBeforeSeek = readUnderrunCount(controller);

            // A real scrub produces several UI positions in quick succession. Only the newest
            // request should survive, and playback must restart once rather than repeatedly flush.
            controller.seekToFraction(0.12f);
            controller.seekToFraction(0.78f);
            controller.seekToFraction(0.31f);
            controller.seekToFraction(0.64f);
            controller.seekToFraction(0.5f);
            long expectedSeekMs = preparedDurationMs.get() / 2L;
            waitForPosition(controller, expectedSeekMs + 250L, error, 8_000L);
            assertNull(error.get(), error.get());
            assertTrue("Playback did not seek into the requested half of the track",
                    controller.getPositionMs() >= expectedSeekMs - 250L);
            int underrunsAfterSeek = readUnderrunCount(controller);
            assertTrue("Seek recovery introduced repeated AudioTrack underruns: before="
                            + underrunsBeforeSeek + " after=" + underrunsAfterSeek,
                    underrunsAfterSeek <= underrunsBeforeSeek + 1);
        } finally {
            controller.release();
        }
    }

    @Test
    public void trackReplacementDoesNotRetainHistoryOrStutterLater() throws Exception {
        String requestedUri = InstrumentationRegistry.getArguments().getString("mediaUri");
        Assume.assumeNotNull(requestedUri);
        Uri uri = Uri.parse(requestedUri);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long durationMs = readDurationMs(context, uri);
        AtomicReference<String> error = new AtomicReference<>();
        PlaybackController controller = new PlaybackController(context,
                new PlaybackController.Listener() {
                    @Override public void onPrepared(long value) { }
                    @Override public void onCompleted() { }
                    @Override public void onError(String message) { error.set(message); }
                });
        try {
            Field ducking = PlaybackController.class.getDeclaredField("ducking");
            ducking.setAccessible(true);
            ducking.setFloat(controller, 0f);

            for (int replacement = 0; replacement < 8; replacement++) {
                controller.loadAndPlay(new CatalogModels.Track(
                        replacement + 1L, "Rapid replacement " + replacement,
                        durationMs, uri.toString(), ""));
            }

            waitForPosition(controller, 300L, error, 10_000L);
            assertNull(error.get(), error.get());
            assertTrue("Newest track session did not become the sole active player",
                    controller.isPlaying() && controller.getPositionMs() >= 300L);
            assertEquals("Current decoder retained the complete replacement history",
                    1, controller.retainedDecoderSessionCount());

            // Reproduce ordinary use too: each of these sessions is allowed to allocate its real
            // codec, AudioTrack, DSP and PCM workspaces before being replaced. The old final
            // predecessor link retained all of those workspaces indefinitely.
            long pssBeforeSettledChangesKb = android.os.Debug.getPss();
            for (int replacement = 0; replacement < 4; replacement++) {
                waitForPosition(controller, 1_100L, error, 5_000L);
                controller.loadAndPlay(new CatalogModels.Track(
                        100L + replacement, "Settled replacement " + replacement,
                        durationMs, uri.toString(), ""));
                waitForPosition(controller, 500L, error, 10_000L);
                assertNull(error.get(), error.get());
                assertEquals("Settled replacement retained predecessor PCM buffers",
                        1, controller.retainedDecoderSessionCount());
            }
            long pssAfterSettledChangesKb = android.os.Debug.getPss();

            int underrunsAfterStart = readUnderrunCount(controller);
            waitForPosition(controller, 6_500L, error, 10_000L);
            int underrunsAfterRun = readUnderrunCount(controller);
            Log.i(PLAYBACK_TAG, "replacement retained="
                    + controller.retainedDecoderSessionCount()
                    + " pssKb=" + pssBeforeSettledChangesKb + "->"
                    + pssAfterSettledChangesKb
                    + " underruns=" + underrunsAfterStart + "->" + underrunsAfterRun);
            assertTrue("Replacement session began to underrun several seconds later: start="
                            + underrunsAfterStart + " end=" + underrunsAfterRun,
                    underrunsAfterRun <= underrunsAfterStart + 1);
        } finally {
            controller.release();
        }
    }

    @Test
    public void sustainedHighResolutionPlaybackKeepsItsAudioReserve() throws Exception {
        String requestedUri = InstrumentationRegistry.getArguments().getString("mediaUri");
        Assume.assumeNotNull(requestedUri);
        Uri uri = Uri.parse(requestedUri);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long durationMs = readDurationMs(context, uri);
        CountDownLatch prepared = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        PlaybackController controller = new PlaybackController(context,
                new PlaybackController.Listener() {
                    @Override public void onPrepared(long value) { prepared.countDown(); }
                    @Override public void onCompleted() { }
                    @Override public void onError(String message) {
                        error.set(message);
                        prepared.countDown();
                    }
                });
        try {
            Field ducking = PlaybackController.class.getDeclaredField("ducking");
            ducking.setAccessible(true);
            ducking.setFloat(controller, 0f);
            controller.loadAndPlay(new CatalogModels.Track(
                    77L, "Sustained high-resolution check", durationMs, uri.toString(), ""));

            assertTrue("Playback did not prepare in time", prepared.await(12, TimeUnit.SECONDS));
            assertNull(error.get(), error.get());
            waitForPosition(controller, 1_000L, error, 10_000L);
            assertNull(error.get(), error.get());

            AudioTrack output = readAudioTrack(controller);
            assertTrue("Playback output was not configured", output != null);
            assertTrue("High-resolution queue is smaller than its anti-jitter reserve",
                    output.getBufferCapacityInFrames() >= output.getSampleRate() * 4 / 5);
            int underrunsAtOneSecond = output.getUnderrunCount();

            long requestedTargetMs = 11_000L;
            String sustainArgument = InstrumentationRegistry.getArguments().getString("sustainMs");
            if (sustainArgument != null) {
                try {
                    requestedTargetMs = Math.max(3_000L, Long.parseLong(sustainArgument));
                } catch (NumberFormatException ignored) {
                    // Keep the normal regression duration for malformed optional arguments.
                }
            }
            long targetMs = Math.min(durationMs - 1_000L, requestedTargetMs);
            Assume.assumeTrue(targetMs > 2_000L);
            waitForPosition(controller, targetMs, error, targetMs + 6_000L);
            assertNull(error.get(), error.get());
            assertTrue("Long high-resolution playback did not remain realtime",
                    controller.getPositionMs() >= targetMs);
            int underrunsAfterRun = output.getUnderrunCount();
            Log.i(PLAYBACK_TAG, "sustained source=" + uri
                    + " targetMs=" + targetMs
                    + " bufferFrames=" + output.getBufferCapacityInFrames()
                    + " underruns=" + underrunsAtOneSecond + "->"
                    + underrunsAfterRun);
            assertTrue("24-bit/high-rate playback exhausted its queue: start="
                            + underrunsAtOneSecond + " end=" + underrunsAfterRun,
                    underrunsAfterRun <= underrunsAtOneSecond + 1);
        } finally {
            controller.release();
        }
    }

    @Test
    public void leavingPlayerSceneKeepsTheCurrentTapeRunning() throws Exception {
        String requestedUri = InstrumentationRegistry.getArguments().getString("mediaUri");
        Assume.assumeNotNull(requestedUri);
        Uri uri = Uri.parse(requestedUri);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        long durationMs = readDurationMs(context, uri);
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
        try {
            Field controllerField = MainActivity.class.getDeclaredField("playbackController");
            controllerField.setAccessible(true);
            PlaybackController controller = (PlaybackController) controllerField.get(activity);
            Field ducking = PlaybackController.class.getDeclaredField("ducking");
            ducking.setAccessible(true);
            ducking.setFloat(controller, 0f);

            CatalogModels.Track track = new CatalogModels.Track(
                    88L, "Background transport check", durationMs, uri.toString(), "");
            CatalogModels.Album album = new CatalogModels.Album(
                    89L, "Device Test", "WalkTape", "1979", "",
                    0xff202020, 0xffeeeeee, 0xffe6532d, 0,
                    java.util.Collections.singletonList(track));
            instrumentation.runOnMainSync(() -> activity.onTrackSelected(album, track));
            AtomicReference<String> noError = new AtomicReference<>();
            waitForPosition(controller, 500L, noError, 10_000L);
            assertTrue("Track did not start before leaving the player", controller.isPlaying());

            instrumentation.runOnMainSync(activity::onExitPlayer);
            long positionAtExit = controller.getPositionMs();
            SystemClock.sleep(700L);
            assertTrue("Leaving the player scene released or paused the tape",
                    controller.isPlaying() && controller.getPositionMs() >= positionAtExit + 400L);
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }

    @Test
    public void visiblePlayerSceneDoesNotStarveHighResolutionAudio() throws Exception {
        String requestedUri = InstrumentationRegistry.getArguments().getString("mediaUri");
        Assume.assumeNotNull(requestedUri);
        Uri uri = Uri.parse(requestedUri);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        long durationMs = readDurationMs(context, uri);
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
        try {
            Field controllerField = MainActivity.class.getDeclaredField("playbackController");
            controllerField.setAccessible(true);
            PlaybackController controller = (PlaybackController) controllerField.get(activity);
            Field ducking = PlaybackController.class.getDeclaredField("ducking");
            ducking.setAccessible(true);
            ducking.setFloat(controller, 0f);

            Field viewField = MainActivity.class.getDeclaredField("walkTapeView");
            viewField.setAccessible(true);
            WalkTapeView view = (WalkTapeView) viewField.get(activity);
            CatalogModels.Track track = new CatalogModels.Track(
                    188L, "Visible high-resolution check", durationMs, uri.toString(), "");
            CatalogModels.Album album = new CatalogModels.Album(
                    189L, "Device Render Test", "WalkTape", "1979", "",
                    0xff202020, 0xffeeeeee, 0xffe6532d, 0,
                    java.util.Collections.singletonList(track));

            instrumentation.runOnMainSync(() -> {
                try {
                    view.setAlbums(java.util.Collections.singletonList(album), true);
                    Field selectedAlbum = WalkTapeView.class.getDeclaredField("selectedAlbum");
                    selectedAlbum.setAccessible(true);
                    selectedAlbum.set(view, album);
                    Field selectedAlbumIndex = WalkTapeView.class.getDeclaredField(
                            "selectedAlbumIndex");
                    selectedAlbumIndex.setAccessible(true);
                    selectedAlbumIndex.setInt(view, 0);
                    Method enterPlayer = WalkTapeView.class.getDeclaredMethod(
                            "enterPlayer", int.class);
                    enterPlayer.setAccessible(true);
                    enterPlayer.invoke(view, 0);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
            instrumentation.waitForIdleSync();
            AtomicReference<String> noError = new AtomicReference<>();
            waitForPosition(controller, 1_000L, noError, 12_000L);
            assertTrue("TPS-L2 player scene was not visible", view.isPlayerScene()
                    && view.isShown());
            AudioTrack output = readAudioTrack(controller);
            assertTrue("Visible player did not create an AudioTrack", output != null);
            int underrunsAtStart = output.getUnderrunCount();
            long pssAtStartKb = android.os.Debug.getPss();

            long visibleTargetMs = Math.min(durationMs - 1_000L, 20_000L);
            waitForPosition(controller, visibleTargetMs, noError, visibleTargetMs + 8_000L);
            assertTrue("High-resolution playback stalled while the TPS-L2 was visible",
                    controller.getPositionMs() >= visibleTargetMs);
            int underrunsAfterVisibleRun = output.getUnderrunCount();

            for (int cycle = 0; cycle < 3; cycle++) {
                instrumentation.runOnMainSync(view::handleBackPressed);
                instrumentation.waitForIdleSync();
                long positionAtExit = controller.getPositionMs();
                SystemClock.sleep(800L);
                assertTrue("Playback stopped after leaving the TPS-L2 scene",
                        controller.isPlaying()
                                && controller.getPositionMs() >= positionAtExit + 450L);

                instrumentation.runOnMainSync(() -> {
                    try {
                        Method returnToPlayer = WalkTapeView.class.getDeclaredMethod(
                                "returnToNowPlaying");
                        returnToPlayer.setAccessible(true);
                        returnToPlayer.invoke(view);
                    } catch (ReflectiveOperationException error) {
                        throw new AssertionError(error);
                    }
                });
                instrumentation.waitForIdleSync();
                long returnTarget = controller.getPositionMs() + 1_200L;
                waitForPosition(controller, returnTarget, noError, 6_000L);
                assertTrue("Playback stalled after returning to the TPS-L2 scene",
                        view.isPlayerScene() && controller.getPositionMs() >= returnTarget);
            }

            int underrunsAfterCycles = output.getUnderrunCount();
            long pssAfterCyclesKb = android.os.Debug.getPss();
            Log.i(PLAYBACK_TAG, "visible-player pssKb=" + pssAtStartKb + "->"
                    + pssAfterCyclesKb + " underruns=" + underrunsAtStart + "->"
                    + underrunsAfterVisibleRun + "->" + underrunsAfterCycles);
            assertTrue("Visible TPS-L2 rendering starved the audio queue: start="
                            + underrunsAtStart + " visible=" + underrunsAfterVisibleRun
                            + " cycles=" + underrunsAfterCycles,
                    underrunsAfterCycles <= underrunsAtStart + 1);
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }

    @Test
    public void screenOffPlaybackKeepsRealtimeAudioAndWakeLock() throws Exception {
        String requestedUri = InstrumentationRegistry.getArguments().getString("mediaUri");
        Assume.assumeNotNull(requestedUri);
        Assume.assumeTrue("true".equalsIgnoreCase(
                InstrumentationRegistry.getArguments().getString("runScreenOff")));
        Uri uri = Uri.parse(requestedUri);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        long durationMs = readDurationMs(context, uri);
        wakeDevice(instrumentation);

        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
        PlaybackController controller = null;
        try {
            Field controllerField = MainActivity.class.getDeclaredField("playbackController");
            controllerField.setAccessible(true);
            controller = (PlaybackController) controllerField.get(activity);
            Field ducking = PlaybackController.class.getDeclaredField("ducking");
            ducking.setAccessible(true);
            ducking.setFloat(controller, 0f);

            CatalogModels.Track track = new CatalogModels.Track(
                    288L, "Screen-off high-resolution check", durationMs, uri.toString(), "");
            CatalogModels.Album album = new CatalogModels.Album(
                    289L, "Device Sleep Test", "WalkTape", "1979", "",
                    0xff202020, 0xffeeeeee, 0xffe6532d, 0,
                    java.util.Collections.singletonList(track));
            instrumentation.runOnMainSync(() -> activity.onTrackSelected(album, track));

            AtomicReference<String> noError = new AtomicReference<>();
            waitForPosition(controller, 1_000L, noError, 12_000L);
            assertNull(noError.get(), noError.get());
            assertTrue("Playback did not hold its partial wake lock before sleep",
                    controller.isPlaybackWakeLockHeldForTest());
            AudioTrack output = readAudioTrack(controller);
            assertTrue("Screen-off playback did not create an AudioTrack", output != null);
            int underrunsBeforeSleep = output.getUnderrunCount();
            long positionBeforeSleep = controller.getPositionMs();
            long target = Math.min(durationMs - 1_000L, positionBeforeSleep + 12_000L);
            Assume.assumeTrue(target > positionBeforeSleep + 5_000L);

            executeShell(instrumentation, "input keyevent 223");
            waitForPosition(controller, target, noError, 20_000L);
            assertNull(noError.get(), noError.get());
            assertTrue("Audio clock stalled after the display powered off: before="
                            + positionBeforeSleep + " after=" + controller.getPositionMs(),
                    controller.isPlaying() && controller.getPositionMs() >= target);
            assertTrue("Partial wake lock was lost while screen-off playback was active",
                    controller.isPlaybackWakeLockHeldForTest());
            int underrunsAfterSleep = output.getUnderrunCount();
            Log.i(PLAYBACK_TAG, "screen-off positionMs=" + positionBeforeSleep + "->"
                    + controller.getPositionMs() + " underruns=" + underrunsBeforeSleep
                    + "->" + underrunsAfterSleep);
            assertTrue("Screen-off playback exhausted its realtime queue: start="
                            + underrunsBeforeSleep + " end=" + underrunsAfterSleep,
                    underrunsAfterSleep <= underrunsBeforeSleep + 1);
        } finally {
            wakeDevice(instrumentation);
            PlaybackController finalController = controller;
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            if (finalController != null) {
                long deadline = SystemClock.elapsedRealtime() + 3_000L;
                while (finalController.isPlaybackWakeLockHeldForTest()
                        && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(40L);
                }
                assertTrue("Activity teardown leaked the playback wake lock",
                        !finalController.isPlaybackWakeLockHeldForTest());
            }
        }
    }

    private static long readDurationMs(Context context, Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")
                        && format.containsKey(MediaFormat.KEY_DURATION)) {
                    Log.i(PLAYBACK_TAG, "source=" + uri + " format=" + format);
                    return Math.max(1L, format.getLong(MediaFormat.KEY_DURATION) / 1_000L);
                }
            }
            throw new AssertionError("No timed audio track in " + uri);
        } finally {
            extractor.release();
        }
    }

    private static void waitForPosition(PlaybackController controller,
                                        long targetMs,
                                        AtomicReference<String> error,
                                        long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (controller.getPositionMs() < targetMs && error.get() == null
                && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(40L);
        }
    }

    private static int readUnderrunCount(PlaybackController controller) throws Exception {
        AudioTrack output = readAudioTrack(controller);
        return output == null ? 0 : output.getUnderrunCount();
    }

    private static AudioTrack readAudioTrack(PlaybackController controller) throws Exception {
        Field sessionField = PlaybackController.class.getDeclaredField("session");
        sessionField.setAccessible(true);
        Object session = sessionField.get(controller);
        if (session == null) {
            return null;
        }
        Field outputField = session.getClass().getDeclaredField("audioTrack");
        outputField.setAccessible(true);
        return (AudioTrack) outputField.get(session);
    }

    private static void wakeDevice(Instrumentation instrumentation) throws Exception {
        executeShell(instrumentation, "input keyevent 224");
        executeShell(instrumentation, "wm dismiss-keyguard");
        SystemClock.sleep(300L);
    }

    private static void executeShell(Instrumentation instrumentation, String command)
            throws Exception {
        try (ParcelFileDescriptor ignored =
                     instrumentation.getUiAutomation().executeShellCommand(command)) {
            // Closing the descriptor is sufficient for commands that do not return useful output.
        }
    }
}
