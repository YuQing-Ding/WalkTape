package com.yqdscott.walktape;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * On-demand lyrics resolver.
 *
 * <p>Genius remains the preferred provider when a client access token is supplied. The public
 * API identifies the song; because it often omits the copyrighted lyric body, the matching
 * Genius page is parsed using the same preloaded-state approach as cvzi/Lyrics. LRCLIB is a
 * token-free fallback so a temporary Genius HTML or anti-bot change does not make the J-card
 * permanently blank.</p>
 */
final class LyricsRepository {

    private static final String LOG_TAG = "WalkTapeLyrics";

    interface Callback {
        void onResult(Result result);
    }

    static final class Result {
        final CatalogModels.LyricsState state;
        final String lyrics;
        final String source;
        final String sourceUrl;
        final String message;
        final boolean fromCache;

        private Result(CatalogModels.LyricsState state,
                       String lyrics,
                       String source,
                       String sourceUrl,
                       String message,
                       boolean fromCache) {
            this.state = state;
            this.lyrics = safe(lyrics);
            this.source = safe(source);
            this.sourceUrl = safe(sourceUrl);
            this.message = safe(message);
            this.fromCache = fromCache;
        }

        static Result ready(String lyrics, String source, String sourceUrl) {
            return new Result(CatalogModels.LyricsState.READY,
                    lyrics, source, sourceUrl, "", false);
        }

        static Result notFound() {
            return new Result(CatalogModels.LyricsState.NOT_FOUND,
                    "", "", "", "没有找到足够可信的歌词匹配", false);
        }

        static Result error(String message) {
            return new Result(CatalogModels.LyricsState.ERROR,
                    "", "", "", message, false);
        }

        Result cached() {
            return new Result(state, lyrics, source, sourceUrl, message, true);
        }
    }

    private static final String GENIUS_API = "https://api.genius.com";
    private static final String LRCLIB_API = "https://lrclib.net/api";
    private static final String USER_AGENT =
            "WalkTape/1.0 (Android; on-demand local music lyrics)";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_JSON_BYTES = 3 * 1024 * 1024;
    private static final int MAX_HTML_BYTES = 8 * 1024 * 1024;
    private static final long READY_CACHE_MS = 180L * 24L * 60L * 60L * 1_000L;
    private static final long MISS_CACHE_MS = 18L * 60L * 60L * 1_000L;
    private static final int MIN_GENIUS_SCORE = 64;
    private static final int MIN_LRCLIB_SCORE = 66;
    private static final Pattern EDITION_SUFFIX = Pattern.compile(
            "(?iu)\\s*[\\[(](?:[^\\])]*(?:remaster(?:ed)?|mono|stereo|live|edit|version|"
                    + "mix|deluxe|anniversary|bonus|radio|single|instrumental|acoustic)[^\\])]*)[\\])]\\s*$");
    private static final Pattern FEATURE_SUFFIX = Pattern.compile(
            "(?iu)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.+$");
    private static final Pattern LRC_TIME = Pattern.compile(
            "^(?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?])+\\s*");
    private static final Pattern LRC_META = Pattern.compile(
            "^\\[(?:ar|al|ti|au|by|offset|re|ve|length):.*]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY = Pattern.compile(
            "&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);", Pattern.CASE_INSENSITIVE);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor;
    private final LyricsCache cache;
    private final String geniusAccessToken;
    private final Map<String, List<Callback>> inFlight = new HashMap<>();
    private volatile boolean closed;

