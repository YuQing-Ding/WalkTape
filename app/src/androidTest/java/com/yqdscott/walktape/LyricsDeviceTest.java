package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in real-network verification. No lyric text is written to the test log. */
@RunWith(AndroidJUnit4.class)
public class LyricsDeviceTest {

    @Test
    public void resolvesAndCachesKnownLyricsWithoutBlockingTheUiThread() throws Exception {
        Assume.assumeTrue("true".equalsIgnoreCase(
                InstrumentationRegistry.getArguments().getString("runLiveLyrics")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LyricsRepository repository = new LyricsRepository(context, "");
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
