package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LyricsRepositoryTest {

    @Test
    public void extractsLyricsFromGeniusPreloadedStateWithoutLosingUnicodeOrBreaks() {
        String html = "<script>window.__PRELOADED_STATE__ = JSON.parse('"
                + "{\\\"songPage\\\":{\\\"lyricsData\\\":{\\\"body\\\":{\\\"children\\\":["
                + "{\\\"tag\\\":\\\"p\\\",\\\"children\\\":[\\\"[Verse 1]\\\","
                + "{\\\"tag\\\":\\\"br\\\",\\\"children\\\":[]},"
                + "\\\"He said \\\\\\\"hello\\\\\\\" \\u4f60\\u597d\\\","
                + "{\\\"tag\\\":\\\"br\\\",\\\"children\\\":[]},\\\"Last line\\\"]}"
                + "]}}}}');</script>";

        String lyrics = LyricsRepository.extractLyricsFromHtml(html);

        assertEquals("[Verse 1]\nHe said \"hello\" 你好\nLast line", lyrics);
    }

    @Test
    public void fallsBackToRenderedGeniusContainersAndDecodesEntities() {
        String html = "<main>"
                + "<div data-lyrics-container=\"true\">[Chorus]<br>One &amp; two"
                + "<div><a href=\"#\">Nested line</a></div></div>"
                + "<div data-lyrics-container=\"true\">Final&nbsp;line<br/>再见</div>"
                + "</main>";

        String lyrics = LyricsRepository.extractLyricsFromHtml(html);

        assertEquals("[Chorus]\nOne & twoNested line\n\nFinal line\n再见", lyrics);
    }

    @Test
    public void stripsLrcTimingAndMetadataForReadableJCardCopy() {
        String synced = "[ar:WalkTape]\n[00:01.20]First line\n"
                + "[00:03.00][00:05.00]Repeated line\n[00:08.4]最后一行";

        assertEquals("First line\nRepeated line\n最后一行",
                LyricsRepository.stripSyncedLyrics(synced));
    }

    @Test
    public void matchScoringPrefersExactSongAndRejectsUnrelatedArtist() {
        int exact = LyricsRepository.matchScore(
                "David Bowie", "Heroes (2017 Remaster)",
                "David Bowie", "Heroes", 371, 372);
        int wrong = LyricsRepository.matchScore(
                "David Bowie", "Heroes (2017 Remaster)",
                "Taylor Swift", "Anti-Hero", 371, 201);

        assertTrue(exact >= 100);
        assertTrue(wrong < 64);
        assertTrue(exact > wrong);
        assertFalse(LyricsRepository.stripSyncedLyrics("").length() > 0);
    }

    @Test
    public void classifiesOnlyConnectivityFailuresForAutomaticRetry() {
        assertTrue(LyricsRepository.isTransientNetworkFailure(
                new UnknownHostException("offline")));
        assertTrue(LyricsRepository.isTransientNetworkFailure(
                new IOException("wrapped", new SocketTimeoutException("timeout"))));
        assertFalse(LyricsRepository.isTransientNetworkFailure(
                new IOException("Genius search returned 401")));

        LyricsRepository.Result offline = LyricsRepository.Result.networkUnavailable();
        assertEquals(CatalogModels.LyricsState.ERROR, offline.state);
        assertTrue(offline.retryWhenOnline);
        assertTrue(offline.openNetworkSettings);
        assertTrue(offline.message.contains("网络"));
    }
}