    LyricsRepository(Context context, String geniusAccessToken) {
        AtomicInteger threadNumber = new AtomicInteger();
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable,
                    "WalkTape-Lyrics-" + threadNumber.incrementAndGet());
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        cache = new LyricsCache(context.getApplicationContext());
        this.geniusAccessToken = safe(geniusAccessToken).trim();
    }

    void request(CatalogModels.Album album,
                 CatalogModels.Track track,
                 boolean forceRefresh,
                 Callback callback) {
        if (album == null || track == null || callback == null) {
            return;
        }
        Request request = Request.from(album, track);
        boolean launch;
        synchronized (inFlight) {
            List<Callback> callbacks = inFlight.get(request.cacheKey);
            if (callbacks == null) {
                callbacks = new ArrayList<>();
                inFlight.put(request.cacheKey, callbacks);
                launch = true;
            } else {
                launch = false;
            }
            callbacks.add(callback);
        }
        if (!launch) {
            return;
        }
        executor.execute(() -> {
            Result result;
            try {
                result = resolve(request, forceRefresh);
            } catch (RuntimeException exception) {
                Log.w(LOG_TAG, "Unexpected lyrics resolver failure: "
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage());
                result = Result.error("歌词服务暂时不可用");
            }
            List<Callback> callbacks;
            synchronized (inFlight) {
                callbacks = inFlight.remove(request.cacheKey);
            }
            if (callbacks == null || closed) {
                return;
            }
            Result delivered = result;
            mainHandler.post(() -> {
                if (closed) {
                    return;
                }
                for (Callback item : callbacks) {
                    item.onResult(delivered);
                }
            });
        });
    }

    void shutdown() {
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        synchronized (inFlight) {
            inFlight.clear();
        }
        cache.close();
    }

    private Result resolve(Request request, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh) {
            Result cached = cache.read(request.cacheKey, now);
            if (cached != null) {
                return cached.cached();
            }
        }

        boolean providerResponded = false;
        if (!geniusAccessToken.isEmpty()) {
            try {
                ProviderResult genius = fetchFromGenius(request);
                providerResponded = true;
                if (genius != null && !genius.lyrics.isEmpty()) {
                    Result result = Result.ready(genius.lyrics, "GENIUS", genius.sourceUrl);
                    cache.write(request.cacheKey, result, now);
                    return result;
                }
            } catch (IOException | JSONException exception) {
                Log.w(LOG_TAG, "Genius request failed: "
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage());
                // A blocked lyrics page must not prevent the independent public fallback.
            }
        }

        try {
            ProviderResult lrclib = fetchFromLrclib(request);
            providerResponded = true;
            if (lrclib != null && !lrclib.lyrics.isEmpty()) {
                Result result = Result.ready(lrclib.lyrics, "LRCLIB", lrclib.sourceUrl);
                cache.write(request.cacheKey, result, now);
                return result;
            }
        } catch (IOException | JSONException exception) {
            Log.w(LOG_TAG, "LRCLIB request failed: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            // Report a transient error below only when neither provider answered coherently.
        }

        if (providerResponded) {
            Result result = Result.notFound();
            cache.write(request.cacheKey, result, now);
            return result;
        }
        return Result.error("网络或歌词服务暂时没有响应");
    }

    private ProviderResult fetchFromGenius(Request request) throws IOException, JSONException {
        String query = request.artist + " " + request.searchTitle;
        URL searchUrl = new URL(GENIUS_API + "/search?q=" + encode(query));
        HttpResult response = get(searchUrl, geniusAccessToken,
                Collections.singleton("api.genius.com"), MAX_JSON_BYTES);
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Genius search returned " + response.statusCode);
        }
        JSONObject root = new JSONObject(response.body);
        JSONObject payload = root.optJSONObject("response");
        JSONArray hits = payload == null ? null : payload.optJSONArray("hits");
        if (hits == null || hits.length() == 0) {
            return null;
        }

        List<GeniusHit> candidates = new ArrayList<>();
        for (int index = 0; index < hits.length(); index++) {
            JSONObject hit = hits.optJSONObject(index);
            if (hit == null || !"song".equals(hit.optString("type"))) {
                continue;
            }
            JSONObject song = hit.optJSONObject("result");
            if (song == null) {
                continue;
            }
            JSONObject primaryArtist = song.optJSONObject("primary_artist");
            String artist = primaryArtist == null ? "" : primaryArtist.optString("name");
            String title = song.optString("title");
            int score = matchScore(request.artist, request.searchTitle,
                    artist, title, request.durationSeconds, -1);
            if (score >= MIN_GENIUS_SCORE) {
                candidates.add(new GeniusHit(song.optLong("id", -1), title, artist,
                        song.optString("url"), score));
            }
        }
        candidates.sort(Comparator.comparingInt((GeniusHit hit) -> hit.score).reversed());

        int attempts = Math.min(3, candidates.size());
        for (int index = 0; index < attempts; index++) {
            GeniusHit hit = candidates.get(index);
            String apiLyrics = fetchGeniusSongLyrics(hit.id);
            if (!apiLyrics.isEmpty()) {
                return new ProviderResult(cleanLyrics(apiLyrics), hit.url);
            }
            if (!isAllowedGeniusPage(hit.url)) {
                continue;
            }
            HttpResult page = get(new URL(hit.url), "",
                    geniusPageHosts(), MAX_HTML_BYTES);
            if (page.statusCode != HttpURLConnection.HTTP_OK) {
                continue;
            }
            String lyrics = extractLyricsFromHtml(page.body);
            if (!lyrics.isEmpty()) {
                return new ProviderResult(lyrics, hit.url);
            }
        }
        return null;
    }

    private String fetchGeniusSongLyrics(long songId) throws IOException, JSONException {
        if (songId < 0) {
            return "";
        }
        URL url = new URL(GENIUS_API + "/songs/" + songId + "?text_format=plain");
        HttpResult response = get(url, geniusAccessToken,
                Collections.singleton("api.genius.com"), MAX_JSON_BYTES);
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            return "";
        }
        JSONObject root = new JSONObject(response.body);
        JSONObject payload = root.optJSONObject("response");
        JSONObject song = payload == null ? null : payload.optJSONObject("song");
        if (song == null) {
            return "";
        }
        Object lyricValue = song.opt("lyrics");
        if (lyricValue instanceof JSONObject) {
            return ((JSONObject) lyricValue).optString("plain", "");
        }
        return lyricValue instanceof String ? (String) lyricValue : "";
    }

    private ProviderResult fetchFromLrclib(Request request) throws IOException, JSONException {
        String exact = LRCLIB_API + "/get?artist_name=" + encode(request.artist)
                + "&track_name=" + encode(request.searchTitle)
                + "&album_name=" + encode(request.albumTitle)
                + "&duration=" + request.durationSeconds;
        HttpResult response = get(new URL(exact), "",
                Collections.singleton("lrclib.net"), MAX_JSON_BYTES);
        if (response.statusCode == HttpURLConnection.HTTP_OK) {
            ProviderResult result = parseLrclibTrack(new JSONObject(response.body), request);
            if (result != null) {
                return result;
            }
        } else if (response.statusCode != HttpURLConnection.HTTP_NOT_FOUND) {
            throw new IOException("LRCLIB get returned " + response.statusCode);
        }

        String search = LRCLIB_API + "/search?artist_name=" + encode(request.artist)
                + "&track_name=" + encode(request.searchTitle)
                + "&album_name=" + encode(request.albumTitle);
        response = get(new URL(search), "",
                Collections.singleton("lrclib.net"), MAX_JSON_BYTES);
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            throw new IOException("LRCLIB search returned " + response.statusCode);
        }
        JSONArray matches = new JSONArray(response.body);
        ProviderResult best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < matches.length(); index++) {
            JSONObject match = matches.optJSONObject(index);
            if (match == null) {
                continue;
            }
            int score = matchScore(request.artist, request.searchTitle,
                    match.optString("artistName"), firstNonEmpty(
                            match.optString("trackName"), match.optString("name")),
                    request.durationSeconds, match.optInt("duration", -1));
            if (score < MIN_LRCLIB_SCORE || score <= bestScore) {
                continue;
            }
            ProviderResult candidate = parseLrclibTrack(match, request);
            if (candidate != null) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static ProviderResult parseLrclibTrack(JSONObject track, Request request) {
        String resultTitle = firstNonEmpty(track.optString("trackName"), track.optString("name"));
        String resultArtist = track.optString("artistName");
        int duration = track.optInt("duration", -1);
        if (matchScore(request.artist, request.searchTitle,
                resultArtist, resultTitle, request.durationSeconds, duration) < MIN_LRCLIB_SCORE) {
            return null;
        }
        String lyrics = cleanLyrics(track.optString("plainLyrics"));
        if (lyrics.isEmpty()) {
            lyrics = stripSyncedLyrics(track.optString("syncedLyrics"));
        }
        if (lyrics.isEmpty() && track.optBoolean("instrumental", false)) {
            lyrics = "这是一首纯音乐。\n\n磁带封页在这里留白。";
        }
        if (lyrics.isEmpty()) {
            return null;
        }
        long id = track.optLong("id", -1);
        String sourceUrl = id >= 0 ? "https://lrclib.net/api/get/" + id : "https://lrclib.net";
        return new ProviderResult(lyrics, sourceUrl);
    }

    static String extractLyricsFromHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String preloaded = extractPreloadedState(html);
        if (!preloaded.isEmpty()) {
            try {
                JSONObject root = new JSONObject(preloaded);
                Object body = findLyricsBody(root, 0);
                if (body != null) {
                    StringBuilder text = new StringBuilder();
                    appendLyricsNode(body, text);
                    String lyrics = cleanLyrics(text.toString());
                    if (!lyrics.isEmpty()) {
                        return lyrics;
                    }
                }
            } catch (JSONException ignored) {
                // Fall through to the rendered container parser.
            }
        }

        StringBuilder containers = new StringBuilder();
        String lower = html.toLowerCase(Locale.ROOT);
        int cursor = 0;
        while (true) {
            int attribute = lower.indexOf("data-lyrics-container", cursor);
            if (attribute < 0) {
                break;
            }
            int tagStart = lower.lastIndexOf("<div", attribute);
            int contentStart = lower.indexOf('>', attribute);
            if (tagStart < cursor || contentStart < 0) {
                cursor = attribute + 21;
                continue;
            }
            int tagEnd = findBalancedDivEnd(lower, contentStart + 1);
            if (tagEnd < 0) {
                break;
            }
            if (containers.length() > 0) {
                containers.append('\n');
            }
            containers.append(html, contentStart + 1, tagEnd);
            cursor = tagEnd + 6;
        }
        if (containers.length() == 0) {
            int oldLyrics = lower.indexOf("class=\"lyrics\"");
            if (oldLyrics < 0) {
                oldLyrics = lower.indexOf("class='lyrics'");
            }
            if (oldLyrics >= 0) {
                int contentStart = lower.indexOf('>', oldLyrics);
                int tagEnd = contentStart < 0 ? -1 : findBalancedDivEnd(lower, contentStart + 1);
                if (tagEnd > contentStart) {
                    containers.append(html, contentStart + 1, tagEnd);
                }
            }
        }
        return cleanLyrics(htmlFragmentToText(containers.toString()));
    }

    private static String extractPreloadedState(String html) {
        int marker = html.indexOf("__PRELOADED_STATE__");
        if (marker < 0) {
            return "";
        }
        int parse = html.indexOf("JSON.parse", marker);
        if (parse < 0) {
            return "";
        }
        int open = html.indexOf('(', parse + 10);
        if (open < 0) {
            return "";
        }
        int quoteIndex = open + 1;
        while (quoteIndex < html.length() && Character.isWhitespace(html.charAt(quoteIndex))) {
            quoteIndex++;
        }
        if (quoteIndex >= html.length()) {
            return "";
        }
        char quote = html.charAt(quoteIndex);
        if (quote != '\'' && quote != '"') {
            return "";
        }
        StringBuilder decoded = new StringBuilder();
        for (int index = quoteIndex + 1; index < html.length(); index++) {
            char value = html.charAt(index);
            if (value == quote) {
                return decoded.toString();
            }
            if (value != '\\') {
                decoded.append(value);
                continue;
            }
            if (++index >= html.length()) {
                return "";
            }
            char escaped = html.charAt(index);
            switch (escaped) {
                case '\\':
                case '\'':
                case '"':
                case '/':
                    decoded.append(escaped);
                    break;
                case 'b':
                    decoded.append('\b');
                    break;
                case 'f':
                    decoded.append('\f');
                    break;
                case 'n':
                    decoded.append('\n');
                    break;
                case 'r':
                    decoded.append('\r');
                    break;
                case 't':
                    decoded.append('\t');
                    break;
                case 'v':
                    decoded.append('\u000b');
                    break;
                case '\n':
                    break;
                case '\r':
                    if (index + 1 < html.length() && html.charAt(index + 1) == '\n') {
                        index++;
                    }
                    break;
                case 'x':
                    index = appendHexEscape(html, index, 2, decoded);
                    break;
                case 'u':
                    index = appendHexEscape(html, index, 4, decoded);
                    break;
                default:
                    decoded.append(escaped);
                    break;
            }
        }
        return "";
    }

    private static int appendHexEscape(String source,
                                       int escapeTypeIndex,
                                       int digits,
                                       StringBuilder destination) {
        int start = escapeTypeIndex + 1;
        int end = start + digits;
        if (end > source.length()) {
            destination.append(source.charAt(escapeTypeIndex));
            return escapeTypeIndex;
        }
        try {
            destination.append((char) Integer.parseInt(source.substring(start, end), 16));
            return end - 1;
        } catch (NumberFormatException ignored) {
            destination.append(source.charAt(escapeTypeIndex));
            return escapeTypeIndex;
        }
    }

    private static Object findLyricsBody(Object node, int depth) {
        if (node == null || node == JSONObject.NULL || depth > 14) {
            return null;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject lyricsData = object.optJSONObject("lyricsData");
            if (lyricsData != null && lyricsData.has("body")) {
                return lyricsData.opt("body");
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int index = 0; index < names.length(); index++) {
                    Object found = findLyricsBody(object.opt(names.optString(index)), depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                Object found = findLyricsBody(array.opt(index), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void appendLyricsNode(Object node, StringBuilder destination) {
        if (node == null || node == JSONObject.NULL) {
            return;
        }
        if (node instanceof String) {
            destination.append((String) node);
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                appendLyricsNode(array.opt(index), destination);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject object = (JSONObject) node;
        String tag = object.optString("tag").toLowerCase(Locale.ROOT);
        if ("br".equals(tag)) {
            destination.append('\n');
            return;
        }
        Object children = object.opt("children");
        if (children != null) {
            appendLyricsNode(children, destination);
        } else {
            String text = firstNonEmpty(object.optString("text"), object.optString("value"));
            destination.append(text);
        }
        if (("p".equals(tag) || "div".equals(tag) || "section".equals(tag))
                && destination.length() > 0
                && destination.charAt(destination.length() - 1) != '\n') {
            destination.append('\n');
        }
    }

    private static int findBalancedDivEnd(String lowerHtml, int contentStart) {
        int depth = 1;
        int cursor = contentStart;
        while (cursor < lowerHtml.length()) {
            int open = lowerHtml.indexOf("<div", cursor);
            int close = lowerHtml.indexOf("</div", cursor);
            if (close < 0) {
                return -1;
            }
            if (open >= 0 && open < close) {
                depth++;
                cursor = open + 4;
            } else {
                depth--;
                if (depth == 0) {
                    return close;
                }
                cursor = close + 5;
            }
        }
        return -1;
    }

    private static String htmlFragmentToText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(html.length());
        int cursor = 0;
        while (cursor < html.length()) {
            char value = html.charAt(cursor);
            if (value != '<') {
                text.append(value);
                cursor++;
                continue;
            }
            int end = html.indexOf('>', cursor + 1);
            if (end < 0) {
                break;
            }
            String tag = html.substring(cursor + 1, end).trim().toLowerCase(Locale.ROOT);
            if (tag.startsWith("br") || tag.startsWith("/p") || tag.startsWith("/div")
                    || tag.startsWith("/section") || tag.startsWith("/li")) {
                if (text.length() == 0 || text.charAt(text.length() - 1) != '\n') {
                    text.append('\n');
                }
            }
            cursor = end + 1;
        }
        return decodeHtmlEntities(text.toString());
    }

    private static String decodeHtmlEntities(String value) {
        Matcher matcher = ENTITY.matcher(value);
        StringBuffer decoded = new StringBuffer();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement;
            try {
                if (entity.startsWith("#x") || entity.startsWith("#X")) {
                    replacement = new String(Character.toChars(
                            Integer.parseInt(entity.substring(2), 16)));
                } else if (entity.startsWith("#")) {
                    replacement = new String(Character.toChars(
                            Integer.parseInt(entity.substring(1))));
                } else {
                    switch (entity.toLowerCase(Locale.ROOT)) {
                        case "amp":
                            replacement = "&";
                            break;
                        case "lt":
                            replacement = "<";
                            break;
                        case "gt":
                            replacement = ">";
                            break;
                        case "quot":
                            replacement = "\"";
                            break;
                        case "apos":
                            replacement = "'";
                            break;
                        case "nbsp":
                            replacement = " ";
                            break;
                        default:
                            replacement = matcher.group();
                            break;
                    }
                }
            } catch (IllegalArgumentException exception) {
                replacement = matcher.group();
            }
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(decoded);
        return decoded.toString();
    }

    static String stripSyncedLyrics(String syncedLyrics) {
        if (syncedLyrics == null || syncedLyrics.trim().isEmpty()) {
            return "";
        }
        StringBuilder plain = new StringBuilder();
        for (String rawLine : syncedLyrics.replace("\r", "").split("\n", -1)) {
            String line = rawLine.trim();
            if (LRC_META.matcher(line).matches()) {
                continue;
            }
            line = LRC_TIME.matcher(line).replaceFirst("").trim();
            if (plain.length() > 0) {
                plain.append('\n');
            }
            plain.append(line);
        }
        return cleanLyrics(plain.toString());
    }

    static int matchScore(String wantedArtist,
                          String wantedTitle,
                          String candidateArtist,
                          String candidateTitle,
                          int wantedDurationSeconds,
                          int candidateDurationSeconds) {
        String wantedArtistNormalized = normalize(wantedArtist);
        String candidateArtistNormalized = normalize(candidateArtist);
        String wantedTitleNormalized = normalize(cleanSearchTitle(wantedTitle));
        String candidateTitleNormalized = normalize(cleanSearchTitle(candidateTitle));
        if (wantedTitleNormalized.isEmpty() || candidateTitleNormalized.isEmpty()) {
            return 0;
        }

        int score = similarityScore(wantedTitleNormalized, candidateTitleNormalized, 68);
        if (wantedArtistNormalized.isEmpty() || "unknown artist".equals(wantedArtistNormalized)) {
            score += 8;
        } else {
            score += similarityScore(wantedArtistNormalized, candidateArtistNormalized, 27);
        }
        if (wantedDurationSeconds > 0 && candidateDurationSeconds > 0) {
            int difference = Math.abs(wantedDurationSeconds - candidateDurationSeconds);
            if (difference <= 2) {
                score += 8;
            } else if (difference <= 6) {
                score += 5;
            } else if (difference <= 12) {
                score += 2;
            } else if (difference > 35) {
                score -= 12;
            }
        }
        return score;
    }

    private static int similarityScore(String wanted, String candidate, int maximum) {
        if (wanted.equals(candidate)) {
            return maximum;
        }
        if (wanted.length() >= 4 && (wanted.contains(candidate) || candidate.contains(wanted))) {
            int shorter = Math.min(wanted.length(), candidate.length());
            int longer = Math.max(wanted.length(), candidate.length());
            return Math.round(maximum * (0.72f + 0.18f * shorter / longer));
        }
        int distance = levenshteinDistance(wanted, candidate);
        int longest = Math.max(wanted.length(), candidate.length());
        float similarity = longest == 0 ? 1f : 1f - distance / (float) longest;
        return Math.max(0, Math.round(maximum * similarity));
    }

    private static int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int substitution = previous[rightIndex - 1]
                        + (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1);
                current[rightIndex] = Math.min(Math.min(
                        current[rightIndex - 1] + 1,
                        previous[rightIndex] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String cleanSearchTitle(String title) {
        String cleaned = safe(title).replaceFirst("(?i)\\.(mp3|m4a|flac|wav|aac|ogg|opus)$", "").trim();
        String previous;
        do {
            previous = cleaned;
            cleaned = EDITION_SUFFIX.matcher(cleaned).replaceFirst("").trim();
        } while (!previous.equals(cleaned));
        return FEATURE_SUFFIX.matcher(cleaned).replaceFirst("").trim();
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(safe(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replace('’', '\'')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return decomposed.replaceAll("\\s+", " ");
    }

    private static String cleanLyrics(String lyrics) {
        if (lyrics == null) {
            return "";
        }
        String cleaned = lyrics.replace("\r", "")
                .replace('\u00a0', ' ')
                .replaceAll("[ \\t]+(?=\\n)", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return cleaned.length() > 80_000 ? cleaned.substring(0, 80_000).trim() : cleaned;
    }

    private static boolean isAllowedGeniusPage(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && geniusPageHosts().contains(safe(uri.getHost()).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Set<String> geniusPageHosts() {
        Set<String> hosts = new HashSet<>();
        hosts.add("genius.com");
        hosts.add("www.genius.com");
        return hosts;
    }

    private static HttpResult get(URL initialUrl,
                                  String bearerToken,
                                  Set<String> allowedHosts,
                                  int maximumBytes) throws IOException {
        URL url = initialUrl;
        for (int redirect = 0; redirect <= 3; redirect++) {
            String host = safe(url.getHost()).toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || !allowedHosts.contains(host)) {
                throw new IOException("Rejected lyrics host");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.7");
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
                connection.setRequestProperty("Accept-Encoding", "gzip");
                if (!safe(bearerToken).isEmpty() && "api.genius.com".equals(host)) {
                    connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        return new HttpResult(status, "");
                    }
                    url = new URL(url, location);
                    continue;
                }
                InputStream stream = status >= 400
                        ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) {
                    return new HttpResult(status, "");
                }
                if ("gzip".equalsIgnoreCase(connection.getHeaderField("Content-Encoding"))) {
                    stream = new GZIPInputStream(stream);
                }
                return new HttpResult(status, readLimited(stream, maximumBytes));
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static String readLimited(InputStream input, int maximumBytes) throws IOException {
        try (InputStream stream = new BufferedInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) {
                    throw new IOException("Lyrics response is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(safe(value), "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError("Every Android runtime supports UTF-8", impossible);
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return first == null || first.trim().isEmpty() ? safe(second) : first;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Request {
        final String cacheKey;
        final String artist;
        final String albumTitle;
        final String searchTitle;
        final int durationSeconds;

        private Request(String cacheKey,
                        String artist,
                        String albumTitle,
                        String searchTitle,
                        int durationSeconds) {
            this.cacheKey = cacheKey;
            this.artist = artist;
            this.albumTitle = albumTitle;
            this.searchTitle = searchTitle;
            this.durationSeconds = durationSeconds;
        }

        static Request from(CatalogModels.Album album, CatalogModels.Track track) {
            String artist = firstNonEmpty(track.artist, album.artist);
            String albumTitle = firstNonEmpty(track.albumTitle, album.title);
            String searchTitle = cleanSearchTitle(track.title);
            int duration = (int) Math.max(1, Math.round(track.durationMs / 1_000d));
            String key = "v2|" + track.id + '|' + normalize(artist) + '|'
                    + normalize(searchTitle) + '|' + duration;
            return new Request(key, artist, albumTitle, searchTitle, duration);
        }
    }

    private static final class GeniusHit {
        final long id;
        final String title;
        final String artist;
        final String url;
        final int score;

        GeniusHit(long id, String title, String artist, String url, int score) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.url = url;
            this.score = score;
        }
    }

    private static final class ProviderResult {
        final String lyrics;
        final String sourceUrl;

        ProviderResult(String lyrics, String sourceUrl) {
            this.lyrics = cleanLyrics(lyrics);
            this.sourceUrl = safe(sourceUrl);
        }
    }

    private static final class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = safe(body);
        }
    }

    private static final class LyricsCache extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "walktape-lyrics.db";
        private static final int DATABASE_VERSION = 1;
        private static final String TABLE = "lyrics";

        LyricsCache(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE " + TABLE + " ("
                    + "cache_key TEXT PRIMARY KEY NOT NULL,"
                    + "state INTEGER NOT NULL,"
                    + "lyrics TEXT NOT NULL,"
                    + "source TEXT NOT NULL,"
                    + "source_url TEXT NOT NULL,"
                    + "message TEXT NOT NULL,"
                    + "updated_ms INTEGER NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            database.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(database);
        }

        Result read(String key, long now) {
            try (Cursor cursor = getReadableDatabase().query(TABLE,
                    new String[]{"state", "lyrics", "source", "source_url", "message", "updated_ms"},
                    "cache_key=?", new String[]{key}, null, null, null, "1")) {
                if (!cursor.moveToFirst()) {
                    return null;
                }
                int stateIndex = cursor.getInt(0);
                CatalogModels.LyricsState[] states = CatalogModels.LyricsState.values();
                if (stateIndex < 0 || stateIndex >= states.length) {
                    return null;
                }
                CatalogModels.LyricsState state = states[stateIndex];
                long age = Math.max(0, now - cursor.getLong(5));
                long lifetime = state == CatalogModels.LyricsState.READY
                        ? READY_CACHE_MS : MISS_CACHE_MS;
                if ((state != CatalogModels.LyricsState.READY
                        && state != CatalogModels.LyricsState.NOT_FOUND) || age > lifetime) {
                    return null;
                }
                return new Result(state, cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), true);
            }
        }

        void write(String key, Result result, long now) {
            if (result.state != CatalogModels.LyricsState.READY
                    && result.state != CatalogModels.LyricsState.NOT_FOUND) {
                return;
            }
            ContentValues values = new ContentValues();
            values.put("cache_key", key);
            values.put("state", result.state.ordinal());
            values.put("lyrics", result.lyrics);
            values.put("source", result.source);
            values.put("source_url", result.sourceUrl);
            values.put("message", result.message);
            values.put("updated_ms", now);
            getWritableDatabase().insertWithOnConflict(TABLE, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }
}
