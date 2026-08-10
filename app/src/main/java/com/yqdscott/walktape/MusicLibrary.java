package com.yqdscott.walktape;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fast, observable MediaStore index.
 *
 * <p>Metadata is delivered before artwork, persisted in a small SQLite cache and reconciled in the
 * background. Item URI notifications are queried incrementally; collection-level notifications
 * fall back to a lightweight metadata diff so additions and deletions cannot be missed.</p>
 */
public final class MusicLibrary {

    public interface Callback {
        void onLoaded(List<CatalogModels.Album> albums, Update update);

        void onArtworkLoaded(long albumId, Bitmap artwork);

        void onSyncStateChanged(boolean syncing);

        void onError(String message);
    }

    public static final class Update {
        public final boolean fromCache;
        public final boolean live;
        public final int added;
        public final int removed;
        public final int changed;

        Update(boolean fromCache, boolean live, int added, int removed, int changed) {
            this.fromCache = fromCache;
            this.live = live;
            this.added = added;
            this.removed = removed;
            this.changed = changed;
        }
    }

    private static final String TAG = "WalkTapeLibrary";
    private static final long CHANGE_DEBOUNCE_MS = 180L;
    private static final long MEDIASTORE_SETTLE_RETRY_MS = 700L;
    private static final int FIRST_PAINT_ARTWORK_COUNT = 12;
    private static final int ARTWORK_MEMORY_ENTRY_LIMIT = 12;
    private static final Uri AUDIO_COLLECTION = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    private static final String AUDIO_ITEM_URI_PREFIX = AUDIO_COLLECTION.toString() + "/";
    private static final String MUSIC_SELECTION = MediaStore.Audio.Media.IS_MUSIC + " != 0";
    private static final String[] PROJECTION = new String[]{
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE
    };
    private static final Comparator<CatalogModels.Album> ALBUM_ORDER = (left, right) -> {
        int title = String.CASE_INSENSITIVE_ORDER.compare(left.title, right.title);
        return title != 0 ? title : Long.compare(left.id, right.id);
    };

    private static final int[][] PALETTES = new int[][]{
            {0xffede5d0, 0xff24201c, 0xffd24f34},
            {0xff18364c, 0xffe3e2d5, 0xff4c98a8},
            {0xffe3b758, 0xff17171c, 0xffdd4667},
            {0xff2c4b42, 0xffebe1c3, 0xffc55239},
            {0xffddd8cf, 0xff313b4e, 0xff6696bc},
            {0xff36271f, 0xffdfab58, 0xff87472f}
    };

