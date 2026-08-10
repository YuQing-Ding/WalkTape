package com.yqdscott.walktape;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** UI-friendly catalogue models populated exclusively from the user's music library. */
public final class CatalogModels {

    private CatalogModels() {
    }

    public enum LyricsState {
        IDLE,
        LOADING,
        READY,
        NOT_FOUND,
        ERROR
    }

    public static final class Track {
        public final long id;
        public final String title;
        public final String artist;
        public final String albumTitle;
        public final long durationMs;
        public final String contentUri;
        public volatile String lyrics;
        public volatile LyricsState lyricsState;
        public volatile String lyricsSource;
        public volatile String lyricsSourceUrl;
        public volatile String lyricsMessage;
        public volatile boolean lyricsOpenNetworkSettings;

        public Track(long id, String title, long durationMs, String contentUri, String lyrics) {
            this(id, title, "", "", durationMs, contentUri, lyrics);
        }

        public Track(long id,
                     String title,
                     String artist,
                     String albumTitle,
                     long durationMs,
                     String contentUri,
                     String lyrics) {
            this.id = id;
            this.title = title;
            this.artist = artist == null ? "" : artist;
            this.albumTitle = albumTitle == null ? "" : albumTitle;
            this.durationMs = durationMs;
            this.contentUri = contentUri;
            this.lyrics = lyrics == null ? "" : lyrics;
            this.lyricsState = this.lyrics.trim().isEmpty()
                    ? LyricsState.IDLE : LyricsState.READY;
            this.lyricsSource = "";
            this.lyricsSourceUrl = "";
            this.lyricsMessage = "";
            this.lyricsOpenNetworkSettings = false;
        }

        public void updateLyrics(LyricsState state,
                                 String text,
                                 String source,
                                 String sourceUrl) {
            updateLyrics(state, text, source, sourceUrl, "");
        }

        public void updateLyrics(LyricsState state,
                                 String text,
                                 String source,
                                 String sourceUrl,
                                 String message) {
            updateLyrics(state, text, source, sourceUrl, message, false);
        }

        public void updateLyrics(LyricsState state,
                                 String text,
                                 String source,
                                 String sourceUrl,
                                 String message,
                                 boolean openNetworkSettings) {
            lyricsState = state == null ? LyricsState.ERROR : state;
            lyrics = text == null ? "" : text;
            lyricsSource = source == null ? "" : source;
            lyricsSourceUrl = sourceUrl == null ? "" : sourceUrl;
            lyricsMessage = message == null ? "" : message;
            lyricsOpenNetworkSettings = openNetworkSettings;
        }

        public void copyLyricsFrom(Track other) {
            if (other == null) {
                return;
            }
            updateLyrics(other.lyricsState, other.lyrics,
                    other.lyricsSource, other.lyricsSourceUrl, other.lyricsMessage,
                    other.lyricsOpenNetworkSettings);
        }
    }

    public static final class Album {
        public final long id;
        public final String title;
        public final String artist;
        public final String year;
        public final String description;
        public final int ink;
        public final int paper;
        public final int accent;
        public final int artworkStyle;
        public final List<Track> tracks;
        public Bitmap artwork;

        public Album(long id,
                     String title,
                     String artist,
                     String year,
                     String description,
                     int ink,
                     int paper,
                     int accent,
                     int artworkStyle,
                     List<Track> tracks) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.year = year;
            this.description = description;
            this.ink = ink;
            this.paper = paper;
            this.accent = accent;
            this.artworkStyle = artworkStyle;
            this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
        }
    }
}
