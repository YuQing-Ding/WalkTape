package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WalkTapeViewRenderTest {

    private WalkTapeView view;

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density = 3f;
        RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().scaledDensity = 3f;
        view = new WalkTapeView(RuntimeEnvironment.getApplication());
        view.setListener(new NoOpListener());
        view.setAlbums(Collections.singletonList(new CatalogModels.Album(
                501,
                "Test Pressing",
                "WalkTape Lab",
                "1979",
                "A test-only record used by the rendering regression suite.",
                0xff1b1b19,
                0xffeee7d2,
                0xffdc4b25,
                0,
                Arrays.asList(
                        new CatalogModels.Track(601, "Reference Tone", 155_000,
                                "content://test/audio/601", "Test lyric one"),
                        new CatalogModels.Track(602, "Transport Study", 218_000,
                                "content://test/audio/602", "Test lyric two"),
                        new CatalogModels.Track(603, "Noise Floor", 191_000,
                                "content://test/audio/603", "Test lyric three")))), true);
    }

    @Test
    public void renderPrimaryFlow() throws IOException {
        layout(1080, 2160);
        Bitmap library = settleAndRender(1080, 2160);
        save(library, "01-library.png");

        tap(210, 610); // first cassette case
        Bitmap openCase = settleAndRender(1080, 2160);
        save(openCase, "02-open-case.png");

        tap(250, 560); // J-card
        Bitmap liner = settleAndRender(1080, 2160);
        save(liner, "03-jcard.png");

        tap(1005, 92); // close the J-card
        settleAndRender(1080, 2160);
        tap(790, 560); // cassette
        Bitmap tracks = settleAndRender(1080, 2160);
        save(tracks, "04-track-picker.png");

        tap(300, 1100); // first track
        layout(2160, 1080);
        Bitmap player = settleAndRender(2160, 1080);
        save(player, "05-player.png");

        assertTrue(library.getWidth() == 1080 && player.getWidth() == 2160);
        assertTrue(new File(outputDirectory(), "05-player.png").length() > 40_000);
    }

    @Test
    public void renderScrolledLibraryWithoutPinnedHeaderOverlap() throws IOException {
        List<CatalogModels.Album> shelf = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            shelf.add(new CatalogModels.Album(
                    700 + index,
                    "Archive " + (index + 1),
                    "WalkTape Library",
                    "198" + (index % 10),
                    "Scroll regression album",
                    0xff1b1b19,
                    0xffeee7d2,
                    0xffdc4b25,
                    index % 5,
                    Collections.singletonList(new CatalogModels.Track(
                            900 + index, "Track " + (index + 1), 180_000,
                            "content://test/audio/" + index, ""))));
        }
        view.setAlbums(shelf, true);
        layout(1080, 2160);
        settleAndRender(1080, 2160);

        drag(540, 1850, 540, 350);
        Bitmap scrolled = settleAndRender(1080, 2160);
        save(scrolled, "06-library-scrolled.png");

        assertTrue(new File(outputDirectory(), "06-library-scrolled.png").length() > 40_000);
    }

    @Test
    public void scrubPreviewsManyMovesButCommitsOnlyOneDecoderSeek() {
        layout(1080, 2160);
        settleAndRender(1080, 2160);
        tap(210, 610);
        settleAndRender(1080, 2160);
        tap(790, 560);
        settleAndRender(1080, 2160);
        tap(300, 1100);
        layout(2160, 1080);
        settleAndRender(2160, 1080);

        CountingListener listener = new CountingListener();
        view.setListener(listener);
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, 1560, 460, 0));
        for (int move = 1; move <= 8; move++) {
            view.dispatchTouchEvent(MotionEvent.obtain(
                    now, now + move * 12L, MotionEvent.ACTION_MOVE,
                    1560 + move * 55f, 460, 0));
        }
        view.dispatchTouchEvent(MotionEvent.obtain(
                now, now + 120, MotionEvent.ACTION_UP, 2010, 460, 0));

        assertEquals("Only ACTION_UP may reset the decoder", 1, listener.seekCount);
    }

    @Test
    public void playerCassetteLabelUsesEmbeddedArtwork() throws IOException {
        Bitmap artwork = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        artwork.eraseColor(0xff12e85a);
        view.setAlbumArtwork(501, artwork);

        layout(1080, 2160);
        settleAndRender(1080, 2160);
        tap(210, 610);
        settleAndRender(1080, 2160);
        tap(790, 560);
        settleAndRender(1080, 2160);
        tap(300, 1100);
        layout(2160, 1080);
        Bitmap player = settleAndRender(2160, 1080);
        save(player, "07-player-artwork.png");

        int artworkPixels = 0;
        for (int y = 140; y < 900; y += 3) {
            for (int x = 180; x < 1_430; x += 3) {
                int color = player.getPixel(x, y);
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                if (green > 90 && green > red * 1.45f && green > blue * 1.35f) {
                    artworkPixels++;
                }
            }
        }
        assertTrue("Embedded artwork was not printed onto the cassette label",
                artworkPixels > 350);
    }

    @Test
    public void hotlineIsMomentaryAndEndsWhenFingerLifts() {
        layout(1080, 2160);
        settleAndRender(1080, 2160);
        tap(210, 610);
        settleAndRender(1080, 2160);
        tap(790, 560);
        settleAndRender(1080, 2160);
        tap(300, 1100);
        layout(2160, 1080);
        settleAndRender(2160, 1080);

        HotlineListener listener = new HotlineListener();
        view.setListener(listener);
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, 105, 165, 0));
        assertEquals(1, listener.started);
        assertEquals(0, listener.stopped);

        view.dispatchTouchEvent(MotionEvent.obtain(
                now, now + 180, MotionEvent.ACTION_UP, 105, 165, 0));
        assertEquals(1, listener.started);
        assertEquals(1, listener.stopped);
    }

    @Test
    public void miniPlayerControlsCurrentTrackAndReturnsWithoutReloading() throws IOException {
        MiniPlayerListener listener = new MiniPlayerListener();
        view.setListener(listener);
        layout(1080, 2160);
        settleAndRender(1080, 2160);
        tap(210, 610);
        settleAndRender(1080, 2160);
        tap(790, 560);
        settleAndRender(1080, 2160);
        tap(300, 1100);
        assertEquals(1, listener.trackLoads);

        layout(2160, 1080);
        settleAndRender(2160, 1080);
        assertTrue(view.handleBackPressed());
        layout(1080, 2160);
        Bitmap miniPlayer = settleAndRender(1080, 2160);
        save(miniPlayer, "08-now-playing.png");
        assertTrue(!view.isPlayerScene());
        assertEquals(601L, view.getNowPlayingTrack().id);

        tap(810, 2015); // play/pause owns its hit area instead of reopening the player
        assertEquals(1, listener.playPauses);
        tap(950, 2015); // next updates the actual playing album, not the browsed selection
        assertEquals(1, listener.skips);
        assertEquals(602L, view.getNowPlayingTrack().id);

        tap(500, 2015); // the rest of the pill returns to the existing player session
        assertTrue(view.isPlayerScene());
        assertEquals(1, listener.playerReturns);
        assertEquals("Returning must not reload or restart the decoder", 1, listener.trackLoads);
        assertTrue(new File(outputDirectory(), "08-now-playing.png").length() > 40_000);
    }

    @Test
    public void playerInfoCardFlipsToAlbumTracksAndSelectionFlipsItBack() throws IOException {
        MiniPlayerListener listener = new MiniPlayerListener();
        view.setListener(listener);
        layout(1080, 2160);
        settleAndRender(1080, 2160);
        tap(210, 610);
        settleAndRender(1080, 2160);
        tap(790, 560);
        settleAndRender(1080, 2160);
        tap(300, 1100);
        assertEquals(1, listener.trackLoads);

        layout(2160, 1080);
        settleAndRender(2160, 1080);
        tap(1970, 955); // TRACK LIST in the lower-right corner of the info placard
        Bitmap directory = settleAndRender(2160, 1080);
        save(directory, "09-player-track-list.png");
        assertTrue(view.infoFlipProgressForTest() > 0.99f);

        tap(1690, 520); // second visible row on the back of the placard
        settleAndRender(2160, 1080);
        assertEquals(2, listener.trackLoads);
        assertEquals(602L, view.getNowPlayingTrack().id);
        assertTrue("Track selection should automatically show the front again",
                view.infoFlipProgressForTest() < 0.01f);
        assertTrue(view.isPlayerScene());

        tap(1970, 955);
        settleAndRender(2160, 1080);
        assertTrue("Back should close the directory before exiting the player",
                view.handleBackPressed());
        settleAndRender(2160, 1080);
        assertTrue(view.isPlayerScene());
        assertTrue(view.infoFlipProgressForTest() < 0.01f);
    }

    @Test
    public void playerReusesStaticMachineFrameWhileReelsAdvance() {
        layout(1080, 2160);
        settleAndRender(1080, 2160);
        tap(210, 610);
        settleAndRender(1080, 2160);
        tap(790, 560);
        settleAndRender(1080, 2160);
        tap(300, 1100);
        layout(2160, 1080);
        settleAndRender(2160, 1080);

        int buildsAfterReveal = view.playerStaticBuildCountForTest();
        assertTrue("The settled player should have a static machine cache",
                buildsAfterReveal >= 1);
        Bitmap frame = Bitmap.createBitmap(2160, 1080, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        for (int index = 0; index < 120; index++) {
            view.draw(canvas);
        }

        assertEquals("Reel animation must not rebuild the full TPS-L2 chassis",
                buildsAfterReveal, view.playerStaticBuildCountForTest());
        assertTrue("Transport animation should leave headroom for realtime audio",
                WalkTapeView.playerFrameIntervalMsForTest() >= 40L);
    }

    @Test
    public void artworkResidencyIsBoundedForLargeLibraries() {
        List<CatalogModels.Album> largeLibrary = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            largeLibrary.add(new CatalogModels.Album(
                    2_000L + index, "Album " + index, "Artist", "1979", "",
                    0xff202020, 0xffeeeeee, 0xffe6532d, 0,
                    Collections.singletonList(new CatalogModels.Track(
                            3_000L + index, "Track", 180_000L,
                            "content://test/audio/" + index, ""))));
        }
        view.setAlbums(largeLibrary, true);
        for (CatalogModels.Album album : largeLibrary) {
            Bitmap artwork = Bitmap.createBitmap(16, 16, Bitmap.Config.RGB_565);
            artwork.eraseColor(0xff223344 + (int) (album.id & 0xff));
            view.setAlbumArtwork(album.id, artwork);
        }

        int attachedArtwork = 0;
        for (CatalogModels.Album album : largeLibrary) {
            if (album.artwork != null) {
                attachedArtwork++;
            }
        }
        assertTrue("Large shelves must not retain every decoded cover",
                view.residentArtworkCountForTest() <= 24);
        assertEquals(view.residentArtworkCountForTest(), attachedArtwork);
    }

    @Test
    public void jCardRequestsLyricsOnDemandAndKeepsThemAcrossLibraryRefresh() {
        CatalogModels.Track unresolved = new CatalogModels.Track(
                7_777L, "Unresolved Song", "Archive Artist", "Archive Album",
                180_000L, "content://test/audio/7777", "");
        CatalogModels.Album album = new CatalogModels.Album(
                7_700L, "Archive Album", "Archive Artist", "1979", "",
                0xff202020, 0xffeeeeee, 0xffe6532d, 0,
                Collections.singletonList(unresolved));
        view.setAlbums(Collections.singletonList(album), true);
        LyricsListener listener = new LyricsListener();
        view.setListener(listener);

        layout(1080, 2160);
        settleAndRender(1080, 2160);
        tap(210, 610);
        settleAndRender(1080, 2160);
        tap(250, 560);

        assertEquals(1, listener.requests);
        LyricsRepository.Result result = LyricsRepository.Result.ready(
                "Fetched line one\nFetched line two", "GENIUS",
                "https://genius.com/example-lyrics");
        view.setTrackLyrics(unresolved.id, result);

        CatalogModels.Track refreshed = new CatalogModels.Track(
                unresolved.id, unresolved.title, unresolved.artist, unresolved.albumTitle,
                unresolved.durationMs, unresolved.contentUri, "");
        CatalogModels.Album refreshedAlbum = new CatalogModels.Album(
                album.id, album.title, album.artist, album.year, album.description,
                album.ink, album.paper, album.accent, album.artworkStyle,
                Collections.singletonList(refreshed));
        view.setAlbums(Collections.singletonList(refreshedAlbum), true);

        assertEquals(CatalogModels.LyricsState.READY, refreshed.lyricsState);
        assertEquals("Fetched line one\nFetched line two", refreshed.lyrics);
        assertEquals("GENIUS", refreshed.lyricsSource);
    }

    private void layout(int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private Bitmap settleAndRender(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        for (int i = 0; i < 42; i++) {
            SystemClock.sleep(24);
            view.draw(canvas);
        }
        return bitmap;
    }

    private void tap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0));
    }

    private void drag(float fromX, float fromY, float toX, float toY) {
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, fromX, fromY, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(
                now, now + 24, MotionEvent.ACTION_MOVE, toX, toY, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(
                now, now + 40, MotionEvent.ACTION_UP, toX, toY, 0));
    }

    private void save(Bitmap bitmap, String name) throws IOException {
        File directory = outputDirectory();
        assertTrue(directory.exists() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }

    private File outputDirectory() {
        return new File(System.getProperty("user.dir"), "build/reports/walktape-renders");
    }

    private static final class NoOpListener implements WalkTapeView.Listener {
        @Override public void onImportRequested() { }
        @Override public void onTrackSelected(CatalogModels.Album album, CatalogModels.Track track) { }
        @Override public void onReturnToPlayer() { }
        @Override public void onPlayPauseRequested() { }
        @Override public void onStopRequested() { }
        @Override public void onSkipRequested(int direction) { }
        @Override public void onSeekRequested(float fraction) { }
        @Override public void onExitPlayer() { }
        @Override public void onHotlineChanged(boolean active) { }
        @Override public void onToneChanged(boolean highTape) { }
    }

    private static final class CountingListener implements WalkTapeView.Listener {
        int seekCount;

        @Override public void onImportRequested() { }
        @Override public void onTrackSelected(CatalogModels.Album album, CatalogModels.Track track) { }
        @Override public void onReturnToPlayer() { }
        @Override public void onPlayPauseRequested() { }
        @Override public void onStopRequested() { }
        @Override public void onSkipRequested(int direction) { }
        @Override public void onSeekRequested(float fraction) { seekCount++; }
        @Override public void onExitPlayer() { }
        @Override public void onHotlineChanged(boolean active) { }
        @Override public void onToneChanged(boolean highTape) { }
    }

    private static final class HotlineListener implements WalkTapeView.Listener {
        int started;
        int stopped;

        @Override public void onImportRequested() { }
        @Override public void onTrackSelected(CatalogModels.Album album, CatalogModels.Track track) { }
        @Override public void onReturnToPlayer() { }
        @Override public void onPlayPauseRequested() { }
        @Override public void onStopRequested() { }
        @Override public void onSkipRequested(int direction) { }
        @Override public void onSeekRequested(float fraction) { }
        @Override public void onExitPlayer() { }
        @Override public void onHotlineChanged(boolean active) {
            if (active) {
                started++;
            } else {
                stopped++;
            }
        }
        @Override public void onToneChanged(boolean highTape) { }
    }

    private static final class MiniPlayerListener implements WalkTapeView.Listener {
        int trackLoads;
        int playerReturns;
        int playPauses;
        int skips;

        @Override public void onImportRequested() { }
        @Override public void onTrackSelected(CatalogModels.Album album, CatalogModels.Track track) {
            trackLoads++;
        }
        @Override public void onReturnToPlayer() { playerReturns++; }
        @Override public void onPlayPauseRequested() { playPauses++; }
        @Override public void onStopRequested() { }
        @Override public void onSkipRequested(int direction) { skips++; }
        @Override public void onSeekRequested(float fraction) { }
        @Override public void onExitPlayer() { }
        @Override public void onHotlineChanged(boolean active) { }
        @Override public void onToneChanged(boolean highTape) { }
    }

    private static final class LyricsListener implements WalkTapeView.Listener {
        int requests;

        @Override public void onImportRequested() { }
        @Override public void onTrackSelected(CatalogModels.Album album, CatalogModels.Track track) { }
        @Override public void onReturnToPlayer() { }
        @Override public void onPlayPauseRequested() { }
        @Override public void onStopRequested() { }
        @Override public void onSkipRequested(int direction) { }
        @Override public void onSeekRequested(float fraction) { }
        @Override public void onExitPlayer() { }
        @Override public void onHotlineChanged(boolean active) { }
        @Override public void onToneChanged(boolean highTape) { }
        @Override public void onLyricsRequested(CatalogModels.Album album,
                                                CatalogModels.Track track,
                                                boolean forceRefresh) {
            requests++;
        }
    }
}