    private final Context appContext;
    private final ContentResolver resolver;
    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "WalkTapeArtwork");
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TrackCache cache;
    private final LinkedHashMap<Long, TrackRecord> records = new LinkedHashMap<>();
    private final LinkedHashMap<Long, CatalogModels.Album> catalogById = new LinkedHashMap<>();
    private final LinkedHashMap<Long, ArtworkEntry> artworkMemory =
            new LinkedHashMap<>(ARTWORK_MEMORY_ENTRY_LIMIT + 1, 0.75f, true);
    private final Map<Long, ArtworkRequest> latestArtworkRequests = new ConcurrentHashMap<>();
    private final Set<String> requestedArtwork = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingIds = new LinkedHashSet<>();
    private final Set<Long> pendingSettleIds = new LinkedHashSet<>();
    private final ContentObserver observer;

    private final Runnable observedChangeDispatcher = new Runnable() {
        @Override
        public void run() {
            final boolean full;
            final Set<Long> ids;
            synchronized (pendingIds) {
                full = pendingFullRefresh;
                ids = new LinkedHashSet<>(pendingIds);
                pendingFullRefresh = false;
                pendingIds.clear();
            }
            notifySyncState(true);
            indexExecutor.execute(() -> {
                try {
                    if (full || ids.isEmpty()) {
                        reconcile(true);
                    } else {
                        applyIncremental(ids, true);
                    }
                } catch (RuntimeException error) {
                    postError(error);
                } finally {
                    notifySyncState(false);
                }
            });
        }
    };

    /*
     * MediaProvider can notify observers just before a filesystem-backed delete is committed.
     * Re-check only those item IDs once after a short settle window; this keeps deletes reliable
     * without turning normal changes into a full-library scan.
     */
    private final Runnable settleRetryDispatcher = new Runnable() {
        @Override
        public void run() {
            final Set<Long> ids;
            synchronized (pendingSettleIds) {
                ids = new LinkedHashSet<>(pendingSettleIds);
                pendingSettleIds.clear();
            }
            if (stopped || ids.isEmpty()) {
                return;
            }
            notifySyncState(true);
            indexExecutor.execute(() -> {
                try {
                    applyIncremental(ids, false);
                } catch (RuntimeException error) {
                    postError(error);
                } finally {
                    notifySyncState(false);
                }
            });
        }
    };

    private volatile Callback callback;
    private volatile boolean stopped;
    private boolean started;
    private boolean catalogPublished;
    private boolean pendingFullRefresh;

    public MusicLibrary(Context context) {
        appContext = context.getApplicationContext();
        resolver = appContext.getContentResolver();
        cache = new TrackCache(appContext);
        observer = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                scheduleObservedChange(null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                scheduleObservedChange(uri);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri, int flags) {
                scheduleObservedChange(uri);
            }

            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags) {
                if (uris == null || uris.isEmpty()) {
                    scheduleObservedChange(null);
                    return;
                }
                for (Uri uri : uris) {
                    scheduleObservedChange(uri);
                }
            }
        };
    }

    /** Starts cache hydration, a MediaStore reconciliation and live change observation. */
    public synchronized void start(Callback callback) {
        this.callback = callback;
        if (started) {
            refresh();
            return;
        }
        started = true;
        stopped = false;
        resolver.registerContentObserver(AUDIO_COLLECTION, true, observer);
        notifySyncState(true);
        indexExecutor.execute(() -> {
            try {
                long cacheStartedAt = System.nanoTime();
                LinkedHashMap<Long, TrackRecord> cached = cache.readAll();
                long cacheReadMs = elapsedMs(cacheStartedAt);
                long publishStartedAt = System.nanoTime();
                if (!cached.isEmpty()) {
                    records.clear();
                    records.putAll(cached);
                    publishCatalog(new Update(true, false, 0, 0, 0));
                }
                Log.i(TAG, "cache tracks=" + cached.size()
                        + " read=" + cacheReadMs + "ms"
                        + " publish=" + elapsedMs(publishStartedAt) + "ms"
                        + " total=" + elapsedMs(cacheStartedAt) + "ms");
                reconcile(false);
            } catch (RuntimeException error) {
                postError(error);
            } finally {
                notifySyncState(false);
            }
        });
    }

    /** Requests a non-blocking metadata reconciliation. */
    public void refresh() {
        if (!started || stopped) {
            return;
        }
        notifySyncState(true);
        indexExecutor.execute(() -> {
            try {
                reconcile(true);
            } catch (RuntimeException error) {
                postError(error);
            } finally {
                notifySyncState(false);
            }
        });
    }

    /** Loads one shelf/player cover on demand without blocking the UI thread. */
    public void requestArtwork(long albumId) {
        if (stopped) {
            return;
        }
        ArtworkRequest request = latestArtworkRequests.get(albumId);
        if (request == null) {
            return;
        }
        ArtworkEntry memory = artworkFromMemory(albumId);
        if (memory != null && memory.version == request.version) {
            mainHandler.post(() -> {
                Callback current = callback;
                if (!stopped && current != null) {
                    current.onArtworkLoaded(albumId, memory.bitmap);
                }
            });
            return;
        }
        enqueueArtwork(Collections.singletonList(request));
    }

    public synchronized void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        started = false;
        mainHandler.removeCallbacks(observedChangeDispatcher);
        mainHandler.removeCallbacks(settleRetryDispatcher);
        try {
            resolver.unregisterContentObserver(observer);
        } catch (RuntimeException ignored) {
            // It is safe to continue when start failed before observer registration completed.
        }
        callback = null;
        latestArtworkRequests.clear();
        requestedArtwork.clear();
        synchronized (artworkMemory) {
            artworkMemory.clear();
        }
        indexExecutor.execute(cache::close);
        indexExecutor.shutdown();
        artworkExecutor.shutdownNow();
    }

    private void reconcile(boolean live) {
        long startedAt = System.nanoTime();
        LinkedHashMap<Long, TrackRecord> fresh = queryAllRecords();
        Diff diff = Diff.between(records, fresh);
        boolean publish = !catalogPublished || diff.hasChanges();
        Set<Long> affectedGroups = affectedGroups(records, diff);
        records.clear();
        records.putAll(fresh);
        if (publish) {
            Update update = new Update(false, live, diff.added, diff.removed, diff.changed);
            if (catalogPublished && diff.hasChanges()) {
                publishIncrementalCatalog(update, affectedGroups);
            } else {
                publishCatalog(update);
            }
        }
        if (diff.hasChanges()) {
            cache.apply(diff);
        }
        Log.i(TAG, "reconcile tracks=" + records.size()
                + " added=" + diff.added
                + " removed=" + diff.removed
                + " changed=" + diff.changed
                + " in=" + elapsedMs(startedAt) + "ms");
    }

    private void applyIncremental(Set<Long> ids, boolean retryIfNoOp) {
        long startedAt = System.nanoTime();
        LinkedHashMap<Long, TrackRecord> fresh = new LinkedHashMap<>(records);
        for (long id : ids) {
            TrackRecord record = queryRecord(id);
            if (record == null) {
                fresh.remove(id);
            } else {
                fresh.put(id, record);
            }
        }
        Diff diff = Diff.between(records, fresh);
        if (!diff.hasChanges()) {
            if (retryIfNoOp) {
                scheduleSettleRetry(ids);
            }
            Log.i(TAG, "incremental ids=" + ids.size() + " no-op"
                    + (retryIfNoOp ? "; settle retry queued" : "")
                    + " in=" + elapsedMs(startedAt) + "ms");
            return;
        }
        cancelSettleRetry(ids);
        Set<Long> affectedGroups = affectedGroups(records, diff);
        records.clear();
        records.putAll(fresh);
        publishIncrementalCatalog(
                new Update(false, true, diff.added, diff.removed, diff.changed),
                affectedGroups);
        cache.apply(diff);
        Log.i(TAG, "incremental ids=" + ids.size()
                + " added=" + diff.added
                + " removed=" + diff.removed
                + " changed=" + diff.changed
                + " in=" + elapsedMs(startedAt) + "ms");
    }

    private static Set<Long> affectedGroups(Map<Long, TrackRecord> oldRecords, Diff diff) {
        Set<Long> result = new LinkedHashSet<>();
        for (TrackRecord upsert : diff.upserts) {
            TrackRecord old = oldRecords.get(upsert.id);
            if (old != null) {
                result.add(old.groupId());
            }
            result.add(upsert.groupId());
        }
        for (long id : diff.removals) {
            TrackRecord old = oldRecords.get(id);
            if (old != null) {
                result.add(old.groupId());
            }
        }
        return result;
    }

    private LinkedHashMap<Long, TrackRecord> queryAllRecords() {
        LinkedHashMap<Long, TrackRecord> result = new LinkedHashMap<>();
        try (Cursor cursor = resolver.query(AUDIO_COLLECTION, PROJECTION,
                MUSIC_SELECTION, null, null)) {
            if (cursor == null) {
                return result;
            }
            Columns columns = new Columns(cursor);
            while (cursor.moveToNext()) {
                TrackRecord record = columns.read(cursor);
                result.put(record.id, record);
            }
        }
        return result;
    }

    private TrackRecord queryRecord(long id) {
        String selection = MediaStore.Audio.Media._ID + " = ? AND " + MUSIC_SELECTION;
        try (Cursor cursor = resolver.query(AUDIO_COLLECTION, PROJECTION,
                selection, new String[]{String.valueOf(id)}, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            return new Columns(cursor).read(cursor);
        }
    }

    private void publishCatalog(Update update) {
        long buildStartedAt = System.nanoTime();
        CatalogBuild build = buildCatalog(null);
        long buildMs = elapsedMs(buildStartedAt);
        catalogById.clear();
        for (CatalogModels.Album album : build.albums) {
            catalogById.put(album.id, album);
        }
        catalogPublished = true;
        deliverCatalog(build, update);
        Log.i(TAG, "catalog full albums=" + build.albums.size()
                + " tracks=" + records.size()
                + " build=" + buildMs + "ms");
    }

    private void publishIncrementalCatalog(Update update, Set<Long> affectedGroups) {
        if (!catalogPublished || catalogById.isEmpty() || affectedGroups.isEmpty()) {
            publishCatalog(update);
            return;
        }
        long buildStartedAt = System.nanoTime();
        for (long groupId : affectedGroups) {
            latestArtworkRequests.remove(groupId);
        }
        CatalogBuild changed = buildCatalog(affectedGroups);
        for (long groupId : affectedGroups) {
            catalogById.remove(groupId);
        }
        for (CatalogModels.Album album : changed.albums) {
            catalogById.put(album.id, album);
        }
        List<CatalogModels.Album> albums = new ArrayList<>(catalogById.values());
        Collections.sort(albums, ALBUM_ORDER);
        catalogById.clear();
        for (CatalogModels.Album album : albums) {
            catalogById.put(album.id, album);
        }
        CatalogBuild delivery = new CatalogBuild(albums, changed.artworkRequests);
        deliverCatalog(delivery, update);
        Log.i(TAG, "catalog incremental affected=" + affectedGroups.size()
                + " albums=" + albums.size()
                + " build=" + elapsedMs(buildStartedAt) + "ms");
    }

    private void deliverCatalog(CatalogBuild build, Update update) {
        Callback destination = callback;
        if (!stopped && destination != null) {
            mainHandler.post(() -> {
                Callback current = callback;
                if (!stopped && current != null) {
                    current.onLoaded(build.albums, update);
                }
            });
        }
        scheduleArtwork(build.artworkRequests);
    }

    private CatalogBuild buildCatalog(Set<Long> includedGroups) {
        if (includedGroups == null) {
            latestArtworkRequests.clear();
        }
        List<TrackRecord> sorted = new ArrayList<>();
        for (TrackRecord record : records.values()) {
            if (includedGroups == null || includedGroups.contains(record.groupId())) {
                sorted.add(record);
            }
        }
        Collections.sort(sorted, TrackRecord.ORDER);
        Map<Long, MutableAlbum> grouped = new LinkedHashMap<>();
        for (TrackRecord record : sorted) {
            long groupId = record.groupId();
            MutableAlbum album = grouped.get(groupId);
            if (album == null) {
                album = new MutableAlbum(groupId, record.album, record.artist, record.year);
                grouped.put(groupId, album);
            }
            album.add(record);
        }

        List<CatalogModels.Album> albums = new ArrayList<>(grouped.size());
        List<ArtworkRequest> artworkRequests = new ArrayList<>();
        for (MutableAlbum source : grouped.values()) {
            int[] palette = PALETTES[Math.floorMod(source.title.hashCode(), PALETTES.length)];
            String year = source.year > 1900
                    ? String.valueOf(source.year)
                    : trackCountLabel(source.tracks.size());
            CatalogModels.Album album = new CatalogModels.Album(
                    source.id,
                    source.title,
                    source.artist,
                    year,
                    "来自你的本地音乐库。WalkTape 会在后台监听新增、删除与元数据变化，并保持这张 J-card 自动同步。",
                    palette[1],
                    palette[0],
                    palette[2],
                    Math.floorMod(Long.hashCode(source.id), 6),
                    source.tracks
            );
            ArtworkRequest artworkRequest = source.artworkProbe == null ? null
                    : new ArtworkRequest(source.id, source.artworkVersion, source.artworkProbe);
            if (artworkRequest != null) {
                latestArtworkRequests.put(source.id, artworkRequest);
            } else {
                latestArtworkRequests.remove(source.id);
            }
            ArtworkEntry memory = artworkFromMemory(source.id);
            if (memory != null && memory.version == source.artworkVersion) {
                album.artwork = memory.bitmap;
            } else if (artworkRequest != null) {
                artworkRequests.add(artworkRequest);
            }
            albums.add(album);
        }
        return new CatalogBuild(albums, artworkRequests);
    }

    private void scheduleArtwork(List<ArtworkRequest> requests) {
        int immediateCount = Math.min(FIRST_PAINT_ARTWORK_COUNT, requests.size());
        enqueueArtwork(requests.subList(0, immediateCount));
    }

    private void enqueueArtwork(List<ArtworkRequest> requests) {
        for (ArtworkRequest request : requests) {
            String key = request.albumId + ":" + request.version;
            if (!requestedArtwork.add(key) || stopped) {
                continue;
            }
            artworkExecutor.execute(() -> {
                Bitmap artwork = loadArtwork(request);
                if (artwork == null || stopped) {
                    return;
                }
                rememberArtwork(request.albumId,
                        new ArtworkEntry(request.version, artwork));
                mainHandler.post(() -> {
                    try {
                        Callback current = callback;
                        if (!stopped && current != null) {
                            current.onArtworkLoaded(request.albumId, artwork);
                        }
                    } finally {
                        // Successful covers may be requested again after the UI's bounded LRU
                        // evicts them. Missing covers remain negative-cached by keeping the key.
                        requestedArtwork.remove(key);
                    }
                });
            });
        }
    }

    private ArtworkEntry artworkFromMemory(long albumId) {
        synchronized (artworkMemory) {
            return artworkMemory.get(albumId);
        }
    }

    private void rememberArtwork(long albumId, ArtworkEntry entry) {
        synchronized (artworkMemory) {
            artworkMemory.put(albumId, entry);
            while (artworkMemory.size() > ARTWORK_MEMORY_ENTRY_LIMIT) {
                java.util.Iterator<Long> iterator = artworkMemory.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    private Bitmap loadArtwork(ArtworkRequest request) {
        File directory = new File(appContext.getCacheDir(), "walktape-covers");
        File cached = new File(directory,
                Long.toHexString(request.albumId) + "-" + Long.toHexString(request.version) + ".jpg");
        if (cached.isFile()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(cached.getAbsolutePath(), options);
            if (bitmap != null) {
                return bitmap;
            }
        }

        Bitmap bitmap = null;
        Uri probe = Uri.parse(request.probe);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                bitmap = resolver.loadThumbnail(probe, new Size(720, 720), null);
            } catch (IOException | RuntimeException ignored) {
                // A few document providers do not expose audio thumbnails; use embedded metadata.
            }
        }
        if (bitmap == null) {
            bitmap = readEmbeddedArtwork(probe);
        }
        if (bitmap != null) {
            if (directory.exists() || directory.mkdirs()) {
                try (FileOutputStream output = new FileOutputStream(cached)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output);
                } catch (IOException ignored) {
                    // The memory copy is still usable; cache failure must never block the shelf.
                }
            }
        }
        return bitmap;
    }

    private Bitmap readEmbeddedArtwork(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(appContext, uri);
            byte[] picture = retriever.getEmbeddedPicture();
            if (picture == null || picture.length == 0) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(picture, 0, picture.length, bounds);
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > 900) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Vendor retrievers occasionally throw after a rejected data source.
            }
        }
    }

    private void scheduleObservedChange(Uri uri) {
        if (stopped || !started) {
            return;
        }
        Long id = mediaId(uri);
        synchronized (pendingIds) {
            if (id == null) {
                pendingFullRefresh = true;
            } else {
                pendingIds.add(id);
            }
        }
        mainHandler.removeCallbacks(observedChangeDispatcher);
        mainHandler.postDelayed(observedChangeDispatcher, CHANGE_DEBOUNCE_MS);
    }

    private void scheduleSettleRetry(Set<Long> ids) {
        synchronized (pendingSettleIds) {
            pendingSettleIds.addAll(ids);
        }
        mainHandler.removeCallbacks(settleRetryDispatcher);
        mainHandler.postDelayed(settleRetryDispatcher, MEDIASTORE_SETTLE_RETRY_MS);
    }

    private void cancelSettleRetry(Set<Long> ids) {
        boolean empty;
        synchronized (pendingSettleIds) {
            pendingSettleIds.removeAll(ids);
            empty = pendingSettleIds.isEmpty();
        }
        if (empty) {
            mainHandler.removeCallbacks(settleRetryDispatcher);
        }
    }

    private static Long mediaId(Uri uri) {
        if (uri == null) {
            return null;
        }
        String segment = uri.getLastPathSegment();
        if (segment == null) {
            return null;
        }
        try {
            return Long.parseLong(segment);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void notifySyncState(boolean syncing) {
        mainHandler.post(() -> {
            Callback current = callback;
            if (!stopped && current != null) {
                current.onSyncStateChanged(syncing);
            }
        });
    }

    private void postError(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "无法读取本地音乐库";
        }
        final String finalMessage = message;
        mainHandler.post(() -> {
            Callback current = callback;
            if (!stopped && current != null) {
                current.onError(finalMessage);
            }
        });
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().isEmpty() || "<unknown>".equalsIgnoreCase(value)) {
            return fallback;
        }
        return value.trim();
    }

    private static String trackCountLabel(int count) {
        return (count < 10 ? "0" : "") + count + " TRACKS";
    }

    static final class TrackRecord {
        static final Comparator<TrackRecord> ORDER = (left, right) -> {
            int album = String.CASE_INSENSITIVE_ORDER.compare(left.album, right.album);
            if (album != 0) {
                return album;
            }
            int albumId = Long.compare(left.groupId(), right.groupId());
            if (albumId != 0) {
                return albumId;
            }
            int track = Integer.compare(left.trackNumber, right.trackNumber);
            if (track != 0) {
                return track;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(left.title, right.title);
        };

        final long id;
        final String title;
        final String artist;
        final String album;
        final long albumId;
        final long durationMs;
        final int trackNumber;
        final int year;
        final long modifiedSeconds;
        final long sizeBytes;

        TrackRecord(long id,
                    String title,
                    String artist,
                    String album,
                    long albumId,
                    long durationMs,
                    int trackNumber,
                    int year,
                    long modifiedSeconds,
                    long sizeBytes) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.albumId = albumId;
            this.durationMs = durationMs;
            this.trackNumber = trackNumber;
            this.year = year;
            this.modifiedSeconds = modifiedSeconds;
            this.sizeBytes = sizeBytes;
        }

        long groupId() {
            if (albumId != 0) {
                return albumId;
            }
            return -1L - Integer.toUnsignedLong((album + "\u0000" + artist).hashCode());
        }

        boolean sameMetadata(TrackRecord other) {
            return other != null
                    && id == other.id
                    && albumId == other.albumId
                    && durationMs == other.durationMs
                    && trackNumber == other.trackNumber
                    && year == other.year
                    && modifiedSeconds == other.modifiedSeconds
                    && sizeBytes == other.sizeBytes
                    && title.equals(other.title)
                    && artist.equals(other.artist)
                    && album.equals(other.album);
        }
    }

    static final class Diff {
        final List<TrackRecord> upserts = new ArrayList<>();
        final List<Long> removals = new ArrayList<>();
        int added;
        int removed;
        int changed;

        static Diff between(Map<Long, TrackRecord> oldRecords,
                            Map<Long, TrackRecord> freshRecords) {
            Diff result = new Diff();
            for (TrackRecord fresh : freshRecords.values()) {
                TrackRecord old = oldRecords.get(fresh.id);
                if (old == null) {
                    result.added++;
                    result.upserts.add(fresh);
                } else if (!old.sameMetadata(fresh)) {
                    result.changed++;
                    result.upserts.add(fresh);
                }
            }
            for (long id : oldRecords.keySet()) {
                if (!freshRecords.containsKey(id)) {
                    result.removed++;
                    result.removals.add(id);
                }
            }
            return result;
        }

        boolean hasChanges() {
            return added != 0 || removed != 0 || changed != 0;
        }
    }

    private static final class Columns {
        final int id;
        final int title;
        final int artist;
        final int album;
        final int albumId;
        final int duration;
        final int track;
        final int year;
        final int modified;
        final int size;

        Columns(Cursor cursor) {
            id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK);
            year = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR);
            modified = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED);
            size = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE);
        }

        TrackRecord read(Cursor cursor) {
            return new TrackRecord(
                    cursor.getLong(id),
                    clean(cursor.getString(title), "Untitled"),
                    clean(cursor.getString(artist), "Unknown Artist"),
                    clean(cursor.getString(album), "Loose Tapes"),
                    cursor.getLong(albumId),
                    Math.max(1_000L, cursor.getLong(duration)),
                    cursor.getInt(track),
                    year >= 0 ? cursor.getInt(year) : 0,
                    modified >= 0 ? cursor.getLong(modified) : 0L,
                    size >= 0 ? cursor.getLong(size) : 0L
            );
        }
    }

    private static final class MutableAlbum {
        final long id;
        final String title;
        final String artist;
        final int year;
        final List<CatalogModels.Track> tracks = new ArrayList<>();
        String artworkProbe;
        long artworkVersion;

        MutableAlbum(long id, String title, String artist, int year) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.year = year;
        }

        void add(TrackRecord record) {
            String trackUri = AUDIO_ITEM_URI_PREFIX + record.id;
            tracks.add(new CatalogModels.Track(
                    record.id,
                    record.title,
                    record.artist,
                    record.album,
                    record.durationMs,
                    trackUri,
                    ""
            ));
            if (artworkProbe == null) {
                artworkProbe = trackUri;
            }
            artworkVersion = Math.max(artworkVersion,
                    record.modifiedSeconds * 31L + record.sizeBytes);
        }
    }

    private static final class CatalogBuild {
        final List<CatalogModels.Album> albums;
        final List<ArtworkRequest> artworkRequests;

        CatalogBuild(List<CatalogModels.Album> albums, List<ArtworkRequest> artworkRequests) {
            this.albums = albums;
            this.artworkRequests = artworkRequests;
        }
    }

    private static final class ArtworkRequest {
        final long albumId;
        final long version;
        final String probe;

        ArtworkRequest(long albumId, long version, String probe) {
            this.albumId = albumId;
            this.version = version;
            this.probe = probe;
        }
    }

    private static final class ArtworkEntry {
        final long version;
        final Bitmap bitmap;

        ArtworkEntry(long version, Bitmap bitmap) {
            this.version = version;
            this.bitmap = bitmap;
        }
    }

    private static final class TrackCache extends SQLiteOpenHelper {
        private static final String DATABASE = "music-index.db";
        private static final int VERSION = 1;
        private static final String TABLE = "tracks";

        TrackCache(Context context) {
            super(context, DATABASE, null, VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE " + TABLE + " ("
                    + "id INTEGER PRIMARY KEY,"
                    + "title TEXT NOT NULL,"
                    + "artist TEXT NOT NULL,"
                    + "album_name TEXT NOT NULL,"
                    + "album_id INTEGER NOT NULL,"
                    + "duration_ms INTEGER NOT NULL,"
                    + "track_number INTEGER NOT NULL,"
                    + "year INTEGER NOT NULL,"
                    + "modified_seconds INTEGER NOT NULL,"
                    + "size_bytes INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX tracks_album ON " + TABLE
                    + " (album_name COLLATE NOCASE, track_number)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            database.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(database);
        }

        LinkedHashMap<Long, TrackRecord> readAll() {
            LinkedHashMap<Long, TrackRecord> result = new LinkedHashMap<>();
            try (Cursor cursor = getReadableDatabase().query(TABLE, null,
                    null, null, null, null,
                    "album_name COLLATE NOCASE, track_number, title COLLATE NOCASE")) {
                int id = cursor.getColumnIndexOrThrow("id");
                int title = cursor.getColumnIndexOrThrow("title");
                int artist = cursor.getColumnIndexOrThrow("artist");
                int album = cursor.getColumnIndexOrThrow("album_name");
                int albumId = cursor.getColumnIndexOrThrow("album_id");
                int duration = cursor.getColumnIndexOrThrow("duration_ms");
                int track = cursor.getColumnIndexOrThrow("track_number");
                int year = cursor.getColumnIndexOrThrow("year");
                int modified = cursor.getColumnIndexOrThrow("modified_seconds");
                int size = cursor.getColumnIndexOrThrow("size_bytes");
                while (cursor.moveToNext()) {
                    TrackRecord record = new TrackRecord(
                            cursor.getLong(id),
                            cursor.getString(title),
                            cursor.getString(artist),
                            cursor.getString(album),
                            cursor.getLong(albumId),
                            cursor.getLong(duration),
                            cursor.getInt(track),
                            cursor.getInt(year),
                            cursor.getLong(modified),
                            cursor.getLong(size));
                    result.put(record.id, record);
                }
            }
            return result;
        }

        void apply(Diff diff) {
            SQLiteDatabase database = getWritableDatabase();
            database.beginTransaction();
            try {
                for (long id : diff.removals) {
                    database.delete(TABLE, "id = ?", new String[]{String.valueOf(id)});
                }
                for (TrackRecord record : diff.upserts) {
                    ContentValues values = new ContentValues();
                    values.put("id", record.id);
                    values.put("title", record.title);
                    values.put("artist", record.artist);
                    values.put("album_name", record.album);
                    values.put("album_id", record.albumId);
                    values.put("duration_ms", record.durationMs);
                    values.put("track_number", record.trackNumber);
                    values.put("year", record.year);
                    values.put("modified_seconds", record.modifiedSeconds);
                    values.put("size_bytes", record.sizeBytes);
                    database.insertWithOnConflict(TABLE, null, values,
                            SQLiteDatabase.CONFLICT_REPLACE);
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }
    }
}
