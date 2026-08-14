package com.yqdscott.walktape;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WalkTape's cinematic interface.  Drawing the shelf, J-card, cassette and
 * player in one canvas gives us a shared physical coordinate system for the
 * opening, unfolding and insertion animations.
 */
public final class WalkTapeView extends View {

    // A real tape reel does not need a 60 Hz redraw. Keeping the transport animation at a
    // cinematic cadence leaves substantially more CPU/GPU headroom for the realtime DSP.
    private static final long PLAYER_FRAME_INTERVAL_MS = 42L;
    private static final int MAX_RESIDENT_ARTWORK = 24;
    private static final double TWO_PI_FOR_DRAWING = Math.PI * 2.0;

    public interface Listener {
        void onImportRequested();

        void onTrackSelected(CatalogModels.Album album, CatalogModels.Track track);

        void onReturnToPlayer();

        void onPlayPauseRequested();

        void onStopRequested();

        void onSkipRequested(int direction);

        void onSeekRequested(float fraction);

        void onExitPlayer();

        void onHotlineChanged(boolean active);

        void onToneChanged(boolean highTape);

        default void onDolbyModeChanged(DolbyMode mode) {
        }

        default void onMachineProfileChanged(TapeMachineProfile profile) {
        }

        default void onTapeProfileChanged(TapeStockProfile profile) {
        }

        default void onRecordLevelChanged(RecordLevelProfile level) {
        }

        default void onConditionProfileChanged(MachineConditionProfile profile) {
        }

        default void onAlbumArtworkRequested(long albumId) {
        }

        default void onLyricsRequested(CatalogModels.Album album,
                                       CatalogModels.Track track,
                                       boolean forceRefresh) {
        }

        default void onLyricsSourceRequested(CatalogModels.Track track) {
        }

        default void onLyricsNetworkSettingsRequested(CatalogModels.Album album,
                                                       CatalogModels.Track track) {
        }
    }

    private enum Scene {
        LIBRARY,
        CASE,
        PLAYER
    }

    private enum LibraryStatus {
        LOADING,
        PERMISSION_REQUIRED,
        READY,
        EMPTY,
        ERROR
    }

    private static final int INK = 0xffe7dfca;
    private static final int MUTED_INK = 0xff968f81;
    private static final int DEEP = 0xff0a0b0b;
    private static final int PANEL = 0xff141513;
    private static final int SONY_BLUE = 0xff245c74;
    private static final int SONY_BLUE_DARK = 0xff173b4c;
    private static final int METAL = 0xffc9c8be;
    private static final int ORANGE = 0xffe95c2c;

    private static final String ACTION_IMPORT = "import";
    private static final String ACTION_ALBUM = "album";
    private static final String ACTION_BACK = "back";
    private static final String ACTION_JCARD = "jcard";
    private static final String ACTION_CASSETTE = "cassette";
    private static final String ACTION_DETAIL_CLOSE = "detail_close";
    private static final String ACTION_DETAIL_TRACK = "detail_track";
    private static final String ACTION_LYRICS_RETRY = "lyrics_retry";
    private static final String ACTION_LYRICS_SOURCE = "lyrics_source";
    private static final String ACTION_SHEET_CLOSE = "sheet_close";
    private static final String ACTION_TRACK = "track";
    private static final String ACTION_PLAY = "play";
    private static final String ACTION_STOP = "stop";
    private static final String ACTION_PREVIOUS = "previous";
    private static final String ACTION_NEXT = "next";
    private static final String ACTION_REWIND = "rewind";
    private static final String ACTION_FORWARD = "forward";
    private static final String ACTION_HOTLINE = "hotline";
    private static final String ACTION_TONE = "tone";
    private static final String ACTION_DOLBY = "dolby";
    private static final String ACTION_SEEK = "seek";
    private static final String ACTION_MINI_PLAYER = "mini_player";
    private static final String ACTION_MINI_PLAY = "mini_play";
    private static final String ACTION_MINI_NEXT = "mini_next";
    private static final String ACTION_INFO_FLIP = "info_flip";
    private static final String ACTION_INFO_FLIP_BACK = "info_flip_back";
    private static final String ACTION_INFO_TRACK = "info_track";
    private static final String ACTION_PLAYER_INFO_TOGGLE = "player_info_toggle";
    private static final String ACTION_PLAYER_VIEW_LOCK = "player_view_lock";
    private static final String ACTION_SETTINGS = "settings";
    private static final String ACTION_SETTINGS_CLOSE = "settings_close";
    private static final String ACTION_SETTINGS_SECTION = "settings_section";
    private static final String ACTION_RECORD_LEVEL = "record_level";
    private static final String ACTION_MACHINE_PROFILE = "machine_profile";
    private static final String ACTION_TAPE_PROFILE = "tape_profile";
    private static final String ACTION_CONDITION_PROFILE = "condition_profile";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    /**
     * Text is drawn with its own paint so its configuration can be left alone between calls.
     *
     * <p>Every {@code drawText} used to reconfigure the shared paint from scratch, and each of
     * those setters is a JNI call that dirties the native paint. Setting a shader in particular
     * forces Skia to rebuild its glyph strike, which is why text layout and strike lookup were the
     * dominant cost of a player frame. A dedicated paint means the values below are only pushed
     * across when they actually change.</p>
     */
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final android.text.TextPaint ellipsisPaint = new android.text.TextPaint();
    private float textPaintSize = -1f;
    private int textPaintColor = 0;
    private android.graphics.Typeface textPaintFace;
    private Paint.Align textPaintAlign;
    private float textPaintLetterSpacing = Float.NaN;
    /** Display density, refreshed once per frame instead of on each of the many dp() calls. */
    private float density = 1f;
    private final Paint texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix bitmapMatrix = new Matrix();
    private final Matrix textureMatrix = new Matrix();
    private final ArrayList<HitTarget> hitTargets = new ArrayList<>();
    private TapeMachineProfile profile = TapeMachineProfile.sonyTpsL2Reference();
    private TapeStockProfile tapeProfile = TapeStockProfile.sonyChf1978();
    private MachineConditionProfile conditionProfile = MachineConditionProfile.calibrated();
    private final LinkedHashMap<Long, Bitmap> residentArtwork =
            new LinkedHashMap<>(MAX_RESIDENT_ARTWORK + 1, 0.75f, true);
    private final Set<Long> requestedAlbumArtwork = new HashSet<>();
    private final Runnable playbackFrame = new Runnable() {
        @Override
        public void run() {
            playbackFramePosted = false;
            if (playing && scene == Scene.PLAYER && isShown()) {
                invalidate();
            }
        }
    };

    private final android.graphics.Typeface displayFace =
            android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL);
    private final android.graphics.Typeface labelFace =
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL);
    private final android.graphics.Typeface condensedFace =
            android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD);
    private final android.graphics.Typeface serifFace =
            android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL);

    private Listener listener;
    private List<CatalogModels.Album> albums = Collections.emptyList();
    private Scene scene = Scene.LIBRARY;
    private LibraryStatus libraryStatus = LibraryStatus.LOADING;
    private CatalogModels.Album selectedAlbum;
    private int selectedAlbumIndex = -1;
    private int selectedTrackIndex;
    private CatalogModels.Album nowPlayingAlbum;
    private int nowPlayingTrackIndex = -1;
    private boolean localLibrary;
    private boolean librarySyncing = true;
    private boolean catalogEverLoaded;
    private boolean playing;
    private boolean hotline;
    private boolean hotlinePressActive;
    private boolean highTape = true;
    private DolbyMode dolbyMode = DolbyMode.OFF;
    private RecordLevelProfile recordLevel = RecordLevelProfile.standard();

    // The machine chassis is almost entirely static but used to be rebuilt on every reel frame.
    // Cache it as one opaque frame, then redraw only the two reels and the live information panel.
    private Bitmap playerStaticFrame;
    private int playerStaticWidth;
    private int playerStaticHeight;
    private long playerStaticSignature = Long.MIN_VALUE;
    private int playerCacheFailedWidth;
    private int playerCacheFailedHeight;
    private int playerStaticBuildCount;
    private Bitmap playerTextureTile;
    private BitmapShader playerTextureShader;

    private float sceneReveal;
    private float miniPlayerReveal;
    private float caseOpen;
    private float detailProgress;
    private float detailTarget;
    private float trackSheetProgress;
    private float trackSheetTarget;
    private float libraryScroll;
    private float maxLibraryScroll;
    private float detailScroll;
    private float maxDetailScroll;
    private float trackScroll;
    private float maxTrackScroll;
    private float trackSheetTop;
    private float infoFlipProgress;
    private float infoFlipTarget;
    private float infoTrackScroll;
    private float maxInfoTrackScroll;
    private float settingsProgress;
    private float settingsTarget;
    private int settingsSection;
    private float playerInfoReveal = 1f;
    private float playerInfoTarget = 1f;
    private boolean playerViewLocked;
    private float audioMeterTarget;
    private float audioMeterDisplay;
    private float audioMeterHoldSeconds;
    private final RectF playerInfoBounds = new RectF();
    private float reelAngle;
    private float buttonPress;
    private String pressedAction;

    private long positionMs;
    private long durationMs = 1;
    private long lastFrameMs;
    private float downX;
    private float downY;
    private float lastTouchY;
    private boolean dragging;
    private boolean seeking;
    private boolean miniPlayerGesture;
    private boolean infoTrackGesture;
    private boolean settingsGesture;
    private boolean playbackFramePosted;

    public WalkTapeView(Context context) {
        this(context, null);
    }

    public WalkTapeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // dp() is reachable from layout and touch handling before the first frame is drawn.
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setContentDescription("WalkTape cassette library");
        texturePaint.setStrokeWidth(dp(0.6f));
        lastFrameMs = SystemClock.uptimeMillis();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width != oldWidth || height != oldHeight) {
            recyclePlayerStaticFrame();
            playerCacheFailedWidth = 0;
            playerCacheFailedHeight = 0;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(playbackFrame);
        playbackFramePosted = false;
        recyclePlayerStaticFrame();
        if (playerTextureTile != null && !playerTextureTile.isRecycled()) {
            playerTextureTile.recycle();
        }
        playerTextureTile = null;
        playerTextureShader = null;
        super.onDetachedFromWindow();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setMachineProfile(TapeMachineProfile machineProfile) {
        TapeMachineProfile selected = machineProfile == null
                ? TapeMachineProfile.sonyTpsL2Reference()
                : TapeMachineProfile.forId(machineProfile.id);
        if (selected.id.equals(profile.id)) {
            return;
        }
        profile = selected;
        if (!profile.hotlineSupported) {
            hotline = false;
            hotlinePressActive = false;
        }
        if (!profile.isSonyWmD6c()) {
            audioMeterTarget = 0f;
            audioMeterDisplay = 0f;
            audioMeterHoldSeconds = 0f;
        }
        recyclePlayerStaticFrame();
        playerCacheFailedWidth = 0;
        playerCacheFailedHeight = 0;
        invalidate();
    }

    public TapeMachineProfile getMachineProfile() {
        return profile;
    }

    public void setTapeProfile(TapeStockProfile requestedProfile) {
        TapeStockProfile selected = requestedProfile == null
                ? TapeStockProfile.sonyChf1978()
                : TapeStockProfile.forId(requestedProfile.id);
        if (selected.id.equals(tapeProfile.id)) {
            return;
        }
        tapeProfile = selected;
        recyclePlayerStaticFrame();
        playerCacheFailedWidth = 0;
        playerCacheFailedHeight = 0;
        invalidate();
    }

    public TapeStockProfile getTapeProfile() {
        return tapeProfile;
    }

    public void setConditionProfile(MachineConditionProfile requestedProfile) {
        MachineConditionProfile selected = requestedProfile == null
                ? MachineConditionProfile.calibrated()
                : MachineConditionProfile.forId(requestedProfile.id);
        if (selected.id.equals(conditionProfile.id)) {
            return;
        }
        conditionProfile = selected;
        recyclePlayerStaticFrame();
        playerCacheFailedWidth = 0;
        playerCacheFailedHeight = 0;
        invalidate();
    }

    public MachineConditionProfile getConditionProfile() {
        return conditionProfile;
    }

    public void setRecordLevel(RecordLevelProfile requested) {
        RecordLevelProfile selected = requested == null
                ? RecordLevelProfile.standard()
                : RecordLevelProfile.forId(requested.id);
        if (selected.id.equals(recordLevel.id)) {
            return;
        }
        recordLevel = selected;
        invalidate();
    }

    public RecordLevelProfile getRecordLevel() {
        return recordLevel;
    }

    public void setHighTape(boolean enabled) {
        if (highTape == enabled) {
            return;
        }
        highTape = enabled;
        recyclePlayerStaticFrame();
        invalidate();
    }

    public void setDolbyMode(DolbyMode mode) {
        DolbyMode selected = mode == null ? DolbyMode.OFF : mode;
        if (dolbyMode == selected) {
            return;
        }
        dolbyMode = selected;
        recyclePlayerStaticFrame();
        invalidate();
    }

    public DolbyMode getDolbyMode() {
        return dolbyMode;
    }

    public void setAlbums(List<CatalogModels.Album> newAlbums, boolean fromDevice) {
        List<CatalogModels.Album> replacement = newAlbums == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(newAlbums));
        preserveLyricsState(albums, replacement);
        boolean firstCatalog = !catalogEverLoaded;
        remapSelection(replacement);
        remapNowPlaying(replacement);
        albums = replacement;
        rebuildArtworkResidency();
        localLibrary = fromDevice;
        libraryStatus = albums.isEmpty() ? LibraryStatus.EMPTY : LibraryStatus.READY;
        catalogEverLoaded = true;
        if (firstCatalog && scene == Scene.LIBRARY) {
            libraryScroll = 0;
            restartScene();
        } else {
            invalidate();
        }
        if (firstCatalog) {
            announceForAccessibility(albums.isEmpty()
                    ? "设备上没有可读取的音乐"
                    : "已载入 " + albums.size() + " 张本地专辑");
        }
    }

    private static void preserveLyricsState(List<CatalogModels.Album> previous,
                                            List<CatalogModels.Album> replacement) {
        if (previous.isEmpty() || replacement.isEmpty()) {
            return;
        }
        Map<Long, CatalogModels.Track> oldTracks = new HashMap<>();
        for (CatalogModels.Album album : previous) {
            for (CatalogModels.Track track : album.tracks) {
                if (track.lyricsState != CatalogModels.LyricsState.IDLE) {
                    oldTracks.put(track.id, track);
                }
            }
        }
        if (oldTracks.isEmpty()) {
            return;
        }
        for (CatalogModels.Album album : replacement) {
            for (CatalogModels.Track track : album.tracks) {
                CatalogModels.Track oldTrack = oldTracks.get(track.id);
                if (oldTrack != null) {
                    track.copyLyricsFrom(oldTrack);
                }
            }
        }
    }

    public void showLibraryLoading() {
        libraryStatus = LibraryStatus.LOADING;
        librarySyncing = true;
        invalidate();
    }

    public void showMusicPermissionRequired() {
        albums = Collections.emptyList();
        residentArtwork.clear();
        requestedAlbumArtwork.clear();
        selectedAlbum = null;
        selectedAlbumIndex = -1;
        localLibrary = false;
        libraryStatus = LibraryStatus.PERMISSION_REQUIRED;
        librarySyncing = false;
        invalidate();
    }

    public void setLibrarySyncing(boolean syncing) {
        librarySyncing = syncing;
        invalidate();
    }

    public void showLibraryError() {
        librarySyncing = false;
        if (albums.isEmpty()) {
            libraryStatus = LibraryStatus.ERROR;
        }
        invalidate();
    }

    public void setAlbumArtwork(long albumId, Bitmap artwork) {
        if (artwork == null) {
            return;
        }
        for (CatalogModels.Album album : albums) {
            if (album.id == albumId) {
                album.artwork = artwork;
                break;
            }
        }
        if (selectedAlbum != null && selectedAlbum.id == albumId) {
            selectedAlbum.artwork = artwork;
        }
        if (nowPlayingAlbum != null && nowPlayingAlbum.id == albumId) {
            nowPlayingAlbum.artwork = artwork;
        }
        requestedAlbumArtwork.remove(albumId);
        residentArtwork.put(albumId, artwork);
        trimResidentArtwork();
        invalidate();
    }

    void setTrackLyricsLoading(long trackId) {
        updateTrackLyrics(trackId, CatalogModels.LyricsState.LOADING,
                "", "", "", "", false);
    }

    void setTrackLyrics(long trackId, LyricsRepository.Result result) {
        if (result == null) {
            updateTrackLyrics(trackId, CatalogModels.LyricsState.ERROR,
                    "", "", "", "歌词服务返回了空结果", false);
            return;
        }
        updateTrackLyrics(trackId, result.state,
                result.lyrics, result.source, result.sourceUrl, result.message,
                result.openNetworkSettings);
    }

    private void updateTrackLyrics(long trackId,
                                   CatalogModels.LyricsState state,
                                   String lyrics,
                                   String source,
                                   String sourceUrl,
                                   String message,
                                   boolean openNetworkSettings) {
        boolean updated = false;
        for (CatalogModels.Album album : albums) {
            updated |= updateAlbumTrackLyrics(album, trackId, state,
                    lyrics, source, sourceUrl, message, openNetworkSettings);
        }
        updated |= updateAlbumTrackLyrics(selectedAlbum, trackId, state,
                lyrics, source, sourceUrl, message, openNetworkSettings);
        updated |= updateAlbumTrackLyrics(nowPlayingAlbum, trackId, state,
                lyrics, source, sourceUrl, message, openNetworkSettings);
        if (updated) {
            invalidate();
        }
    }

    private static boolean updateAlbumTrackLyrics(CatalogModels.Album album,
                                                  long trackId,
                                                  CatalogModels.LyricsState state,
                                                   String lyrics,
                                                   String source,
                                                   String sourceUrl,
                                                   String message,
                                                   boolean openNetworkSettings) {
        if (album == null) {
            return false;
        }
        for (CatalogModels.Track track : album.tracks) {
            if (track.id == trackId) {
                track.updateLyrics(state, lyrics, source, sourceUrl, message,
                        openNetworkSettings);
                return true;
            }
        }
        return false;
    }

    private void rebuildArtworkResidency() {
        residentArtwork.clear();
        requestedAlbumArtwork.retainAll(albumIds(albums));
        for (CatalogModels.Album album : albums) {
            if (album.artwork != null && !album.artwork.isRecycled()) {
                residentArtwork.put(album.id, album.artwork);
            }
        }
        trimResidentArtwork();
    }

    private static Set<Long> albumIds(List<CatalogModels.Album> source) {
        Set<Long> result = new HashSet<>();
        for (CatalogModels.Album album : source) {
            result.add(album.id);
        }
        return result;
    }

    private void touchOrRequestArtwork(CatalogModels.Album album) {
        if (album == null) {
            return;
        }
        Bitmap artwork = album.artwork;
        if (artwork != null && !artwork.isRecycled()) {
            residentArtwork.put(album.id, artwork);
            trimResidentArtwork();
            return;
        }
        if (listener != null && requestedAlbumArtwork.add(album.id)) {
            listener.onAlbumArtworkRequested(album.id);
        }
    }

    private void trimResidentArtwork() {
        while (residentArtwork.size() > MAX_RESIDENT_ARTWORK) {
            boolean removed = false;
            Iterator<Map.Entry<Long, Bitmap>> iterator = residentArtwork.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Bitmap> candidate = iterator.next();
                long albumId = candidate.getKey();
                if ((selectedAlbum != null && selectedAlbum.id == albumId)
                        || (nowPlayingAlbum != null && nowPlayingAlbum.id == albumId)) {
                    continue;
                }
                Bitmap bitmap = candidate.getValue();
                iterator.remove();
                requestedAlbumArtwork.remove(albumId);
                clearAlbumArtworkReference(albumId, bitmap);
                removed = true;
                break;
            }
            if (!removed) {
                break;
            }
        }
    }

    private void clearAlbumArtworkReference(long albumId, Bitmap bitmap) {
        for (CatalogModels.Album album : albums) {
            if (album.id == albumId && album.artwork == bitmap) {
                album.artwork = null;
                break;
            }
        }
    }

    int residentArtworkCountForTest() {
        return residentArtwork.size();
    }

    private void remapSelection(List<CatalogModels.Album> replacement) {
        if (selectedAlbum == null) {
            return;
        }
        CatalogModels.Track selectedTrack = getSelectedTrack();
        long selectedTrackId = selectedTrack == null ? Long.MIN_VALUE : selectedTrack.id;
        for (int albumIndex = 0; albumIndex < replacement.size(); albumIndex++) {
            CatalogModels.Album candidate = replacement.get(albumIndex);
            if (candidate.id != selectedAlbum.id) {
                continue;
            }
            for (int trackIndex = 0; trackIndex < candidate.tracks.size(); trackIndex++) {
                if (candidate.tracks.get(trackIndex).id == selectedTrackId) {
                    selectedAlbum = candidate;
                    selectedAlbumIndex = albumIndex;
                    selectedTrackIndex = trackIndex;
                    return;
                }
            }
            // Keep the currently playing stale object when its source disappears mid-playback.
            if (scene != Scene.PLAYER && !candidate.tracks.isEmpty()) {
                selectedAlbum = candidate;
                selectedAlbumIndex = albumIndex;
                selectedTrackIndex = 0;
            }
            return;
        }
    }

    private void remapNowPlaying(List<CatalogModels.Album> replacement) {
        CatalogModels.Track playingTrack = getNowPlayingTrack();
        if (nowPlayingAlbum == null || playingTrack == null) {
            return;
        }
        for (int albumIndex = 0; albumIndex < replacement.size(); albumIndex++) {
            CatalogModels.Album candidate = replacement.get(albumIndex);
            if (candidate.id != nowPlayingAlbum.id) {
                continue;
            }
            for (int trackIndex = 0; trackIndex < candidate.tracks.size(); trackIndex++) {
                if (candidate.tracks.get(trackIndex).id == playingTrack.id) {
                    nowPlayingAlbum = candidate;
                    nowPlayingTrackIndex = trackIndex;
                    if (scene == Scene.PLAYER) {
                        selectedAlbum = candidate;
                        selectedAlbumIndex = albumIndex;
                        selectedTrackIndex = trackIndex;
                    }
                    return;
                }
            }
            // Keep the stale object if the live source disappears during a MediaStore update;
            // changing the label to an unrelated track while it is audible is more confusing.
            return;
        }
    }

    public void setPlaying(boolean value) {
        if (playing == value) {
            return;
        }
        playing = value;
        invalidate();
    }

    public void setHotlineActive(boolean value) {
        hotline = value;
        invalidate();
    }

    public void setPlaybackPosition(long position, long duration) {
        durationMs = Math.max(1, duration);
        // Keep the thumb under the user's finger. The transport keeps reporting its old playhead
        // until a scrub is committed, which used to make the thumb jump backwards every 180 ms.
        if (!seeking) {
            positionMs = Math.max(0, position);
        }
        invalidate();
    }

    /** Supplies post-DSP peaks. The D6C detector follows the louder instantaneous channel. */
    public void setAudioMeterLevels(float leftPeak, float rightPeak) {
        float target = profile.isSonyWmD6c()
                ? clamp(Math.max(Math.abs(leftPeak), Math.abs(rightPeak)), 0f, 1f)
                : 0f;
        if (Math.abs(target - audioMeterTarget) < 0.001f) {
            return;
        }
        audioMeterTarget = target;
        if (target >= audioMeterDisplay) {
            audioMeterHoldSeconds = 0.070f;
        }
        invalidate();
    }

    public CatalogModels.Album getSelectedAlbum() {
        return selectedAlbum;
    }

    public CatalogModels.Track getSelectedTrack() {
        if (selectedAlbum == null || selectedAlbum.tracks.isEmpty()) {
            return null;
        }
        int safeIndex = Math.max(0, Math.min(selectedTrackIndex, selectedAlbum.tracks.size() - 1));
        return selectedAlbum.tracks.get(safeIndex);
    }

    public CatalogModels.Track getNowPlayingTrack() {
        if (nowPlayingAlbum == null || nowPlayingAlbum.tracks.isEmpty()
                || nowPlayingTrackIndex < 0) {
            return null;
        }
        int safeIndex = Math.min(nowPlayingTrackIndex, nowPlayingAlbum.tracks.size() - 1);
        return nowPlayingAlbum.tracks.get(safeIndex);
    }

    public CatalogModels.Album getNowPlayingAlbum() {
        return nowPlayingAlbum;
    }

    /** Advances within the current album without wrapping its final track back to side A. */
    boolean advanceAfterCompletion() {
        if (nowPlayingAlbum == null || nowPlayingAlbum.tracks.isEmpty()
                || nowPlayingTrackIndex < 0
                || nowPlayingTrackIndex + 1 >= nowPlayingAlbum.tracks.size()) {
            return false;
        }
        skipTrack(1);
        return true;
    }

    public boolean isPlayerScene() {
        return scene == Scene.PLAYER;
    }

    public boolean handleBackPressed() {
        if (settingsTarget > 0f || settingsProgress > 0.01f) {
            settingsTarget = 0f;
            animateNextFrame();
            return true;
        }
        if (detailTarget > 0f || detailProgress > 0.01f) {
            detailTarget = 0f;
            animateNextFrame();
            return true;
        }
        if (trackSheetTarget > 0f || trackSheetProgress > 0.01f) {
            trackSheetTarget = 0f;
            animateNextFrame();
            return true;
        }
        if (scene == Scene.PLAYER && (infoFlipTarget > 0f || infoFlipProgress > 0.01f)) {
            infoFlipTarget = 0f;
            animateNextFrame();
            announceForAccessibility("返回播放信息");
            return true;
        }
        if (scene == Scene.PLAYER && (playerInfoTarget < 0.5f || playerInfoReveal < 0.99f)) {
            playerInfoTarget = 1f;
            recyclePlayerStaticFrame();
            animateNextFrame();
            announceForAccessibility("显示播放信息");
            return true;
        }
        if (scene == Scene.PLAYER) {
            scene = Scene.CASE;
            hotline = false;
            trackSheetTarget = 0f;
            trackSheetProgress = 0f;
            if (listener != null) {
                listener.onHotlineChanged(false);
                listener.onExitPlayer();
            }
            restartScene();
            return true;
        }
        if (scene == Scene.CASE) {
            scene = Scene.LIBRARY;
            selectedAlbum = null;
            selectedAlbumIndex = -1;
            restartScene();
            return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Read once per frame rather than inside every one of the many dp() conversions a frame
        // performs; it still tracks a configuration change on the very next frame.
        density = getResources().getDisplayMetrics().density;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, Math.max(0.001f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;
        boolean moving = updateAnimation(dt);
        hitTargets.clear();

        if (scene == Scene.LIBRARY) {
            drawLibrary(canvas);
        } else if (scene == Scene.CASE) {
            drawCaseScene(canvas);
        } else {
            drawPlayerScene(canvas);
        }

        if (scene != Scene.PLAYER && miniPlayerReveal > 0.001f
                && detailTarget == 0f && trackSheetTarget == 0f) {
            drawMiniPlayer(canvas);
        }

        if (settingsProgress > 0.001f || settingsTarget > 0f) {
            drawMachineSettings(canvas);
        }

        if (moving) {
            animateNextFrame();
        } else if (playing && scene == Scene.PLAYER) {
            schedulePlaybackFrame();
        }
    }

    private boolean updateAnimation(float dt) {
        boolean moving = false;
        float old = sceneReveal;
        sceneReveal = approach(sceneReveal, 1f, dt * 2.8f);
        moving |= old != sceneReveal;

        float miniOld = miniPlayerReveal;
        float miniTarget = scene != Scene.PLAYER && getNowPlayingTrack() != null
                && detailTarget == 0f && trackSheetTarget == 0f ? 1f : 0f;
        miniPlayerReveal = approach(miniPlayerReveal, miniTarget, dt * 5.4f);
        moving |= miniOld != miniPlayerReveal;

        float caseOld = caseOpen;
        caseOpen = approach(caseOpen, scene == Scene.CASE ? 1f : 0f, dt * 2.35f);
        moving |= caseOld != caseOpen;

        float detailOld = detailProgress;
        detailProgress = approach(detailProgress, detailTarget, dt * 3.2f);
        moving |= detailOld != detailProgress;

        float sheetOld = trackSheetProgress;
        trackSheetProgress = approach(trackSheetProgress, trackSheetTarget, dt * 4.1f);
        moving |= sheetOld != trackSheetProgress;

        float infoFlipOld = infoFlipProgress;
        infoFlipProgress = approach(infoFlipProgress, infoFlipTarget, dt * 4.8f);
        moving |= infoFlipOld != infoFlipProgress;

        float settingsOld = settingsProgress;
        settingsProgress = approach(settingsProgress, settingsTarget, dt * 5.2f);
        moving |= settingsOld != settingsProgress;

        float playerInfoOld = playerInfoReveal;
        playerInfoReveal = approach(playerInfoReveal, playerInfoTarget, dt * 5.2f);
        moving |= playerInfoOld != playerInfoReveal;

        float meterOld = audioMeterDisplay;
        if (audioMeterTarget >= audioMeterDisplay) {
            audioMeterDisplay = approach(audioMeterDisplay, audioMeterTarget, dt * 28f);
            audioMeterHoldSeconds = 0.070f;
        } else if (audioMeterHoldSeconds > 0f) {
            audioMeterHoldSeconds = Math.max(0f, audioMeterHoldSeconds - dt);
        } else {
            audioMeterDisplay = approach(audioMeterDisplay, audioMeterTarget, dt * 3.6f);
        }
        if (Math.abs(audioMeterDisplay) < 0.0001f && audioMeterTarget == 0f) {
            audioMeterDisplay = 0f;
        }
        moving |= meterOld != audioMeterDisplay;

        float pressOld = buttonPress;
        buttonPress = approach(buttonPress, pressedAction == null ? 0f : 1f, dt * 10f);
        moving |= pressOld != buttonPress;

        if (playing) {
            reelAngle = (reelAngle + dt * 255f) % 360f;
            CatalogModels.Track track = getNowPlayingTrack();
            if (track != null && track.contentUri == null) {
                positionMs += (long) (dt * 1000f);
                durationMs = Math.max(1, track.durationMs);
                if (positionMs >= durationMs) {
                    positionMs = durationMs;
                    playing = false;
                }
            }
        }
        return moving;
    }

    private void drawLibrary(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, 0, 0, height,
                new int[]{0xff171817, 0xff0e0f0f, 0xff090a0a},
                new float[]{0f, 0.56f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        drawAmbientGlow(canvas, width * 0.18f, height * 0.16f, width * 0.72f, 0x18d99b62);
        drawFineGrain(canvas, 0xff2c2924, 0.12f);

        float side = dp(20);
        float reveal = easeOut(sceneReveal);
        float baseHeaderY = dp(94) + dp(14) * (1f - reveal);

        int columns = width >= dp(700) ? 5 : (width >= dp(430) ? 4 : 3);
        float gap = dp(10);
        float cardWidth = (width - side * 2 - gap * (columns - 1)) / columns;
        float cardHeight = cardWidth * 1.43f;
        float rowHeight = cardHeight + dp(73);
        int rows = albums.isEmpty() ? 0 : (int) Math.ceil(albums.size() / (float) columns);
        float miniPlayerInset = getNowPlayingTrack() == null ? 0f : dp(92);
        maxLibraryScroll = albums.isEmpty() ? 0 : Math.max(0,
                baseHeaderY + dp(55) + rows * rowHeight - height + dp(26)
                        + miniPlayerInset);
        libraryScroll = clamp(libraryScroll, 0, maxLibraryScroll);
        float contentOffset = libraryScroll;

        drawText(canvas, "WALKTAPE", side, dp(30) - contentOffset, dp(14), INK,
                labelFace, Paint.Align.LEFT, 1f);
        drawText(canvas, "A PERSONAL CASSETTE ARCHIVE", side, dp(45) - contentOffset,
                dp(7.3f), MUTED_INK,
                labelFace, Paint.Align.LEFT, 1.7f);

        RectF importRect = new RectF(width - dp(128), dp(15) - contentOffset,
                width - side, dp(47) - contentOffset);
        String libraryAction;
        if (libraryStatus == LibraryStatus.PERMISSION_REQUIRED) {
            libraryAction = "+  ALLOW MUSIC";
        } else if (librarySyncing || libraryStatus == LibraryStatus.LOADING) {
            libraryAction = "SYNCING...";
        } else if (albums.isEmpty()) {
            libraryAction = "RESCAN";
        } else {
            libraryAction = "LIVE  ·  ON DEVICE";
        }
        drawPill(canvas, importRect, libraryAction,
                localLibrary && !albums.isEmpty() ? 0xff31433e : 0xff252622,
                localLibrary && !albums.isEmpty() ? 0xffa9c1b7 : INK);
        addHit(ACTION_IMPORT, importRect, -1);

        RectF settings = new RectF(width - dp(168), dp(15) - contentOffset,
                width - dp(136), dp(47) - contentOffset);
        drawSettingsButton(canvas, settings, false);
        addHit(ACTION_SETTINGS, settings, -1);

        float headerY = baseHeaderY - contentOffset;
        drawText(canvas, "YOUR TAPES", side, headerY, dp(33), INK, displayFace, Paint.Align.LEFT, 0.2f);
        drawText(canvas,
                albums.isEmpty() && libraryStatus == LibraryStatus.LOADING
                        ? "MEDIASTORE  ·  STANDING BY"
                        : String.format(Locale.US, "%02d CASSETTES  ·  LIVE INDEX", albums.size()),
                side,
                headerY + dp(25),
                dp(8),
                MUTED_INK,
                labelFace,
                Paint.Align.LEFT,
                1.4f);

        if (albums.isEmpty()) {
            drawEmptyLibrary(canvas, width, height, headerY);
            return;
        }

        float firstY = headerY + dp(55);

        for (int row = 0; row < rows; row++) {
            float y = firstY + row * rowHeight;
            if (y > height + dp(30) || y + cardHeight + dp(50) < dp(60)) {
                continue;
            }
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= albums.size()) {
                    break;
                }
                float x = side + column * (cardWidth + gap);
                float stagger = Math.min(1f, Math.max(0f, sceneReveal * 1.4f - index * 0.055f));
                float cardY = y + dp(18) * (1f - easeOut(stagger));
                RectF card = new RectF(x, cardY, x + cardWidth, cardY + cardHeight);
                drawAlbumCase(canvas, card, albums.get(index), index, stagger);
                RectF target = new RectF(card);
                target.bottom += dp(37);
                addHit(ACTION_ALBUM, target, index);
            }
            drawWoodShelf(canvas, y + cardHeight + dp(39), width, row);
        }

        if (maxLibraryScroll > 0) {
            float railTop = dp(18);
            float railBottom = height - dp(18) - miniPlayerInset;
            float thumb = Math.max(dp(24), (railBottom - railTop) * (height / (height + maxLibraryScroll)));
            float thumbY = railTop + (railBottom - railTop - thumb) * (libraryScroll / maxLibraryScroll);
            paint.setColor(0x24ffffff);
            canvas.drawRoundRect(width - dp(4), railTop, width - dp(2.5f), railBottom, dp(1), dp(1), paint);
            paint.setColor(0x88d4c9b1);
            canvas.drawRoundRect(width - dp(4.5f), thumbY, width - dp(2), thumbY + thumb, dp(2), dp(2), paint);
        }
    }

    private void drawMiniPlayer(Canvas canvas) {
        CatalogModels.Track track = getNowPlayingTrack();
        if (track == null || nowPlayingAlbum == null) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        float reveal = easeOut(miniPlayerReveal);
        float side = dp(12);
        float barHeight = dp(72);
        float bottom = height - dp(12) + dp(18) * (1f - reveal);
        RectF bar = new RectF(side, bottom - barHeight, width - side, bottom);

        int layer = canvas.saveLayerAlpha(0, 0, width, height,
                Math.round(255f * reveal));
        for (int shadow = 4; shadow >= 1; shadow--) {
            paint.setColor(withAlpha(Color.BLACK, 0.035f * shadow));
            float spread = dp(shadow * 1.2f);
            canvas.drawRoundRect(bar.left - spread * 0.25f,
                    bar.top + dp(2) + spread * 0.35f,
                    bar.right + spread * 0.25f,
                    bar.bottom + spread,
                    dp(22), dp(22), paint);
        }

        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, bar.top, 0, bar.bottom,
                new int[]{0xfffaf8f2, 0xffe8e4da}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bar, dp(20), dp(20), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(0x66ffffff);
        canvas.drawRoundRect(inset(bar, dp(0.6f)), dp(19.4f), dp(19.4f), paint);
        paint.setStyle(Paint.Style.FILL);

        RectF artwork = new RectF(bar.left + dp(10), bar.top + dp(10),
                bar.left + dp(62), bar.bottom - dp(10));
        drawMiniArtwork(canvas, artwork, nowPlayingAlbum);

        RectF next = new RectF(bar.right - dp(52), bar.top + dp(12),
                bar.right - dp(8), bar.bottom - dp(12));
        RectF play = new RectF(next.left - dp(48), bar.top + dp(12),
                next.left - dp(4), bar.bottom - dp(12));
        float copyLeft = artwork.right + dp(11);
        float copyRight = play.left - dp(7);

        drawText(canvas, "NOW PLAYING", copyLeft, bar.top + dp(18), dp(5.3f),
                0xff827d73, labelFace, Paint.Align.LEFT, 1.45f);
        drawEllipsizedText(canvas, track.title, copyLeft, bar.top + dp(39),
                Math.max(dp(24), copyRight - copyLeft), dp(11.3f), 0xff171816,
                labelFace, Paint.Align.LEFT);
        drawEllipsizedText(canvas, nowPlayingAlbum.artist, copyLeft, bar.top + dp(56),
                Math.max(dp(24), copyRight - copyLeft), dp(6.5f), 0xff777269,
                labelFace, Paint.Align.LEFT);

        boolean playPressed = ACTION_MINI_PLAY.equals(pressedAction);
        paint.setColor(playPressed ? darken(nowPlayingAlbum.accent, 0.12f) : 0xff171816);
        canvas.drawCircle(play.centerX(), play.centerY(), dp(playPressed ? 19f : 18f), paint);
        drawMiniPlayGlyph(canvas, play.centerX(), play.centerY(), playing, 0xfffaf8f2);

        if (ACTION_MINI_NEXT.equals(pressedAction)) {
            paint.setColor(0x14201f1d);
            canvas.drawCircle(next.centerX(), next.centerY(), dp(19), paint);
        }
        drawMiniNextGlyph(canvas, next.centerX(), next.centerY(), 0xff272622);

        float progressLeft = bar.left + dp(14);
        float progressRight = bar.right - dp(14);
        float progressY = bar.bottom - dp(2.6f);
        float fraction = durationMs <= 0 ? 0f : clamp(positionMs / (float) durationMs, 0f, 1f);
        paint.setColor(0x18000000);
        canvas.drawRoundRect(progressLeft, progressY, progressRight,
                progressY + dp(1.2f), dp(1), dp(1), paint);
        if (fraction > 0f) {
            paint.setColor(nowPlayingAlbum.accent);
            canvas.drawRoundRect(progressLeft, progressY,
                    progressLeft + (progressRight - progressLeft) * fraction,
                    progressY + dp(1.2f), dp(1), dp(1), paint);
        }
        canvas.restoreToCount(layer);

        if (miniPlayerReveal > 0.72f) {
            addHit(ACTION_MINI_PLAYER, bar, -1);
            addHit(ACTION_MINI_PLAY, play, -1);
            addHit(ACTION_MINI_NEXT, next, -1);
        }
    }

    private void drawMiniArtwork(Canvas canvas, RectF rect, CatalogModels.Album album) {
        canvas.save();
        path.reset();
        path.addRoundRect(rect, dp(11), dp(11), Path.Direction.CW);
        canvas.clipPath(path);
        if (album.artwork != null && !album.artwork.isRecycled()) {
            drawCenterCrop(canvas, album.artwork, rect);
        } else {
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{lighten(album.paper, 0.08f), album.accent,
                            darken(album.ink, 0.08f)}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            paint.setColor(0x48ffffff);
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.24f, paint);
            paint.setColor(0x99000000);
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.075f, paint);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(0x26000000);
        canvas.drawRoundRect(rect, dp(11), dp(11), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMiniPlayGlyph(Canvas canvas, float cx, float cy,
                                   boolean pause, int color) {
        paint.setColor(color);
        if (pause) {
            canvas.drawRoundRect(cx - dp(5), cy - dp(6.5f), cx - dp(1.5f),
                    cy + dp(6.5f), dp(1), dp(1), paint);
            canvas.drawRoundRect(cx + dp(1.5f), cy - dp(6.5f), cx + dp(5),
                    cy + dp(6.5f), dp(1), dp(1), paint);
            return;
        }
        path.reset();
        path.moveTo(cx - dp(4.5f), cy - dp(7));
        path.lineTo(cx + dp(7), cy);
        path.lineTo(cx - dp(4.5f), cy + dp(7));
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawMiniNextGlyph(Canvas canvas, float cx, float cy, int color) {
        paint.setColor(color);
        path.reset();
        path.moveTo(cx - dp(7), cy - dp(7));
        path.lineTo(cx + dp(4.5f), cy);
        path.lineTo(cx - dp(7), cy + dp(7));
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawRoundRect(cx + dp(5), cy - dp(7), cx + dp(7.5f), cy + dp(7),
                dp(0.8f), dp(0.8f), paint);
    }

    private void drawEmptyLibrary(Canvas canvas, int width, int height, float headerY) {
        float caseWidth = Math.min(dp(122), width * 0.34f);
        float caseHeight = caseWidth * 1.38f;
        float top = Math.min(headerY + dp(82), height * 0.34f);
        RectF ghost = new RectF(width * 0.5f - caseWidth * 0.5f, top,
                width * 0.5f + caseWidth * 0.5f, top + caseHeight);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x4de6ddc7);
        canvas.drawRoundRect(ghost, dp(5), dp(5), paint);
        RectF insert = inset(ghost, dp(8));
        paint.setColor(0x242ea1a1);
        canvas.drawRoundRect(insert, dp(2), dp(2), paint);
        paint.setStrokeWidth(dp(0.55f));
        paint.setColor(0x30e6ddc7);
        for (int line = 1; line < 5; line++) {
            float y = insert.top + insert.height() * line / 5f;
            canvas.drawLine(insert.left, y, insert.right, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        String title;
        String detail;
        switch (libraryStatus) {
            case PERMISSION_REQUIRED:
                title = "YOUR SHELF IS PRIVATE";
                detail = "ALLOW MUSIC ACCESS  ·  NOTHING LEAVES THIS DEVICE";
                break;
            case EMPTY:
                title = "THE SHELF IS READY";
                detail = "ADD OR REMOVE MUSIC  ·  WALKTAPE UPDATES LIVE";
                break;
            case ERROR:
                title = "THE INDEX PAUSED";
                detail = "TAP RESCAN TO TRY THE MEDIA LIBRARY AGAIN";
                break;
            case LOADING:
            default:
                title = "BUILDING YOUR SHELF";
                detail = "METADATA FIRST  ·  COVERS FOLLOW IN BACKGROUND";
                break;
        }
        float copyY = ghost.bottom + dp(35);
        drawText(canvas, title, width * 0.5f, copyY, dp(9.5f), INK,
                labelFace, Paint.Align.CENTER, 1.25f);
        drawText(canvas, detail, width * 0.5f, copyY + dp(18), dp(5.8f), MUTED_INK,
                labelFace, Paint.Align.CENTER, 0.85f);
        drawWoodShelf(canvas, Math.min(height - dp(54), ghost.bottom + dp(78)), width, 0);
    }

    private void drawAlbumCase(Canvas canvas,
                               RectF rect,
                               CatalogModels.Album album,
                               int index,
                               float reveal) {
        float alpha = clamp(reveal, 0f, 1f);
        canvas.save();
        float lean = ((index % 5) - 2) * 0.45f;
        canvas.rotate(lean * alpha, rect.centerX(), rect.bottom);

        paint.setColor(withAlpha(Color.BLACK, 0.38f * alpha));
        canvas.drawRoundRect(rect.left + dp(3), rect.top + dp(7), rect.right + dp(6), rect.bottom + dp(10),
                dp(4), dp(4), paint);

        paint.setAlpha(255);
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0x99f5f3e9, 0x36b5b7b2, 0x84f4f1e5},
                null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, dp(4), dp(4), paint);
        paint.setShader(null);

        RectF art = inset(rect, dp(5.5f));
        drawCoverArt(canvas, art, album);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(withAlpha(0xfffaf8ef, 0.62f * alpha));
        canvas.drawRoundRect(rect, dp(4), dp(4), paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(withAlpha(Color.WHITE, 0.12f * alpha));
        path.reset();
        path.moveTo(rect.left + dp(3), rect.top + dp(2));
        path.lineTo(rect.right - dp(3), rect.top + dp(2));
        path.lineTo(rect.right - dp(15), rect.top + dp(10));
        path.lineTo(rect.left + dp(8), rect.top + dp(10));
        path.close();
        canvas.drawPath(path, paint);

        float baseline = rect.bottom + dp(17);
        drawEllipsizedText(canvas, album.title, rect.left, baseline, rect.width(), dp(10.5f),
                withAlpha(INK, alpha), labelFace, Paint.Align.LEFT);
        drawEllipsizedText(canvas, album.artist.toUpperCase(Locale.ROOT), rect.left, baseline + dp(13),
                rect.width(), dp(6.4f), withAlpha(MUTED_INK, alpha), labelFace, Paint.Align.LEFT);
        canvas.restore();
    }

    private void drawWoodShelf(Canvas canvas, float y, int width, int row) {
        paint.setColor(0x65000000);
        canvas.drawRect(0, y + dp(7), width, y + dp(17), paint);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, y, 0, y + dp(15),
                new int[]{0xff5a4431, 0xff2d231c, 0xff151311}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, y, width, y + dp(14), paint);
        paint.setShader(null);
        paint.setColor(0x338f6d4d);
        canvas.drawRect(0, y + dp(1), width, y + dp(2), paint);
        paint.setColor(0x25201913);
        for (int i = 0; i < 9; i++) {
            float start = ((i * 83 + row * 31) % Math.max(1, width));
            canvas.drawLine(start, y + dp(4 + i % 4), Math.min(width, start + dp(34 + i * 2)),
                    y + dp(4.4f + i % 4), paint);
        }
    }

    private void drawCaseScene(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        drawDeskBackground(canvas, width, height);

        float side = dp(18);
        RectF back = new RectF(side, dp(15), side + dp(38), dp(53));
        drawRoundIcon(canvas, back, "‹");
        addHit(ACTION_BACK, back, -1);

        if (selectedAlbum == null) {
            scene = Scene.LIBRARY;
            restartScene();
            return;
        }

        drawText(canvas, "OPEN CASE", side + dp(52), dp(31), dp(8), MUTED_INK,
                labelFace, Paint.Align.LEFT, 1.5f);
        drawEllipsizedText(canvas, selectedAlbum.title, side + dp(52), dp(49),
                width - side * 2 - dp(52), dp(17), INK, displayFace, Paint.Align.LEFT);

        float reveal = easeOut(sceneReveal);
        float open = easeInOut(caseOpen);
        float margin = dp(13);
        float gap = dp(7);
        float top = dp(82) + dp(18) * (1f - reveal);
        float panelWidth = (width - margin * 2 - gap) / 2f;
        float panelHeight = Math.min(dp(284), Math.max(dp(230), height * 0.37f));
        float closedX = width / 2f - panelWidth / 2f;
        float leftX = lerp(closedX, margin, open);
        float rightX = lerp(closedX, margin + panelWidth + gap, open);

        RectF leftPanel = new RectF(leftX, top, leftX + panelWidth, top + panelHeight);
        RectF rightPanel = new RectF(rightX, top, rightX + panelWidth, top + panelHeight);
        drawAcrylicPanel(canvas, leftPanel, true, open);
        drawAcrylicPanel(canvas, rightPanel, false, open);

        RectF jCard = inset(leftPanel, dp(8));
        jCard.bottom -= dp(8);
        drawJCardCover(canvas, jCard, selectedAlbum, open);
        addHit(ACTION_JCARD, jCard, -1);

        float cassetteWidth = rightPanel.width() - dp(13);
        float cassetteHeight = cassetteWidth * 0.635f;
        RectF cassette = new RectF(
                rightPanel.centerX() - cassetteWidth / 2f,
                rightPanel.top + dp(37),
                rightPanel.centerX() + cassetteWidth / 2f,
                rightPanel.top + dp(37) + cassetteHeight);
        drawCassette(canvas, cassette, selectedAlbum, reelAngle, false);
        addHit(ACTION_CASSETTE, new RectF(rightPanel), -1);

        drawText(canvas, "J-CARD", leftPanel.centerX(), leftPanel.bottom + dp(21), dp(7.2f),
                MUTED_INK, labelFace, Paint.Align.CENTER, 1.6f);
        drawText(canvas, "TAP TO UNFOLD", leftPanel.centerX(), leftPanel.bottom + dp(34), dp(6.4f),
                0xff625d54, labelFace, Paint.Align.CENTER, 1.1f);
        drawText(canvas, "CASSETTE", rightPanel.centerX(), rightPanel.bottom + dp(21), dp(7.2f),
                MUTED_INK, labelFace, Paint.Align.CENTER, 1.6f);
        drawText(canvas, "TAP TO CHOOSE A TRACK", rightPanel.centerX(), rightPanel.bottom + dp(34),
                dp(6.4f), 0xff625d54, labelFace, Paint.Align.CENTER, 1.1f);

        float metaTop = top + panelHeight + dp(61);
        if (metaTop < height - dp(74)) {
            drawText(canvas, selectedAlbum.artist.toUpperCase(Locale.ROOT), side, metaTop, dp(8),
                    selectedAlbum.accent, labelFace, Paint.Align.LEFT, 1.5f);
            drawText(canvas, selectedAlbum.year + "  /  " + tapeProfile.typeShortLabel()
                            + "  /  SIDE A", width - side, metaTop,
                    dp(7.2f), MUTED_INK, labelFace, Paint.Align.RIGHT, 1.1f);
            paint.setColor(0x22ffffff);
            canvas.drawRect(side, metaTop + dp(12), width - side, metaTop + dp(12.7f), paint);
            drawParagraph(canvas, selectedAlbum.description, side, metaTop + dp(31),
                    width - side * 2, dp(10), 0xffb8b09f, serifFace, dp(16), 3);
        }

        if (trackSheetProgress > 0.001f) {
            drawTrackSheet(canvas);
        }
        if (detailProgress > 0.001f) {
            drawJCardDetail(canvas);
        }
    }

    private void drawDeskBackground(Canvas canvas, int width, int height) {
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{0xff171412, 0xff0d0d0c, 0xff12100f},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        drawAmbientGlow(canvas, width * 0.72f, height * 0.17f, width * 0.72f, 0x1cc47846);
        paint.setStrokeWidth(dp(0.7f));
        for (int i = 0; i < 17; i++) {
            float y = ((i * 79 + 31) % Math.max(1, height));
            paint.setColor(i % 3 == 0 ? 0x191f1813 : 0x101f1813);
            path.reset();
            path.moveTo(0, y);
            path.cubicTo(width * 0.22f, y + dp((i % 5) - 2), width * 0.73f,
                    y - dp((i % 4) - 1), width, y + dp((i % 3) - 1));
            canvas.drawPath(path, paint);
        }
        drawFineGrain(canvas, 0xff4a4037, 0.15f);
    }

    private void drawAcrylicPanel(Canvas canvas, RectF rect, boolean left, float open) {
        paint.setColor(0x62000000);
        canvas.drawRoundRect(rect.left + dp(3), rect.top + dp(8), rect.right + dp(6),
                rect.bottom + dp(9), dp(8), dp(8), paint);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0x5cf1f1e9, 0x191c1d1e, 0x4dc3c3bc}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, dp(7), dp(7), paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x96d8d7ce);
        canvas.drawRoundRect(rect, dp(7), dp(7), paint);
        paint.setColor(0x30232220);
        canvas.drawRoundRect(inset(rect, dp(4)), dp(5), dp(5), paint);
        paint.setStyle(Paint.Style.FILL);

        if (left) {
            paint.setColor(0x6fb2b1aa);
            float hingeX = rect.right - dp(2.5f);
            canvas.drawRoundRect(hingeX, rect.top + rect.height() * 0.24f,
                    hingeX + dp(5), rect.top + rect.height() * 0.76f, dp(2), dp(2), paint);
        }

        paint.setColor(withAlpha(Color.WHITE, 0.08f * open));
        path.reset();
        path.moveTo(rect.left + dp(5), rect.top + dp(3));
        path.lineTo(rect.right - dp(7), rect.top + dp(3));
        path.lineTo(rect.right - dp(29), rect.bottom - dp(5));
        path.lineTo(rect.left + dp(18), rect.bottom - dp(5));
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawJCardCover(Canvas canvas, RectF rect, CatalogModels.Album album, float open) {
        paint.setColor(0x48000000);
        canvas.drawRoundRect(rect.left + dp(2), rect.top + dp(4), rect.right + dp(4),
                rect.bottom + dp(4), dp(3), dp(3), paint);
        drawCoverArt(canvas, rect, album);
        paint.setColor(0x26ffffff);
        canvas.drawRect(rect.left, rect.top, rect.left + dp(4), rect.bottom, paint);
        paint.setColor(withAlpha(album.accent, 0.95f));
        canvas.drawRect(rect.left + dp(6), rect.bottom - dp(13), rect.right - dp(6),
                rect.bottom - dp(9), paint);
        drawText(canvas, "UNFOLD", rect.right - dp(8), rect.bottom - dp(17), dp(5.8f),
                withAlpha(Color.WHITE, 0.8f * open), labelFace, Paint.Align.RIGHT, 1.2f);
    }

    private void drawTrackSheet(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        float eased = easeOut(trackSheetProgress);
        float fullHeight = Math.min(height * 0.62f, dp(430));
        trackSheetTop = height - fullHeight * eased;

        paint.setColor(withAlpha(Color.BLACK, 0.46f * eased));
        canvas.drawRect(0, 0, width, trackSheetTop, paint);
        RectF sheet = new RectF(0, trackSheetTop, width, height + dp(20));
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, trackSheetTop, 0, height,
                new int[]{0xff1c1d1a, 0xff101110}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(sheet, dp(18), dp(18), paint);
        paint.setShader(null);

        paint.setColor(0x557e796e);
        canvas.drawRoundRect(width / 2f - dp(18), trackSheetTop + dp(9),
                width / 2f + dp(18), trackSheetTop + dp(12), dp(2), dp(2), paint);
        drawText(canvas, "CHOOSE A TRACK", dp(20), trackSheetTop + dp(38), dp(8.5f), INK,
                labelFace, Paint.Align.LEFT, 1.4f);
        drawText(canvas, "SIDE A  ·  " + selectedAlbum.tracks.size() + " TRACKS",
                width - dp(49), trackSheetTop + dp(38), dp(6.8f), MUTED_INK,
                labelFace, Paint.Align.RIGHT, 1.1f);
        RectF close = new RectF(width - dp(43), trackSheetTop + dp(21), width - dp(13),
                trackSheetTop + dp(51));
        drawText(canvas, "×", close.centerX(), close.centerY() + dp(5), dp(18), MUTED_INK,
                displayFace, Paint.Align.CENTER, 0);
        addHit(ACTION_SHEET_CLOSE, close, -1);

        float listTop = trackSheetTop + dp(57);
        float rowHeight = dp(53);
        float visibleHeight = height - listTop - dp(14);
        maxTrackScroll = Math.max(0, selectedAlbum.tracks.size() * rowHeight - visibleHeight);
        trackScroll = clamp(trackScroll, 0, maxTrackScroll);
        canvas.save();
        canvas.clipRect(0, listTop, width, height);
        for (int i = 0; i < selectedAlbum.tracks.size(); i++) {
            CatalogModels.Track track = selectedAlbum.tracks.get(i);
            float top = listTop + i * rowHeight - trackScroll;
            if (top > height || top + rowHeight < listTop) {
                continue;
            }
            boolean selected = i == selectedTrackIndex;
            if (selected) {
                paint.setColor(withAlpha(selectedAlbum.accent, 0.12f));
                canvas.drawRoundRect(dp(12), top + dp(3), width - dp(12),
                        top + rowHeight - dp(3), dp(8), dp(8), paint);
            }
            drawText(canvas, String.format(Locale.US, "%02d", i + 1), dp(21), top + dp(31),
                    dp(7.4f), selected ? selectedAlbum.accent : 0xff706b61,
                    condensedFace, Paint.Align.LEFT, 1f);
            drawEllipsizedText(canvas, track.title, dp(51), top + dp(27), width - dp(139),
                    dp(12), selected ? INK : 0xffc6bfaf, labelFace, Paint.Align.LEFT);
            drawText(canvas, formatTime(track.durationMs), width - dp(52), top + dp(27), dp(7.5f),
                    MUTED_INK, labelFace, Paint.Align.RIGHT, 0.7f);
            drawText(canvas, "PLAY  ›", width - dp(17), top + dp(29), dp(6.6f),
                    selectedAlbum.accent, labelFace, Paint.Align.RIGHT, 1.1f);
            paint.setColor(0x17ffffff);
            canvas.drawRect(dp(51), top + rowHeight - dp(1), width - dp(17),
                    top + rowHeight - dp(0.5f), paint);
            addHit(ACTION_TRACK, new RectF(dp(8), top, width - dp(8), top + rowHeight), i);
        }
        canvas.restore();
    }

    private void drawJCardDetail(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        float eased = easeInOut(detailProgress);
        paint.setColor(withAlpha(0xff050606, 0.88f * eased));
        canvas.drawRect(0, 0, width, height, paint);

        float margin = dp(10);
        float startScale = 0.43f;
        float scaleX = lerp(startScale, 1f, eased);
        float paperTop = dp(9);
        float paperBottom = height - dp(9);
        RectF paper = new RectF(margin, paperTop, width - margin, paperBottom);
        canvas.save();
        canvas.scale(scaleX, 1f, paper.left, paper.centerY());
        paint.setColor(0x78000000);
        canvas.drawRoundRect(paper.left + dp(5), paper.top + dp(7), paper.right + dp(5),
                paper.bottom + dp(8), dp(4), dp(4), paint);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(paper.left, paper.top, paper.right, paper.bottom,
                new int[]{selectedAlbum.paper, lighten(selectedAlbum.paper, 0.06f),
                        darken(selectedAlbum.paper, 0.035f)}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(paper, dp(3), dp(3), paint);
        paint.setShader(null);
        canvas.restore();

        if (eased < 0.28f) {
            return;
        }

        float contentAlpha = clamp((eased - 0.28f) / 0.72f, 0f, 1f);
        int ink = withAlpha(selectedAlbum.ink, contentAlpha);
        int muted = withAlpha(darken(selectedAlbum.paper, 0.52f), contentAlpha);

        paint.setColor(withAlpha(selectedAlbum.accent, contentAlpha));
        canvas.drawRect(margin, paperTop, margin + dp(6), paperBottom, paint);
        for (int i = 1; i <= 2; i++) {
            float foldX = margin + (paper.width() / 3f) * i;
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(foldX - dp(3), 0, foldX + dp(3), 0,
                    new int[]{0x00000000, withAlpha(Color.BLACK, 0.1f * contentAlpha), 0x00ffffff},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(foldX - dp(4), paperTop, foldX + dp(4), paperBottom, paint);
            paint.setShader(null);
        }

        RectF close = new RectF(width - dp(48), dp(16), width - dp(17), dp(47));
        paint.setColor(withAlpha(darken(selectedAlbum.paper, 0.08f), contentAlpha));
        canvas.drawOval(close, paint);
        drawText(canvas, "×", close.centerX(), close.centerY() + dp(6), dp(19), ink,
                displayFace, Paint.Align.CENTER, 0);
        addHit(ACTION_DETAIL_CLOSE, close, -1);

        float left = margin + dp(22);
        float right = width - margin - dp(18);
        float headerTop = dp(31);
        drawText(canvas, "ORIGINAL J-CARD  /  SIDE A", left, headerTop, dp(6.8f), muted,
                labelFace, Paint.Align.LEFT, 1.35f);
        drawEllipsizedText(canvas, selectedAlbum.title, left, headerTop + dp(28),
                right - left - dp(42), dp(25), ink, displayFace, Paint.Align.LEFT);
        drawText(canvas, selectedAlbum.artist.toUpperCase(Locale.ROOT), left, headerTop + dp(48),
                dp(7.8f), withAlpha(selectedAlbum.accent, contentAlpha), labelFace,
                Paint.Align.LEFT, 1.3f);

        float clipTop = headerTop + dp(65);
        float clipBottom = paperBottom - dp(10);
        canvas.save();
        canvas.clipRect(margin + dp(7), clipTop, width - margin, clipBottom);
        float y = clipTop + dp(10) - detailScroll;

        drawText(canvas, "LINER NOTES", left, y, dp(7), muted, labelFace, Paint.Align.LEFT, 1.5f);
        y += dp(18);
        y = drawParagraph(canvas, selectedAlbum.description, left, y, right - left,
                dp(10.2f), ink, serifFace, dp(16.5f), 20);
        y += dp(20);
        paint.setColor(withAlpha(darken(selectedAlbum.paper, 0.22f), 0.55f * contentAlpha));
        canvas.drawRect(left, y, right, y + dp(0.7f), paint);
        y += dp(24);
        drawText(canvas, "TRACK LIST  /  SIDE A", left, y, dp(7), muted, labelFace,
                Paint.Align.LEFT, 1.5f);
        y += dp(14);

        for (int i = 0; i < selectedAlbum.tracks.size(); i++) {
            CatalogModels.Track track = selectedAlbum.tracks.get(i);
            boolean selected = i == selectedTrackIndex;
            float rowTop = y;
            if (selected) {
                paint.setColor(withAlpha(selectedAlbum.accent, 0.13f * contentAlpha));
                canvas.drawRoundRect(left - dp(6), y - dp(4), right + dp(1), y + dp(25),
                        dp(5), dp(5), paint);
            }
            drawText(canvas, String.format(Locale.US, "%02d", i + 1), left, y + dp(13),
                    dp(7.2f), selected ? withAlpha(selectedAlbum.accent, contentAlpha) : muted,
                    condensedFace, Paint.Align.LEFT, 0.8f);
            drawEllipsizedText(canvas, track.title, left + dp(30), y + dp(13),
                    right - left - dp(92), dp(10.5f), selected ? ink : withAlpha(selectedAlbum.ink, 0.75f * contentAlpha),
                    labelFace, Paint.Align.LEFT);
            drawText(canvas, formatTime(track.durationMs), right, y + dp(13), dp(7.2f), muted,
                    labelFace, Paint.Align.RIGHT, 0.5f);
            float hitTop = Math.max(rowTop - dp(4), clipTop);
            float hitBottom = Math.min(rowTop + dp(27), clipBottom);
            if (hitBottom > hitTop) {
                addHit(ACTION_DETAIL_TRACK, new RectF(left - dp(7), hitTop,
                        right, hitBottom), i);
            }
            y += dp(31);
        }

        y += dp(13);
        paint.setColor(withAlpha(darken(selectedAlbum.paper, 0.22f), 0.55f * contentAlpha));
        canvas.drawRect(left, y, right, y + dp(0.7f), paint);
        y += dp(25);
        CatalogModels.Track selectedTrack = getSelectedTrack();
        drawText(canvas, "LYRICS  /  " + (selectedTrack == null ? "" : selectedTrack.title.toUpperCase(Locale.ROOT)),
                left, y, dp(7), muted, labelFace, Paint.Align.LEFT, 1.35f);
        y += dp(22);
        float lyricsTop = y - dp(16);
        CatalogModels.LyricsState lyricsState = selectedTrack == null
                ? CatalogModels.LyricsState.IDLE : selectedTrack.lyricsState;
        String lyrics;
        int lyricColor = ink;
        int maximumLines = 180;
        switch (lyricsState) {
            case LOADING:
                lyrics = "正在翻查歌词档案……\n\n首次匹配完成后会保存在这台设备上。";
                lyricColor = muted;
                maximumLines = 8;
                break;
            case NOT_FOUND:
                lyrics = "这次没有找到足够可信的歌词。\n\n轻触这里重新检索";
                lyricColor = muted;
                maximumLines = 8;
                break;
            case ERROR:
                String errorDetail = selectedTrack == null ? "" : selectedTrack.lyricsMessage;
                lyrics = (errorDetail.isEmpty() ? "歌词服务暂时没有响应。" : errorDetail)
                        + (selectedTrack != null && selectedTrack.lyricsOpenNetworkSettings
                        ? "\n\n轻触打开 WalkTape 网络设置" : "\n\n轻触这里重试");
                lyricColor = muted;
                maximumLines = 8;
                break;
            case READY:
                lyrics = selectedTrack == null ? "" : selectedTrack.lyrics;
                break;
            case IDLE:
            default:
                lyrics = "正在准备歌词匹配……";
                lyricColor = muted;
                maximumLines = 4;
                break;
        }
        y = drawParagraph(canvas, lyrics, left, y, right - left, dp(13), lyricColor,
                serifFace, dp(23), maximumLines);
        if (selectedTrack != null && (lyricsState == CatalogModels.LyricsState.NOT_FOUND
                || lyricsState == CatalogModels.LyricsState.ERROR
                || lyricsState == CatalogModels.LyricsState.IDLE)) {
            float hitBottom = Math.min(clipBottom, Math.max(y + dp(8), lyricsTop + dp(74)));
            if (hitBottom > Math.max(clipTop, lyricsTop)) {
                addHit(ACTION_LYRICS_RETRY,
                        new RectF(left - dp(7), Math.max(clipTop, lyricsTop), right, hitBottom),
                        -1);
            }
        }
        if (selectedTrack != null && lyricsState == CatalogModels.LyricsState.READY
                && !selectedTrack.lyricsSource.isEmpty()) {
            y += dp(12);
            String sourceLabel = "LYRICS VIA " + selectedTrack.lyricsSource;
            drawText(canvas, sourceLabel, left, y, dp(6.5f),
                    withAlpha(selectedAlbum.accent, contentAlpha), labelFace,
                    Paint.Align.LEFT, 1.1f);
            if (!selectedTrack.lyricsSourceUrl.isEmpty()) {
                float sourceTop = y - dp(15);
                float sourceBottom = y + dp(9);
                if (sourceBottom > clipTop && sourceTop < clipBottom) {
                    addHit(ACTION_LYRICS_SOURCE, new RectF(left - dp(6),
                            Math.max(clipTop, sourceTop),
                            Math.min(right, left + dp(155)),
                            Math.min(clipBottom, sourceBottom)), -1);
                }
            }
        }
        y += dp(34);
        drawText(canvas, "WALKTAPE ARCHIVE  ·  " + selectedAlbum.year, left, y, dp(6.5f), muted,
                labelFace, Paint.Align.LEFT, 1.3f);
        maxDetailScroll = Math.max(0, y + detailScroll - clipBottom + dp(18));
        detailScroll = clamp(detailScroll, 0, maxDetailScroll);
        canvas.restore();
    }

    private void drawPlayerScene(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        if (width < height * 1.18f) {
            drawPlayerBackdrop(canvas, width, height);
            drawRotationInterlude(canvas, width, height);
            return;
        }

        float margin = dp(14);
        float infoWidth = clamp(width * 0.30f, dp(205), dp(330));
        RectF restingInfo = new RectF(width - infoWidth - margin, margin,
                width - margin, height - margin);
        float panelReveal = easeInOut(playerInfoReveal);
        float panelTravel = infoWidth + margin + dp(18);
        RectF info = new RectF(restingInfo);
        info.offset(panelTravel * (1f - panelReveal), 0f);
        playerInfoBounds.set(info);
        float normalDeviceWidth = restingInfo.left - margin * 2;
        float expandedDeviceWidth = width - margin * 2;
        float availableDeviceWidth = lerp(expandedDeviceWidth, normalDeviceWidth, panelReveal);
        float machineAspect = profile.isSonyWmD6c() ? 1.90f : 1.56f;
        float deviceHeight = Math.min(height - margin * 2,
                availableDeviceWidth / machineAspect);
        float deviceWidth = deviceHeight * machineAspect;
        RectF device = new RectF(
                margin + Math.max(0, (availableDeviceWidth - deviceWidth) / 2f),
                (height - deviceHeight) / 2f,
                margin + Math.max(0, (availableDeviceWidth - deviceWidth) / 2f) + deviceWidth,
                (height - deviceHeight) / 2f + deviceHeight);

        float reveal = easeOut(sceneReveal);
        boolean infoSettled = playerInfoReveal <= 0.001f || playerInfoReveal >= 0.999f;
        boolean cacheable = reveal >= 0.999f && infoSettled
                && pressedAction == null && buttonPress <= 0.001f;
        if (cacheable && drawCachedPlayerMachine(canvas, width, height, device)) {
            // The cached frame contains the shell, artwork and controls. Only the moving tape
            // packs/spokes and their glass reflection need to be painted at transport cadence.
            drawPlayerReels(canvas, device);
        } else {
            drawPlayerBackdrop(canvas, width, height);
            canvas.save();
            canvas.scale(lerp(0.94f, 1f, reveal), lerp(0.94f, 1f, reveal),
                    device.centerX(), device.centerY());
            drawSelectedMachine(canvas, device);
            canvas.restore();
        }
        drawLiveMachineOverlays(canvas, device);
        // The broad body action is registered first; physical transport/selector controls added
        // afterwards take priority because hit testing walks the list in reverse.
        addHit(ACTION_PLAYER_INFO_TOGGLE, device, -1);
        addPlayerMachineHits(device);
        if (panelReveal > 0.001f) {
            drawPlayerInfo(canvas, info, reveal * panelReveal);
        }
        drawPlayerViewLock(canvas, device);
    }

    private void drawPlayerBackdrop(Canvas canvas, int width, int height) {
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{0xff101111, 0xff070808, 0xff0b0b0a}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        drawAmbientGlow(canvas, width * 0.3f, height * 0.34f, width * 0.58f,
                profile.isSonyWmD6c() ? 0x1f9e4933
                        : profile.isAiwaHsJx707() ? 0x1d9d8060
                        : profile.isSonyWmF2015() ? 0x1b8b9295 : 0x1d6593a1);
        drawFineGrain(canvas, 0xff4e504b, 0.11f);
    }

    private boolean drawCachedPlayerMachine(Canvas canvas,
                                            int width,
                                            int height,
                                            RectF device) {
        long signature = playerStaticSignature();
        boolean wrongSize = playerStaticFrame == null
                || playerStaticFrame.isRecycled()
                || playerStaticWidth != width
                || playerStaticHeight != height;
        if (wrongSize || playerStaticSignature != signature) {
            recyclePlayerStaticFrame();
            if (playerCacheFailedWidth == width && playerCacheFailedHeight == height) {
                return false;
            }
            try {
                // RGB_565 is enough for an opaque vintage machine/background and halves the
                // cache footprint (about 5 MiB on the Pixel 8 in landscape).
                playerStaticFrame = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                Canvas cacheCanvas = new Canvas(playerStaticFrame);
                drawPlayerBackdrop(cacheCanvas, width, height);
                drawSelectedMachine(cacheCanvas, device);
                playerStaticWidth = width;
                playerStaticHeight = height;
                playerStaticSignature = signature;
                playerStaticBuildCount++;
                playerCacheFailedWidth = 0;
                playerCacheFailedHeight = 0;
            } catch (OutOfMemoryError ignored) {
                recyclePlayerStaticFrame();
                playerCacheFailedWidth = width;
                playerCacheFailedHeight = height;
                return false;
            }
        }
        paint.setAlpha(255);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawBitmap(playerStaticFrame, 0, 0, paint);
        return true;
    }

    private long playerStaticSignature() {
        CatalogModels.Track track = getSelectedTrack();
        long signature = 17L;
        signature = signature * 31L + (selectedAlbum == null ? 0L : selectedAlbum.id);
        signature = signature * 31L + (track == null ? 0L : track.id);
        signature = signature * 31L + (selectedAlbum == null || selectedAlbum.title == null
                ? 0 : selectedAlbum.title.hashCode());
        signature = signature * 31L + (track == null || track.title == null
                ? 0 : track.title.hashCode());
        signature = signature * 31L + (selectedAlbum == null || selectedAlbum.artwork == null
                ? 0 : System.identityHashCode(selectedAlbum.artwork));
        signature = signature * 31L + (playing ? 1 : 0);
        signature = signature * 31L + (highTape ? 1 : 0);
        signature = signature * 31L + dolbyMode.ordinal();
        signature = signature * 31L + (hotline ? 1 : 0);
        signature = signature * 31L + profile.id.hashCode();
        signature = signature * 31L + tapeProfile.id.hashCode();
        signature = signature * 31L + conditionProfile.id.hashCode();
        signature = signature * 31L + (playerInfoTarget < 0.5f ? 1 : 0);
        return signature;
    }

    private void recyclePlayerStaticFrame() {
        if (playerStaticFrame != null && !playerStaticFrame.isRecycled()) {
            playerStaticFrame.recycle();
        }
        playerStaticFrame = null;
        playerStaticWidth = 0;
        playerStaticHeight = 0;
        playerStaticSignature = Long.MIN_VALUE;
    }

    int playerStaticBuildCountForTest() {
        return playerStaticBuildCount;
    }

    static long playerFrameIntervalMsForTest() {
        return PLAYER_FRAME_INTERVAL_MS;
    }

    float infoFlipProgressForTest() {
        return infoFlipProgress;
    }

    boolean playerInfoHiddenForTest() {
        return playerInfoTarget < 0.5f;
    }

    boolean playerViewLockedForTest() {
        return playerViewLocked;
    }

    float audioMeterDisplayForTest() {
        return audioMeterDisplay;
    }

    static int d6cMeterSegmentCountForTest(float linearPeak) {
        if (!(linearPeak > 0f) || !Float.isFinite(linearPeak)) {
            return 0;
        }
        // Full-scale PCM represents +6 dB on the five-step D6C scale; consequently its
        // documented 0 dB reference sits at -6 dBFS with symmetric digital headroom.
        float analogueDb = 20f * (float) Math.log10(Math.min(1f, linearPeak)) + 6f;
        float[] thresholds = {-10f, -5f, 0f, 3f, 6f};
        int count = 0;
        for (float threshold : thresholds) {
            if (analogueDb + 0.0001f >= threshold) {
                count++;
            }
        }
        return count;
    }

    private void drawPlayerReels(Canvas canvas, RectF body) {
        if (profile.isSonyWmD6c()) {
            RectF window = sonyWmD6cWindow(body);
            RectF visibleTape = inset(window, dp(5));
            drawDynamicCassetteReels(canvas, visibleTape);
            drawPlayerWindowReflection(canvas, window);
            return;
        }
        float faceLeft = body.left + body.width() * 0.075f;
        float faceTop = body.top + body.height() * 0.075f;
        float faceRight = body.right - body.width() * 0.108f;
        float faceBottom = body.bottom - body.height() * 0.12f;
        RectF face = new RectF(faceLeft, faceTop, faceRight, faceBottom);
        float railWidth = face.width() * 0.16f;
        RectF door = new RectF(face.left + railWidth + dp(8), face.top + dp(8),
                face.right - dp(9), face.bottom - dp(9));
        float windowMarginX = door.width() * 0.105f;
        float windowMarginY = door.height() * 0.10f;
        RectF window = new RectF(door.left + windowMarginX, door.top + windowMarginY,
                door.right - windowMarginX, door.bottom - windowMarginY);
        RectF visibleTape = inset(window, dp(5));

        float shellInset = visibleTape.width() * 0.067f;
        RectF label = new RectF(visibleTape.left + shellInset,
                visibleTape.top + visibleTape.height() * 0.075f,
                visibleTape.right - shellInset,
                visibleTape.bottom - visibleTape.height() * 0.14f);
        float reelY = label.top + label.height() * 0.43f;
        float reelRadius = Math.min(label.height() * 0.22f, label.width() * 0.115f);
        float leftReelX = label.left + label.width() * 0.28f;
        float rightReelX = label.right - label.width() * 0.28f;
        float played = durationMs <= 0 ? 0.42f
                : clamp(positionMs / (float) durationMs, 0f, 1f);
        drawReel(canvas, leftReelX, reelY, reelRadius,
                reelAngle, lerp(0.86f, 0.48f, played), true);
        drawReel(canvas, rightReelX, reelY, reelRadius,
                -reelAngle * 1.04f, lerp(0.48f, 0.86f, played), true);
        // Reapply the subtle window sheen after the dynamic reel layer. It is intentionally very
        // faint; avoiding a mutable-Path clip here keeps recording/hardware canvases consistent.
        drawPlayerWindowReflection(canvas, window);
    }

    private void drawDynamicCassetteReels(Canvas canvas, RectF visibleTape) {
        float shellInset = visibleTape.width() * 0.067f;
        RectF label = new RectF(visibleTape.left + shellInset,
                visibleTape.top + visibleTape.height() * 0.075f,
                visibleTape.right - shellInset,
                visibleTape.bottom - visibleTape.height() * 0.14f);
        float reelY = label.top + label.height() * 0.43f;
        float reelRadius = Math.min(label.height() * 0.22f, label.width() * 0.115f);
        float leftReelX = label.left + label.width() * 0.28f;
        float rightReelX = label.right - label.width() * 0.28f;
        float played = durationMs <= 0 ? 0.42f
                : clamp(positionMs / (float) durationMs, 0f, 1f);
        drawReel(canvas, leftReelX, reelY, reelRadius,
                reelAngle, lerp(0.86f, 0.48f, played), true);
        drawReel(canvas, rightReelX, reelY, reelRadius,
                -reelAngle * 1.04f, lerp(0.48f, 0.86f, played), true);
    }

    private void drawLiveMachineOverlays(Canvas canvas, RectF body) {
        if (profile.isSonyWmD6c()) {
            drawSonyWmD6cMeterLights(canvas, body);
        }
    }

    private void drawPlayerViewLock(Canvas canvas, RectF body) {
        float lockWidth = dp(playerViewLocked ? 62f : 52f);
        float lockHeight = dp(23f);
        RectF lock = new RectF(body.right - lockWidth - dp(9), body.top + dp(8),
                body.right - dp(9), body.top + dp(8) + lockHeight);
        boolean pressed = ACTION_PLAYER_VIEW_LOCK.equals(pressedAction);
        paint.setColor(playerViewLocked ? (pressed ? 0xdde95c2c : 0xb8c84e29)
                : (pressed ? 0x9a242523 : 0x66171918));
        canvas.drawRoundRect(lock, lock.height() * 0.5f, lock.height() * 0.5f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.75f));
        paint.setColor(playerViewLocked ? 0xfff0b29b : 0x769e998e);
        canvas.drawRoundRect(lock, lock.height() * 0.5f, lock.height() * 0.5f, paint);

        float iconX = lock.left + dp(10.5f);
        float iconY = lock.centerY() + dp(1f);
        paint.setStrokeWidth(dp(1.15f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawArc(iconX - dp(3.2f), iconY - dp(6.2f), iconX + dp(3.2f),
                iconY + dp(0.8f), 196f, 148f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(iconX - dp(4.2f), iconY - dp(0.8f),
                iconX + dp(4.2f), iconY + dp(5.2f), dp(1.2f), dp(1.2f), paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        drawText(canvas, playerViewLocked ? "LOCKED" : "LOCK", lock.left + dp(20),
                lock.centerY() + dp(2.1f), dp(4.4f),
                playerViewLocked ? 0xffffe2d7 : 0xffb4afa4,
                labelFace, Paint.Align.LEFT, 0.75f);
        addHit(ACTION_PLAYER_VIEW_LOCK, expand(lock, dp(3)), -1);
    }

    private void drawPlayerWindowReflection(Canvas canvas, RectF window) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x28d8edf1);
        path.reset();
        path.moveTo(window.left + dp(5), window.top + dp(3));
        path.lineTo(window.right - dp(19), window.top + dp(3));
        path.lineTo(window.right - dp(44), window.bottom - dp(3));
        path.lineTo(window.left + dp(21), window.bottom - dp(3));
        path.close();
        canvas.drawPath(path, paint);
    }

    private void addPlayerMachineHits(RectF body) {
        float hotSize = Math.max(dp(25), body.height() * 0.105f);
        RectF hotButton = new RectF(body.left + dp(7), body.top + dp(12),
                body.left + dp(7) + hotSize, body.top + dp(12) + hotSize);
        if (profile.hotlineSupported) {
            addHit(ACTION_HOTLINE, expand(hotButton, dp(5)), -1);
        }

        float controlTop = body.bottom - body.height() * 0.116f;
        float left = body.left + body.width() * 0.24f;
        float totalWidth = body.width() * 0.48f;
        float gap = dp(3);
        float buttonWidth = (totalWidth - gap * 3) / 4f;
        float buttonHeight = Math.max(dp(24), body.height() * 0.108f);
        String[] actions = {ACTION_STOP, ACTION_REWIND, ACTION_PLAY, ACTION_FORWARD};
        for (int i = 0; i < actions.length; i++) {
            float x = left + i * (buttonWidth + gap);
            RectF button = new RectF(x, controlTop, x + buttonWidth, controlTop + buttonHeight);
            addHit(actions[i], expand(button, dp(3)), -1);
        }

        float toneX = body.right - body.width() * 0.083f;
        float toneY = body.top + body.height() * 0.30f;
        RectF switchTrack = new RectF(toneX - dp(6), toneY, toneX + dp(6), toneY + dp(33));
        addHit(ACTION_TONE, expand(switchTrack, dp(8)), -1);

        if (profile.isSonyWmD6c()) {
            RectF dolbyTrack = sonyWmD6cDolbyTrack(body);
            float third = dolbyTrack.height() / 3f;
            float hitLeft = dolbyTrack.left - dp(6);
            float hitRight = dolbyTrack.right + dp(13);
            addHit(ACTION_DOLBY, new RectF(hitLeft,
                    dolbyTrack.top, hitRight, dolbyTrack.top + third), 2);
            addHit(ACTION_DOLBY, new RectF(hitLeft,
                    dolbyTrack.top + third, hitRight, dolbyTrack.top + third * 2f), 1);
            addHit(ACTION_DOLBY, new RectF(hitLeft,
                    dolbyTrack.top + third * 2f, hitRight, dolbyTrack.bottom), 0);
        } else if (profile.isAiwaHsJx707()) {
            addHit(ACTION_DOLBY, expand(aiwaDolbyBadge(body), dp(5)), -1);
        }
    }

    private void drawRotationInterlude(Canvas canvas, int width, int height) {
        float reveal = easeInOut(sceneReveal);
        float cassetteWidth = Math.min(width - dp(50), dp(260));
        float cassetteHeight = cassetteWidth * 0.635f;
        RectF cassette = new RectF(width / 2f - cassetteWidth / 2f,
                height / 2f - cassetteHeight / 2f - dp(25),
                width / 2f + cassetteWidth / 2f,
                height / 2f + cassetteHeight / 2f - dp(25));
        canvas.save();
        canvas.rotate(90f * reveal, cassette.centerX(), cassette.centerY());
        drawCassette(canvas, cassette, selectedAlbum, reelAngle, true);
        canvas.restore();
        drawText(canvas, "ROTATING THE MACHINE", width / 2f, height - dp(64), dp(8),
                MUTED_INK, labelFace, Paint.Align.CENTER, 1.6f);
        drawText(canvas, profile.model + "  /  PLAYBACK MODE", width / 2f,
                height - dp(42), dp(12),
                INK, displayFace, Paint.Align.CENTER, 0.8f);
    }

    private void drawSelectedMachine(Canvas canvas, RectF body) {
        if (profile.isSonyWmD6c()) {
            drawSonyWmD6c(canvas, body);
        } else if (profile.isAiwaHsJx707()) {
            drawAiwaHsJx707(canvas, body);
        } else if (profile.isSonyWmF2015()) {
            drawSonyWmF2015(canvas, body);
        } else {
            drawTpsL2(canvas, body);
        }
    }

    private void drawSonyWmD6c(Canvas canvas, RectF body) {
        float radius = body.height() * 0.018f;
        paint.setColor(0xb8000000);
        canvas.drawRoundRect(body.left + dp(7), body.top + dp(9), body.right + dp(10),
                body.bottom + dp(11), radius, radius, paint);

        paint.setAlpha(255);
        paint.setShader(new LinearGradient(body.left, body.top, body.right, body.bottom,
                new int[]{0xff484a48, 0xff222423, 0xff111313, 0xff343634},
                new float[]{0f, 0.24f, 0.70f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(body, radius, radius, paint);
        paint.setShader(null);
        drawBrushedMetal(canvas, body);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.05f));
        paint.setColor(0xff080909);
        canvas.drawRoundRect(inset(body, dp(2)), radius, radius, paint);
        paint.setStrokeWidth(dp(0.55f));
        paint.setColor(0x708e918d);
        canvas.drawRoundRect(body.left + dp(4), body.top + dp(4), body.right - dp(4),
                body.bottom - dp(4), radius * 0.76f, radius * 0.76f, paint);
        paint.setStyle(Paint.Style.FILL);

        float seamY = body.top + body.height() * 0.145f;
        paint.setColor(0x69050606);
        canvas.drawRect(body.left + dp(2), seamY, body.right - dp(2),
                seamY + dp(1.1f), paint);
        drawText(canvas, "SONY", body.left + body.width() * 0.048f,
                body.top + body.height() * 0.115f, dp(15f), 0xfff0f0eb,
                labelFace, Paint.Align.LEFT, 0.18f);

        float badgeLeft = body.left + body.width() * 0.30f;
        String[] badges = {"QUARTZ LOCK", "DISC DRIVE", "DOLBY B·C NR"};
        for (int index = 0; index < badges.length; index++) {
            float badgeWidth = index == 2 ? dp(43) : dp(38);
            RectF badge = new RectF(badgeLeft, body.top + dp(9),
                    badgeLeft + badgeWidth, body.top + dp(23));
            paint.setColor(0xff131514);
            canvas.drawRoundRect(badge, dp(1.5f), dp(1.5f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(0.45f));
            paint.setColor(index == 2 ? 0x9ac7a744 : 0x628e918d);
            canvas.drawRoundRect(badge, dp(1.5f), dp(1.5f), paint);
            paint.setStyle(Paint.Style.FILL);
            drawText(canvas, badges[index], badge.centerX(), badge.centerY() + dp(1.7f),
                    dp(3.5f), index == 2 ? 0xffd6b34a : 0xffb8bbb6,
                    condensedFace, Paint.Align.CENTER, 0.32f);
            badgeLeft = badge.right + dp(4);
        }

        RectF window = sonyWmD6cWindow(body);
        paint.setShader(new LinearGradient(window.left, window.top,
                window.right, window.bottom,
                new int[]{0xff171918, 0xff050606, 0xff202220}, null,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(window, dp(3), dp(3), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.0f));
        paint.setColor(0xff070808);
        canvas.drawRoundRect(window, dp(3), dp(3), paint);
        paint.setStrokeWidth(dp(0.65f));
        paint.setColor(0x6b969a94);
        canvas.drawRoundRect(inset(window, dp(2.4f)), dp(2), dp(2), paint);
        paint.setStyle(Paint.Style.FILL);
        RectF visibleTape = inset(window, dp(5));
        drawCassette(canvas, visibleTape, selectedAlbum, reelAngle, true);

        // The red dot field and script are the unmistakable D6C window graphic. The opacity is
        // held back so the listener's own album artwork remains legible beneath the glazing.
        float dotStartX = window.left + window.width() * 0.045f;
        float dotStartY = window.top + window.height() * 0.10f;
        float dotStep = Math.max(dp(3.3f), window.height() * 0.055f);
        paint.setColor(0xcff16647);
        for (int row = 0; row < 6; row++) {
            for (int column = 0; column < 7; column++) {
                if (column + row > 8) {
                    continue;
                }
                canvas.drawCircle(dotStartX + column * dotStep,
                        dotStartY + row * dotStep, dp(1.35f), paint);
            }
        }
        drawText(canvas, "It's a Sony", window.left + window.width() * 0.045f,
                window.bottom - dp(8), dp(5.6f), 0xffef6749,
                displayFace, Paint.Align.LEFT, 0.05f);
        drawPlayerWindowReflection(canvas, window);

        RectF rail = new RectF(body.left + body.width() * 0.785f,
                body.top + body.height() * 0.175f,
                body.right - body.width() * 0.035f,
                body.top + body.height() * 0.775f);
        paint.setColor(0x71101211);
        canvas.drawRoundRect(rail, dp(2), dp(2), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.55f));
        paint.setColor(0x477f827e);
        canvas.drawRoundRect(rail, dp(2), dp(2), paint);
        paint.setStyle(Paint.Style.FILL);

        float dolbyX = rail.left + rail.width() * 0.23f;
        float dolbyTop = rail.top + rail.height() * 0.12f;
        drawText(canvas, "DOLBY NR", dolbyX, dolbyTop - dp(5), dp(3.5f),
                0xffdadbd5, labelFace, Paint.Align.CENTER, 0.35f);
        RectF dolbyTrack = sonyWmD6cDolbyTrack(body);
        paint.setColor(0xff050606);
        canvas.drawRoundRect(dolbyTrack, dp(2), dp(2), paint);
        float knobY = dolbyMode == DolbyMode.C
                ? dolbyTrack.top + dp(4.6f)
                : dolbyMode == DolbyMode.B ? dolbyTrack.centerY()
                : dolbyTrack.bottom - dp(4.7f);
        paint.setColor(ACTION_DOLBY.equals(pressedAction) ? 0xffd5aa3d : 0xff9a9d97);
        canvas.drawRoundRect(dolbyTrack.left + dp(1.2f), knobY - dp(3),
                dolbyTrack.right - dp(1.2f), knobY + dp(3),
                dp(1), dp(1), paint);
        drawText(canvas, "C", dolbyTrack.right + dp(2.5f), dolbyTrack.top + dp(4),
                dp(3.1f), dolbyMode == DolbyMode.C ? 0xfff2c859 : 0xffa9aaa5,
                condensedFace, Paint.Align.LEFT, 0.15f);
        drawText(canvas, "B", dolbyTrack.right + dp(2.5f), dolbyTrack.centerY() + dp(1),
                dp(3.1f), dolbyMode == DolbyMode.B ? 0xfff2c859 : 0xffa9aaa5,
                condensedFace, Paint.Align.LEFT, 0.15f);
        drawText(canvas, "OFF", dolbyTrack.right + dp(2.5f), dolbyTrack.bottom,
                dp(2.8f), dolbyMode == DolbyMode.OFF ? 0xfff0f0ea : 0xffa9aaa5,
                condensedFace, Paint.Align.LEFT, 0.15f);

        drawSonyWmD6cMeterHousing(canvas, body);

        float logoY = body.top + body.height() * 0.835f;
        drawText(canvas, "WALKMAN", body.left + body.width() * 0.045f, logoY,
                dp(15.5f), 0xfff0f0ea, condensedFace, Paint.Align.LEFT, -0.15f);
        drawText(canvas, "PROFESSIONAL", body.left + body.width() * 0.205f, logoY - dp(1),
                dp(6.1f), 0xffeeeee8, labelFace, Paint.Align.LEFT, 0.22f);
        RectF dolbyBadge = new RectF(body.left + body.width() * 0.205f,
                logoY + dp(3), body.left + body.width() * 0.315f, logoY + dp(14));
        paint.setColor(0xffc9a329);
        canvas.drawRoundRect(dolbyBadge, dp(1), dp(1), paint);
        drawText(canvas, "DOLBY B·C NR", dolbyBadge.centerX(),
                dolbyBadge.centerY() + dp(1.7f), dp(3.8f), 0xff151613,
                condensedFace, Paint.Align.CENTER, 0.28f);
        drawText(canvas, "STEREO CASSETTE-CORDER   WM-D6C", body.right - dp(10),
                logoY + dp(7), dp(4.3f), 0xffd7d7d1,
                labelFace, Paint.Align.RIGHT, 0.48f);

        drawMechanicalControls(canvas, body);
    }

    private RectF sonyWmD6cWindow(RectF body) {
        return new RectF(body.left + body.width() * 0.205f,
                body.top + body.height() * 0.205f,
                body.left + body.width() * 0.765f,
                body.top + body.height() * 0.690f);
    }

    private RectF sonyWmD6cDolbyTrack(RectF body) {
        float railLeft = body.left + body.width() * 0.785f;
        float railRight = body.right - body.width() * 0.035f;
        float railTop = body.top + body.height() * 0.175f;
        float railBottom = body.top + body.height() * 0.775f;
        float dolbyX = railLeft + (railRight - railLeft) * 0.23f;
        float dolbyTop = railTop + (railBottom - railTop) * 0.12f;
        return new RectF(dolbyX - dp(4.2f), dolbyTop,
                dolbyX + dp(4.2f), dolbyTop + dp(29));
    }

    private void drawSonyWmD6cMeterHousing(Canvas canvas, RectF body) {
        float left = body.left + body.width() * 0.824f;
        float right = body.left + body.width() * 0.852f;
        float top = body.top + body.height() * 0.485f;
        float segmentHeight = body.height() * 0.036f;
        float gap = body.height() * 0.013f;
        String[] labels = {"+6", "+3", "0", "−5", "−10"};
        for (int index = 0; index < labels.length; index++) {
            float segmentTop = top + index * (segmentHeight + gap);
            RectF segment = new RectF(left, segmentTop, right,
                    segmentTop + segmentHeight);
            paint.setColor(0xff090808);
            canvas.drawRoundRect(segment, dp(1.2f), dp(1.2f), paint);
            paint.setColor(index < 2 ? 0xff2d0b09 : 0xff240b0a);
            canvas.drawRoundRect(inset(segment, dp(1.1f)), dp(0.7f), dp(0.7f), paint);
            drawText(canvas, labels[index], segment.left - dp(3),
                    segment.centerY() + dp(1.6f), dp(3.5f),
                    index < 2 ? 0xffee6a4c : 0xffc6c7c1,
                    condensedFace, Paint.Align.RIGHT, 0.15f);
        }
        float bottom = top + 5 * segmentHeight + 4 * gap;
        drawText(canvas, "PEAK", (left + right) * 0.5f, bottom + dp(8), dp(3.4f),
                0xffd0d0ca, condensedFace, Paint.Align.CENTER, 0.32f);
        drawText(canvas, "L/R MAX", (left + right) * 0.5f, bottom + dp(14), dp(2.8f),
                0xff8d8f8a, condensedFace, Paint.Align.CENTER, 0.18f);
    }

    private void drawSonyWmD6cMeterLights(Canvas canvas, RectF body) {
        int activeSegments = d6cMeterSegmentCountForTest(audioMeterDisplay);
        if (activeSegments <= 0) {
            return;
        }
        float left = body.left + body.width() * 0.824f;
        float right = body.left + body.width() * 0.852f;
        float top = body.top + body.height() * 0.485f;
        float segmentHeight = body.height() * 0.036f;
        float gap = body.height() * 0.013f;
        for (int index = 0; index < 5; index++) {
            int levelFromBottom = 5 - index;
            if (activeSegments < levelFromBottom) {
                continue;
            }
            float segmentTop = top + index * (segmentHeight + gap);
            RectF segment = new RectF(left, segmentTop, right,
                    segmentTop + segmentHeight);
            paint.setColor(index == 0 ? 0x50ff4b2d : 0x3ee95832);
            canvas.drawRoundRect(segment.left - dp(2.1f), segment.top - dp(1.6f),
                    segment.right + dp(2.1f), segment.bottom + dp(1.6f),
                    dp(2.2f), dp(2.2f), paint);
            paint.setShader(new LinearGradient(segment.left, segment.top,
                    segment.right, segment.bottom,
                    new int[]{index < 2 ? 0xffff6d43 : 0xfff95738,
                            index < 2 ? 0xffff3d25 : 0xffd82d22},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(inset(segment, dp(0.8f)), dp(0.8f), dp(0.8f), paint);
            paint.setShader(null);
        }
    }

    private void drawTpsL2(Canvas canvas, RectF body) {
        float radius = body.height() * 0.035f;
        paint.setColor(0x8a000000);
        canvas.drawRoundRect(body.left + dp(5), body.top + dp(8), body.right + dp(8),
                body.bottom + dp(9), radius, radius, paint);

        paint.setAlpha(255);
        paint.setShader(new LinearGradient(body.left, body.top, body.right, body.bottom,
                new int[]{0xff2b6b84, SONY_BLUE, SONY_BLUE_DARK, 0xff225b73},
                new float[]{0f, 0.36f, 0.73f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(body, radius, radius, paint);
        paint.setShader(null);
        drawPlayerBodyTexture(canvas, body);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(0xff091f2a);
        canvas.drawRoundRect(inset(body, dp(2)), radius, radius, paint);
        paint.setColor(0x66a7c8d1);
        canvas.drawRoundRect(body.left + dp(3), body.top + dp(3), body.right - dp(3),
                body.bottom - dp(3), radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        float faceLeft = body.left + body.width() * 0.075f;
        float faceTop = body.top + body.height() * 0.075f;
        float faceRight = body.right - body.width() * 0.108f;
        float faceBottom = body.bottom - body.height() * 0.12f;
        RectF face = new RectF(faceLeft, faceTop, faceRight, faceBottom);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(face.left, face.top, face.right, face.bottom,
                new int[]{0xffe0ded3, 0xffaaa99f, 0xffd0cec3, 0xff8f908a},
                new float[]{0f, 0.33f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, radius * 0.48f, radius * 0.48f, paint);
        paint.setShader(null);
        drawBrushedMetal(canvas, face);

        float railWidth = face.width() * 0.16f;
        RectF brandRail = new RectF(face.left + dp(5), face.top + dp(7),
                face.left + railWidth, face.bottom - dp(7));
        paint.setColor(0xffb9b8ae);
        canvas.drawRoundRect(brandRail, dp(2), dp(2), paint);
        drawVerticalLabel(canvas, "STEREO", brandRail.centerX() - dp(1),
                brandRail.bottom - dp(9), dp(6.4f), 0xff2b2c2b);
        drawVerticalLabel(canvas, "WALKMAN", brandRail.centerX() + dp(8),
                brandRail.bottom - dp(9), dp(8.2f), 0xff202120);

        RectF door = new RectF(face.left + railWidth + dp(8), face.top + dp(8),
                face.right - dp(9), face.bottom - dp(9));
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(door.left, door.top, door.right, door.bottom,
                new int[]{0xff2d6680, 0xff1c4a60, 0xff285c73}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(door, dp(4), dp(4), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xff163545);
        canvas.drawRoundRect(door, dp(4), dp(4), paint);
        paint.setColor(0x5db9d0d7);
        canvas.drawRoundRect(inset(door, dp(2)), dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.FILL);

        float windowMarginX = door.width() * 0.105f;
        float windowMarginY = door.height() * 0.10f;
        RectF window = new RectF(door.left + windowMarginX, door.top + windowMarginY,
                door.right - windowMarginX, door.bottom - windowMarginY);
        paint.setColor(0xff101415);
        canvas.drawRoundRect(window, dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.2f));
        paint.setColor(0xff77786f);
        canvas.drawRoundRect(window, dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.FILL);

        RectF visibleTape = inset(window, dp(5));
        drawCassette(canvas, visibleTape, selectedAlbum, reelAngle, true);
        paint.setColor(0x28d8edf1);
        path.reset();
        path.moveTo(window.left + dp(5), window.top + dp(3));
        path.lineTo(window.right - dp(19), window.top + dp(3));
        path.lineTo(window.right - dp(44), window.bottom - dp(3));
        path.lineTo(window.left + dp(21), window.bottom - dp(3));
        path.close();
        canvas.drawPath(path, paint);

        drawText(canvas, "TPS-L2", face.right - dp(9), face.top + dp(6), dp(5.6f),
                0xff333433, condensedFace, Paint.Align.RIGHT, 0.8f);
        drawText(canvas, "STEREO CASSETTE PLAYER", face.right - dp(9), face.bottom - dp(3),
                dp(4.6f), 0xff373835, labelFace, Paint.Align.RIGHT, 0.7f);

        float hotSize = Math.max(dp(25), body.height() * 0.105f);
        RectF hotButton = new RectF(body.left + dp(7), body.top + dp(12),
                body.left + dp(7) + hotSize, body.top + dp(12) + hotSize);
        boolean hotPressed = hotline || (ACTION_HOTLINE.equals(pressedAction) && buttonPress > 0.2f);
        paint.setColor(hotPressed ? 0xffc6421f : ORANGE);
        canvas.drawRoundRect(hotButton.left, hotButton.top + (hotPressed ? dp(2) : 0),
                hotButton.right, hotButton.bottom + (hotPressed ? dp(2) : 0), dp(3), dp(3), paint);
        drawText(canvas, "HOT", hotButton.centerX(), hotButton.centerY() - dp(1) + (hotPressed ? dp(2) : 0),
                dp(5.2f), Color.WHITE, condensedFace, Paint.Align.CENTER, 0.3f);
        drawText(canvas, "LINE", hotButton.centerX(), hotButton.centerY() + dp(6) + (hotPressed ? dp(2) : 0),
                dp(4.8f), Color.WHITE, condensedFace, Paint.Align.CENTER, 0.4f);

        drawMechanicalControls(canvas, body);
        drawHeadphoneJacks(canvas, body);
    }

    private void drawSonyWmF2015(Canvas canvas, RectF body) {
        float radius = body.height() * 0.030f;
        paint.setColor(0xa9000000);
        canvas.drawRoundRect(body.left + dp(6), body.top + dp(9), body.right + dp(10),
                body.bottom + dp(11), radius, radius, paint);

        paint.setAlpha(255);
        paint.setShader(new LinearGradient(body.left, body.top, body.right, body.bottom,
                new int[]{0xff343738, 0xff111313, 0xff070808, 0xff272a2a},
                new float[]{0f, 0.28f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(body, radius, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0xff020303);
        canvas.drawRoundRect(inset(body, dp(2)), radius, radius, paint);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(0x667f8585);
        canvas.drawRoundRect(body.left + dp(4), body.top + dp(4), body.right - dp(4),
                body.bottom - dp(4), radius * 0.84f, radius * 0.84f, paint);
        paint.setStyle(Paint.Style.FILL);

        float faceLeft = body.left + body.width() * 0.075f;
        float faceTop = body.top + body.height() * 0.075f;
        float faceRight = body.right - body.width() * 0.108f;
        float faceBottom = body.bottom - body.height() * 0.12f;

        RectF tuner = new RectF(faceLeft, body.top + dp(7), faceRight,
                Math.max(body.top + dp(25), faceTop - dp(5)));
        paint.setColor(0xff090b0b);
        canvas.drawRoundRect(tuner, dp(2), dp(2), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.55f));
        paint.setColor(0x708e9493);
        canvas.drawRoundRect(tuner, dp(2), dp(2), paint);
        paint.setStyle(Paint.Style.FILL);
        float scaleLeft = tuner.left + dp(34);
        float scaleRight = tuner.right - dp(35);
        float fmY = tuner.top + tuner.height() * 0.35f;
        float amY = tuner.top + tuner.height() * 0.73f;
        drawText(canvas, "FM", tuner.left + dp(7), fmY + dp(1.5f), dp(4.0f),
                0xffe2e3df, condensedFace, Paint.Align.LEFT, 0.55f);
        drawText(canvas, "AM", tuner.left + dp(7), amY + dp(1.5f), dp(4.0f),
                0xffc4c7c3, condensedFace, Paint.Align.LEFT, 0.55f);
        paint.setColor(0xffd6d8d4);
        canvas.drawRect(scaleLeft, fmY, scaleRight, fmY + dp(0.7f), paint);
        paint.setColor(0xff929795);
        canvas.drawRect(scaleLeft, amY, scaleRight, amY + dp(0.6f), paint);
        for (int tick = 0; tick <= 10; tick++) {
            float x = lerp(scaleLeft, scaleRight, tick / 10f);
            paint.setColor(tick % 2 == 0 ? 0xffe1e2de : 0xff777d7b);
            canvas.drawCircle(x, fmY, tick % 2 == 0 ? dp(1.05f) : dp(0.65f), paint);
            canvas.drawCircle(x, amY, tick % 2 == 0 ? dp(0.85f) : dp(0.55f), paint);
        }
        drawText(canvas, "88  92  96  100  107", (scaleLeft + scaleRight) * 0.5f,
                fmY + dp(2), dp(3.6f), 0xffe7e8e4, condensedFace,
                Paint.Align.CENTER, 0.35f);
        drawText(canvas, "5.4  6  7  8  10  12  16", (scaleLeft + scaleRight) * 0.5f,
                amY + dp(2), dp(3.25f), 0xffbfc2bf, condensedFace,
                Paint.Align.CENTER, 0.28f);
        drawText(canvas, "MHz", tuner.right - dp(6), fmY + dp(1.5f), dp(3.4f),
                0xffd7d9d5, condensedFace, Paint.Align.RIGHT, 0.3f);
        drawText(canvas, "x100 kHz", tuner.right - dp(6), amY + dp(1.5f), dp(3.0f),
                0xffaeb2af, condensedFace, Paint.Align.RIGHT, 0.2f);

        RectF face = new RectF(faceLeft, faceTop, faceRight, faceBottom);
        paint.setShader(new LinearGradient(face.left, face.top, face.right, face.bottom,
                new int[]{0xff2b2e2f, 0xff090b0b, 0xff202323, 0xff080909},
                new float[]{0f, 0.34f, 0.70f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, radius * 0.42f, radius * 0.42f, paint);
        paint.setShader(null);

        float railWidth = face.width() * 0.16f;
        RectF brandRail = new RectF(face.left + dp(5), face.top + dp(7),
                face.left + railWidth, face.bottom - dp(7));
        paint.setShader(new LinearGradient(brandRail.left, brandRail.top,
                brandRail.right, brandRail.bottom,
                new int[]{0xff343738, 0xff141616, 0xff2a2d2d}, null,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(brandRail, dp(2), dp(2), paint);
        paint.setShader(null);
        drawVerticalLabel(canvas, "SONY", brandRail.centerX() - dp(1),
                brandRail.bottom - dp(9), dp(10.0f), 0xffe2e2dc);
        drawVerticalLabel(canvas, "STEREO", brandRail.centerX() + dp(9),
                brandRail.bottom - dp(9), dp(4.2f), 0xff9da19f);

        RectF door = new RectF(face.left + railWidth + dp(8), face.top + dp(8),
                face.right - dp(9), face.bottom - dp(9));
        paint.setShader(new LinearGradient(door.left, door.top, door.right, door.bottom,
                new int[]{0xff35393a, 0xff111414, 0xff272a2a}, null,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(door, dp(3), dp(3), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0xff050606);
        canvas.drawRoundRect(door, dp(3), dp(3), paint);
        paint.setColor(0x628b9190);
        canvas.drawRoundRect(inset(door, dp(2)), dp(2), dp(2), paint);
        paint.setStyle(Paint.Style.FILL);

        float windowMarginX = door.width() * 0.105f;
        float windowMarginY = door.height() * 0.10f;
        RectF window = new RectF(door.left + windowMarginX, door.top + windowMarginY,
                door.right - windowMarginX, door.bottom - windowMarginY);
        paint.setColor(0xff050707);
        canvas.drawRoundRect(window, dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0xff676b69);
        canvas.drawRoundRect(window, dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.FILL);

        RectF visibleTape = inset(window, dp(5));
        drawCassette(canvas, visibleTape, selectedAlbum, reelAngle, true);
        paint.setColor(0x22e0e5e3);
        path.reset();
        path.moveTo(window.left + dp(5), window.top + dp(3));
        path.lineTo(window.right - dp(19), window.top + dp(3));
        path.lineTo(window.right - dp(44), window.bottom - dp(3));
        path.lineTo(window.left + dp(21), window.bottom - dp(3));
        path.close();
        canvas.drawPath(path, paint);

        RectF walkmanBadge = new RectF(window.right + dp(3), window.top + dp(2),
                door.right - dp(3), window.bottom - dp(2));
        paint.setShader(new LinearGradient(walkmanBadge.left, walkmanBadge.top,
                walkmanBadge.right, walkmanBadge.bottom,
                new int[]{0xffd5d5cf, 0xff707473, 0xffc7c8c3}, null,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(walkmanBadge, dp(1.4f), dp(1.4f), paint);
        paint.setShader(null);
        drawVerticalLabel(canvas, "WALKMAN", walkmanBadge.centerX() - dp(0.5f),
                walkmanBadge.bottom - dp(7), dp(7.4f), 0xff222424);

        drawText(canvas, "WM-F2015", face.right - dp(9), face.top + dp(6), dp(5.5f),
                0xffc8cbc8, condensedFace, Paint.Align.RIGHT, 0.75f);
        drawText(canvas, "FM / AM RADIO CASSETTE PLAYER", face.right - dp(9),
                face.bottom - dp(3), dp(4.25f), 0xff929795, labelFace,
                Paint.Align.RIGHT, 0.58f);

        float badgeTop = body.top + dp(12);
        float badgeLeft = body.left + dp(8);
        String[] badges = {"FM / AM", "MANUAL TAPE", "LA4570M"};
        for (String badgeText : badges) {
            float badgeWidth = badgeText.length() > 7 ? dp(39) : dp(29);
            RectF badge = new RectF(badgeLeft, badgeTop,
                    badgeLeft + badgeWidth, badgeTop + dp(14));
            paint.setColor(0xff090a0a);
            canvas.drawRoundRect(badge, dp(1.5f), dp(1.5f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(0.45f));
            paint.setColor(0x6faeb3b1);
            canvas.drawRoundRect(badge, dp(1.5f), dp(1.5f), paint);
            paint.setStyle(Paint.Style.FILL);
            drawText(canvas, badgeText, badge.centerX(), badge.centerY() + dp(1.8f),
                    dp(3.45f), 0xffc8cbc8, condensedFace, Paint.Align.CENTER, 0.28f);
            badgeLeft = badge.right + dp(3);
        }

        drawMechanicalControls(canvas, body);
        float phoneX = body.right - body.width() * 0.052f;
        float phoneY = body.top + body.height() * 0.17f;
        float phoneRadius = Math.max(dp(6.5f), body.height() * 0.027f);
        paint.setColor(0xff020303);
        canvas.drawCircle(phoneX, phoneY, phoneRadius + dp(2), paint);
        paint.setColor(0xff5d6261);
        canvas.drawCircle(phoneX, phoneY, phoneRadius, paint);
        paint.setColor(0xff030404);
        canvas.drawCircle(phoneX, phoneY, phoneRadius * 0.56f, paint);
        drawText(canvas, "PHONES", phoneX - phoneRadius - dp(3), phoneY + dp(2), dp(3.6f),
                0xffc4c8c5, labelFace, Paint.Align.RIGHT, 0.35f);
    }

    private void drawAiwaHsJx707(Canvas canvas, RectF body) {
        float radius = body.height() * 0.027f;
        paint.setColor(0xa6000000);
        canvas.drawRoundRect(body.left + dp(6), body.top + dp(9), body.right + dp(10),
                body.bottom + dp(11), radius, radius, paint);

        paint.setAlpha(255);
        paint.setShader(new LinearGradient(body.left, body.top, body.right, body.bottom,
                new int[]{0xff4a4a46, 0xff191b1b, 0xff0d0f10, 0xff343633},
                new float[]{0f, 0.29f, 0.71f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(body, radius, radius, paint);
        paint.setShader(null);
        drawBrushedMetal(canvas, body);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0xff050606);
        canvas.drawRoundRect(inset(body, dp(2)), radius, radius, paint);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(0x83d6c49a);
        canvas.drawRoundRect(body.left + dp(4), body.top + dp(4), body.right - dp(4),
                body.bottom - dp(4), radius * 0.84f, radius * 0.84f, paint);
        paint.setStyle(Paint.Style.FILL);

        float faceLeft = body.left + body.width() * 0.075f;
        float faceTop = body.top + body.height() * 0.075f;
        float faceRight = body.right - body.width() * 0.108f;
        float faceBottom = body.bottom - body.height() * 0.12f;
        RectF face = new RectF(faceLeft, faceTop, faceRight, faceBottom);
        paint.setShader(new LinearGradient(face.left, face.top, face.right, face.bottom,
                new int[]{0xff383a38, 0xff151716, 0xff2b2d2b, 0xff0d0f0f},
                new float[]{0f, 0.36f, 0.70f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, radius * 0.38f, radius * 0.38f, paint);
        paint.setShader(null);
        drawBrushedMetal(canvas, face);

        float railWidth = face.width() * 0.16f;
        RectF brandRail = new RectF(face.left + dp(5), face.top + dp(7),
                face.left + railWidth, face.bottom - dp(7));
        paint.setShader(new LinearGradient(brandRail.left, brandRail.top,
                brandRail.right, brandRail.bottom,
                new int[]{0xffb5aa8b, 0xff625c4d, 0xffc1b595}, null,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(brandRail, dp(2), dp(2), paint);
        paint.setShader(null);
        drawVerticalLabel(canvas, "AIWA", brandRail.centerX() - dp(1),
                brandRail.bottom - dp(9), dp(10.5f), 0xff171816);
        drawVerticalLabel(canvas, "AMORPHOUS HEAD", brandRail.centerX() + dp(9),
                brandRail.bottom - dp(9), dp(4.4f), 0xff292a27);

        RectF door = new RectF(face.left + railWidth + dp(8), face.top + dp(8),
                face.right - dp(9), face.bottom - dp(9));
        paint.setShader(new LinearGradient(door.left, door.top, door.right, door.bottom,
                new int[]{0xff2f3230, 0xff111313, 0xff252826}, null,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(door, dp(3), dp(3), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0xff090a0a);
        canvas.drawRoundRect(door, dp(3), dp(3), paint);
        paint.setColor(0x58d3c398);
        canvas.drawRoundRect(inset(door, dp(2)), dp(2), dp(2), paint);
        paint.setStyle(Paint.Style.FILL);

        float windowMarginX = door.width() * 0.105f;
        float windowMarginY = door.height() * 0.10f;
        RectF window = new RectF(door.left + windowMarginX, door.top + windowMarginY,
                door.right - windowMarginX, door.bottom - windowMarginY);
        paint.setColor(0xff070909);
        canvas.drawRoundRect(window, dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0xff706b5e);
        canvas.drawRoundRect(window, dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.FILL);

        RectF visibleTape = inset(window, dp(5));
        drawCassette(canvas, visibleTape, selectedAlbum, reelAngle, true);
        paint.setColor(0x1fd8d5c4);
        path.reset();
        path.moveTo(window.left + dp(5), window.top + dp(3));
        path.lineTo(window.right - dp(19), window.top + dp(3));
        path.lineTo(window.right - dp(44), window.bottom - dp(3));
        path.lineTo(window.left + dp(21), window.bottom - dp(3));
        path.close();
        canvas.drawPath(path, paint);

        drawText(canvas, "HS-JX707", face.right - dp(9), face.top + dp(6), dp(5.8f),
                0xffd6c89f, condensedFace, Paint.Align.RIGHT, 0.9f);
        drawText(canvas, "STEREO RADIO CASSETTE PLAYER", face.right - dp(9),
                face.bottom - dp(3), dp(4.4f), 0xffa59b80, labelFace,
                Paint.Align.RIGHT, 0.65f);

        float badgeTop = body.top + dp(12);
        float badgeLeft = body.left + dp(8);
        String[] badges = {"BBE", "DSL", "DOLBY " + dolbyMode.label};
        for (int index = 0; index < badges.length; index++) {
            float badgeWidth = index == 2 ? dp(42) : dp(23);
            RectF badge = new RectF(badgeLeft, badgeTop,
                    badgeLeft + badgeWidth, badgeTop + dp(14));
            boolean dolbySelected = index == 2 && dolbyMode != DolbyMode.OFF;
            paint.setColor(dolbySelected ? 0xff292315 : 0xff111311);
            canvas.drawRoundRect(badge, dp(1.5f), dp(1.5f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(0.45f));
            paint.setColor(dolbySelected ? 0xffd7b444 : 0x72c6b687);
            canvas.drawRoundRect(badge, dp(1.5f), dp(1.5f), paint);
            paint.setStyle(Paint.Style.FILL);
            drawText(canvas, badges[index], badge.centerX(), badge.centerY() + dp(1.8f),
                    dp(3.6f), dolbySelected ? 0xffffd968 : 0xffbcae88,
                    condensedFace, Paint.Align.CENTER, 0.35f);
            badgeLeft = badge.right + dp(3);
        }

        drawMechanicalControls(canvas, body);
        drawHeadphoneJacks(canvas, body);
    }

    private RectF aiwaDolbyBadge(RectF body) {
        return new RectF(body.left + dp(60), body.top + dp(12),
                body.left + dp(102), body.top + dp(26));
    }

    private void drawMechanicalControls(Canvas canvas, RectF body) {
        boolean aiwa = profile.isAiwaHsJx707();
        boolean f2015 = profile.isSonyWmF2015();
        boolean d6c = profile.isSonyWmD6c();
        boolean darkMachine = aiwa || f2015 || d6c;
        boolean tapeSelector = profile.usesTapeTypeSelector();
        float controlTop = body.bottom - body.height() * 0.116f;
        float left = body.left + body.width() * 0.24f;
        float totalWidth = body.width() * 0.48f;
        float gap = dp(3);
        float buttonWidth = (totalWidth - gap * 3) / 4f;
        float buttonHeight = Math.max(dp(24), body.height() * 0.108f);
        String[] actions = {ACTION_STOP, ACTION_REWIND, ACTION_PLAY, ACTION_FORWARD};
        String[] symbols = {"■", "◀◀", playing ? "Ⅱ" : "▶", "▶▶"};
        for (int i = 0; i < actions.length; i++) {
            float x = left + i * (buttonWidth + gap);
            RectF button = new RectF(x, controlTop, x + buttonWidth, controlTop + buttonHeight);
            boolean pressed = actions[i].equals(pressedAction) || (i == 2 && playing);
            float offset = pressed ? dp(2.1f) * Math.max(0.7f, buttonPress) : 0;
            paint.setColor(darkMachine ? 0xff050606 : 0xff0d2028);
            canvas.drawRoundRect(button.left, button.top + dp(3), button.right,
                    button.bottom + dp(4), dp(2), dp(2), paint);
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(0, button.top, 0, button.bottom,
                    darkMachine
                            ? new int[]{pressed ? 0xff333431 : 0xff555651, 0xff171918}
                            : new int[]{pressed ? 0xff7f817d : 0xffafb0aa, 0xff6d6f6b},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(button.left, button.top + offset, button.right,
                    button.bottom + offset, dp(2), dp(2), paint);
            paint.setShader(null);
            drawText(canvas, symbols[i], button.centerX(), button.centerY() + dp(4) + offset,
                    i == 0 ? dp(7) : dp(6.2f), aiwa ? 0xffd0c49f
                            : (f2015 || d6c) ? 0xffe1e1dc : 0xff252827,
                    condensedFace,
                    Paint.Align.CENTER, 0.2f);
        }

        float toneX = body.right - body.width() * 0.083f;
        float toneY = body.top + body.height() * 0.30f;
        drawText(canvas, tapeSelector ? "TAPE" : "TONE", toneX, toneY - dp(8), dp(4.8f),
                aiwa ? 0xffcbbd94 : d6c ? 0xffd8d8d1 : 0xffd7dfdf,
                labelFace, Paint.Align.CENTER, 0.7f);
        RectF switchTrack = new RectF(toneX - dp(6), toneY, toneX + dp(6), toneY + dp(33));
        paint.setColor(darkMachine ? 0xff070808 : 0xff0c252f);
        canvas.drawRoundRect(switchTrack, dp(4), dp(4), paint);
        float knobY = highTape ? switchTrack.top + dp(4) : switchTrack.bottom - dp(12);
        paint.setColor(aiwa ? 0xffb9aa82 : d6c ? 0xff9ea19c : 0xffc6c5bb);
        canvas.drawRoundRect(switchTrack.left + dp(2), knobY, switchTrack.right - dp(2),
                knobY + dp(8), dp(2), dp(2), paint);
        drawText(canvas, profile.highTapeLabel, switchTrack.right + dp(3),
                switchTrack.top + dp(7), tapeSelector ? dp(3.0f) : dp(3.7f),
                aiwa ? 0xffb6aa8b : (f2015 || d6c) ? 0xffc9cac5 : 0xffadc1c6,
                labelFace, Paint.Align.LEFT, 0.25f);
        drawText(canvas, profile.lowTapeLabel, switchTrack.right + dp(3),
                switchTrack.bottom - dp(1), tapeSelector ? dp(3.1f) : dp(3.7f),
                aiwa ? 0xffb6aa8b : (f2015 || d6c) ? 0xffc9cac5 : 0xffadc1c6,
                labelFace, Paint.Align.LEFT, 0.25f);
    }

    private void drawHeadphoneJacks(Canvas canvas, RectF body) {
        float x = body.right - body.width() * 0.052f;
        float top = body.top + body.height() * 0.16f;
        float radius = Math.max(dp(6.5f), body.height() * 0.027f);
        for (int i = 0; i < 2; i++) {
            float cy = top + i * radius * 3.15f;
            paint.setColor(0xff071319);
            canvas.drawCircle(x, cy, radius + dp(2), paint);
            paint.setColor(0xff555b5b);
            canvas.drawCircle(x, cy, radius, paint);
            paint.setColor(0xff060708);
            canvas.drawCircle(x, cy, radius * 0.56f, paint);
            drawText(canvas, "PHONES " + (i + 1), x - radius - dp(3), cy + dp(2), dp(3.8f),
                    0xffb8c7c9, labelFace, Paint.Align.RIGHT, 0.4f);
        }
    }

    private void drawPlayerInfo(Canvas canvas, RectF info, float reveal) {
        float flip = easeInOut(infoFlipProgress);
        float angle = (float) Math.PI * flip;
        float edge = Math.abs((float) Math.cos(angle));
        float faceScale = Math.max(0.025f, edge);
        float depth = (float) Math.sin(angle);

        // A soft moving shadow and a narrow edge glint make the panel read as one physical
        // placard instead of two screens cross-fading into each other.
        paint.setColor(withAlpha(Color.BLACK, 0.30f * depth));
        canvas.drawRoundRect(info.left - dp(3) * depth,
                info.top + dp(5) * depth,
                info.right + dp(7) * depth,
                info.bottom + dp(8) * depth, dp(13), dp(13), paint);

        canvas.save();
        canvas.scale(faceScale, lerp(1f, 0.985f, depth), info.centerX(), info.centerY());
        boolean interactive = infoFlipProgress <= 0.01f || infoFlipProgress >= 0.99f;
        if (flip < 0.5f) {
            drawPlayerInfoFront(canvas, info, interactive && infoFlipTarget < 0.5f);
        } else {
            drawPlayerInfoBack(canvas, info, interactive && infoFlipTarget > 0.5f);
        }
        if (edge < 0.16f) {
            paint.setColor(0x78d0c8b6);
            canvas.drawRoundRect(info.centerX() - dp(0.8f), info.top + dp(10),
                    info.centerX() + dp(0.8f), info.bottom - dp(10), dp(1), dp(1), paint);
        }
        canvas.restore();
    }

    private void drawPlayerInfoPanel(Canvas canvas, RectF info) {
        paint.setColor(0x680d0e0e);
        canvas.drawRoundRect(info, dp(12), dp(12), paint);
        paint.setShader(new LinearGradient(info.left, info.top, info.right, info.bottom,
                new int[]{0x24000000, 0x08000000, 0x2a06100d},
                null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(info, dp(12), dp(12), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(0x2effffff);
        canvas.drawRoundRect(info, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawPlayerInfoFront(Canvas canvas, RectF info, boolean interactive) {
        drawPlayerInfoPanel(canvas, info);

        float left = info.left + dp(18);
        float right = info.right - dp(18);
        float y = info.top + dp(23);
        drawText(canvas, profile.manufacturer + "  /  " + profile.model, left, y, dp(7.2f),
                ORANGE, labelFace, Paint.Align.LEFT, 1.3f);
        RectF back = new RectF(info.right - dp(46), info.top + dp(9), info.right - dp(10),
                info.top + dp(45));
        drawRoundIcon(canvas, back, "×");
        if (interactive) {
            addHit(ACTION_BACK, back, -1);
        }
        RectF settings = new RectF(info.right - dp(86), info.top + dp(11),
                info.right - dp(54), info.top + dp(43));
        drawSettingsButton(canvas, settings, true);
        if (interactive) {
            addHit(ACTION_SETTINGS, settings, -1);
        }

        CatalogModels.Track track = getSelectedTrack();
        y += dp(37);
        drawText(canvas, "NOW PLAYING", left, y, dp(6.3f), MUTED_INK, labelFace,
                Paint.Align.LEFT, 1.5f);
        y += dp(27);
        drawEllipsizedText(canvas, track == null ? "NO TAPE" : track.title, left, y,
                right - left, dp(20), INK, displayFace, Paint.Align.LEFT);
        y += dp(19);
        drawEllipsizedText(canvas, selectedAlbum == null ? "" : selectedAlbum.artist.toUpperCase(Locale.ROOT),
                left, y, right - left, dp(7.3f), 0xffaaa395, labelFace, Paint.Align.LEFT);

        y += dp(26);
        float fraction = durationMs <= 0 ? 0f : clamp(positionMs / (float) durationMs, 0f, 1f);
        RectF seek = new RectF(left, y, right, y + dp(20));
        paint.setColor(0xff2b2c29);
        canvas.drawRoundRect(left, y + dp(7), right, y + dp(10), dp(2), dp(2), paint);
        paint.setColor(selectedAlbum == null ? ORANGE : selectedAlbum.accent);
        canvas.drawRoundRect(left, y + dp(7), left + seek.width() * fraction, y + dp(10),
                dp(2), dp(2), paint);
        canvas.drawCircle(left + seek.width() * fraction, y + dp(8.5f), dp(3.2f), paint);
        if (interactive) {
            addHit(ACTION_SEEK, seek, -1);
        }
        y += dp(27);
        drawText(canvas, formatTime(positionMs), left, y, dp(7), 0xffcbc5b7, condensedFace,
                Paint.Align.LEFT, 0.6f);
        drawText(canvas, "−" + formatTime(Math.max(0, durationMs - positionMs)), right, y,
                dp(7), 0xff716d64, condensedFace, Paint.Align.RIGHT, 0.6f);

        y += dp(26);
        paint.setColor(0x20ffffff);
        canvas.drawRect(left, y, right, y + dp(0.7f), paint);
        y += dp(22);
        drawText(canvas, "CALIBRATION TARGET", left, y, dp(6.2f), MUTED_INK,
                labelFace, Paint.Align.LEFT, 1.4f);
        y += dp(18);
        drawSpecRow(canvas, left, right, y, "FREQUENCY", profile.frequencySpec(highTape));
        y += dp(18);
        drawSpecRow(canvas, left, right, y, "WOW / FLUTTER", profile.wowFlutterSpec);
        y += dp(18);
        drawSpecRow(canvas, left, right, y, profile.noiseSpecLabel,
                profile.noiseSpecValue(dolbyMode, tapeProfile));
        y += dp(18);
        drawSpecRow(canvas, left, right, y, "TAPE / EQ",
                tapeProfile.manufacturer + " " + tapeProfile.model + "  ·  "
                        + tapeProfile.replayEqMicroseconds + " µs");
        y += dp(15);
        // Keep every calibration value on one shared right edge. The track-list control lives
        // below this row, so reserving its width here made UNIT CONDITION visibly misaligned.
        drawSpecRow(canvas, left, right, y, "UNIT CONDITION", conditionProfile.name);

        float footer = info.bottom - dp(20);
        drawText(canvas, hotline ? "HOT LINE ACTIVE  ·  LIVE MIC" :
                        "DSP: " + profile.model + " × " + tapeProfile.model + "  ·  "
                                + (highTape ? profile.highTapeLabel : profile.lowTapeLabel)
                                + (profile.supportsDolbyBC()
                                ? "  ·  DOLBY " + dolbyMode.label : ""),
                left, footer, dp(5.25f), hotline ? ORANGE : 0xff716d64,
                condensedFace, Paint.Align.LEFT, 0.72f);

        RectF flipButton = new RectF(info.right - dp(86), info.bottom - dp(34),
                info.right - dp(11), info.bottom - dp(10));
        boolean pressed = ACTION_INFO_FLIP.equals(pressedAction);
        paint.setColor(pressed ? 0x35e95c2c : 0x17000000);
        canvas.drawRoundRect(flipButton, dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(pressed ? ORANGE : 0x79e95c2c);
        canvas.drawRoundRect(flipButton, dp(3), dp(3), paint);
        paint.setStyle(Paint.Style.FILL);
        drawText(canvas, "TRACK LIST", flipButton.left + dp(8),
                flipButton.centerY() + dp(2), dp(5.1f),
                pressed ? 0xffffd9c9 : 0xffc9b4a7, labelFace, Paint.Align.LEFT, 0.9f);
        drawText(canvas, "↻", flipButton.right - dp(8),
                flipButton.centerY() + dp(2.4f), dp(7.4f), ORANGE,
                displayFace, Paint.Align.RIGHT, 0f);
        if (interactive) {
            addHit(ACTION_INFO_FLIP, expand(flipButton, dp(2)), -1);
        }
    }

    private void drawPlayerInfoBack(Canvas canvas, RectF info, boolean interactive) {
        drawPlayerInfoPanel(canvas, info);
        CatalogModels.Album album = nowPlayingAlbum == null ? selectedAlbum : nowPlayingAlbum;
        float left = info.left + dp(18);
        float right = info.right - dp(18);
        float y = info.top + dp(23);

        drawText(canvas, "ALBUM DIRECTORY  /  SIDE A", left, y, dp(6.4f), ORANGE,
                labelFace, Paint.Align.LEFT, 1.25f);
        RectF flipBack = new RectF(info.right - dp(46), info.top + dp(9),
                info.right - dp(10), info.top + dp(45));
        drawRoundIcon(canvas, flipBack, "↶");
        if (interactive) {
            addHit(ACTION_INFO_FLIP_BACK, flipBack, -1);
        }

        y += dp(31);
        drawEllipsizedText(canvas, album == null ? "NO ALBUM" : album.title,
                left, y, right - left, dp(16.5f), INK,
                displayFace, Paint.Align.LEFT);
        y += dp(16);
        String albumMeta = album == null ? "" : album.artist.toUpperCase(Locale.ROOT)
                + "  ·  " + album.tracks.size() + (album.tracks.size() == 1 ? " TRACK" : " TRACKS")
                + (album.year == null || album.year.isEmpty() ? "" : "  ·  " + album.year);
        drawEllipsizedText(canvas, albumMeta, left, y, right - left, dp(6.4f),
                0xffa49d90, labelFace, Paint.Align.LEFT);

        y += dp(18);
        paint.setColor(0x20ffffff);
        canvas.drawRect(left, y, right, y + dp(0.7f), paint);
        y += dp(17);
        drawText(canvas, "SELECT A TRACK", left, y, dp(5.8f), MUTED_INK,
                labelFace, Paint.Align.LEFT, 1.25f);

        RectF list = new RectF(left, y + dp(9), right, info.bottom - dp(49));
        drawInfoTrackList(canvas, list, album, interactive);

        float footer = info.bottom - dp(20);
        drawText(canvas, "TAP TO LOAD  ·  SWIPE TO BROWSE", left, footer,
                dp(5.2f), 0xff716d64, labelFace, Paint.Align.LEFT, 0.8f);
        drawText(canvas, "FLIP ↶", right, footer, dp(5.2f), ORANGE,
                labelFace, Paint.Align.RIGHT, 0.8f);
    }

    private void drawInfoTrackList(Canvas canvas,
                                   RectF list,
                                   CatalogModels.Album album,
                                   boolean interactive) {
        if (album == null || album.tracks.isEmpty() || list.height() <= 0) {
            drawText(canvas, "THIS TAPE HAS NO TRACKS", list.left, list.top + dp(20),
                    dp(6), MUTED_INK, labelFace, Paint.Align.LEFT, 1f);
            maxInfoTrackScroll = 0f;
            infoTrackScroll = 0f;
            return;
        }

        float rowHeight = dp(31);
        float contentHeight = album.tracks.size() * rowHeight;
        maxInfoTrackScroll = Math.max(0f, contentHeight - list.height());
        infoTrackScroll = clamp(infoTrackScroll, 0f, maxInfoTrackScroll);
        int first = Math.max(0, (int) Math.floor(infoTrackScroll / rowHeight));
        int last = Math.min(album.tracks.size(),
                (int) Math.ceil((infoTrackScroll + list.height()) / rowHeight) + 1);

        canvas.save();
        canvas.clipRect(list);
        for (int index = first; index < last; index++) {
            CatalogModels.Track track = album.tracks.get(index);
            float top = list.top + index * rowHeight - infoTrackScroll;
            RectF row = new RectF(list.left, top, list.right, top + rowHeight - dp(2));
            boolean current = index == nowPlayingTrackIndex;
            if (current) {
                paint.setColor(withAlpha(album.accent, 0.20f));
                canvas.drawRoundRect(row, dp(3), dp(3), paint);
                paint.setColor(album.accent);
                canvas.drawRoundRect(row.left, row.top + dp(5), row.left + dp(1.4f),
                        row.bottom - dp(5), dp(1), dp(1), paint);
            } else {
                paint.setColor(0x0dffffff);
                canvas.drawRoundRect(row, dp(3), dp(3), paint);
            }

            float baseline = row.centerY() + dp(2.4f);
            drawText(canvas, String.format(Locale.US, "%02d", index + 1),
                    row.left + dp(6), baseline, dp(5.6f),
                    current ? album.accent : 0xff6f6b63,
                    condensedFace, Paint.Align.LEFT, 0.35f);
            drawEllipsizedText(canvas, track.title,
                    row.left + dp(27), baseline, row.width() - dp(70),
                    dp(6.4f), current ? INK : 0xffb9b2a5,
                    current ? labelFace : displayFace, Paint.Align.LEFT);
            drawText(canvas, formatTime(track.durationMs), row.right - dp(6), baseline,
                    dp(5.5f), current ? 0xffd8cfc0 : 0xff6f6b63,
                    condensedFace, Paint.Align.RIGHT, 0.25f);

            if (interactive && row.bottom >= list.top && row.top <= list.bottom) {
                RectF hit = new RectF(row.left, Math.max(row.top, list.top),
                        row.right, Math.min(row.bottom, list.bottom));
                addHit(ACTION_INFO_TRACK, hit, index);
            }
        }
        canvas.restore();

        if (maxInfoTrackScroll > 0f) {
            float thumbHeight = Math.max(dp(14), list.height() * list.height() / contentHeight);
            float thumbTravel = list.height() - thumbHeight;
            float thumbTop = list.top + thumbTravel * infoTrackScroll / maxInfoTrackScroll;
            paint.setColor(0x24ffffff);
            canvas.drawRoundRect(list.right + dp(5), list.top,
                    list.right + dp(5.8f), list.bottom, dp(1), dp(1), paint);
            paint.setColor(0x8ae95c2c);
            canvas.drawRoundRect(list.right + dp(4.6f), thumbTop,
                    list.right + dp(6.2f), thumbTop + thumbHeight, dp(1), dp(1), paint);
        }
    }

    private void drawSpecRow(Canvas canvas, float left, float right, float y,
                             String label, String value) {
        drawText(canvas, label, left, y, dp(5.7f), 0xff6f6b63, labelFace,
                Paint.Align.LEFT, 0.8f);
        drawText(canvas, value, right, y, dp(6.5f), 0xffb7b0a2, condensedFace,
                Paint.Align.RIGHT, 0.5f);
    }

    private void drawCassette(Canvas canvas,
                              RectF rect,
                              CatalogModels.Album album,
                              float angle,
                              boolean inMachine) {
        if (rect.width() <= 2 || rect.height() <= 2) {
            return;
        }
        touchOrRequestArtwork(album);
        float radius = Math.min(rect.width(), rect.height()) * 0.035f;
        paint.setColor(inMachine ? 0x6b050606 : 0x54000000);
        canvas.drawRoundRect(rect.left + dp(1.5f), rect.top + dp(3), rect.right + dp(3),
                rect.bottom + dp(4), radius, radius, paint);

        paint.setAlpha(255);
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{inMachine ? 0xd31b1b1a : 0xd533302b,
                        inMachine ? 0xe4090b0b : 0xcf171613,
                        inMachine ? 0xc52b2a26 : 0xbe403b33},
                null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        // The tiny square grid catches light like the moulded shell in the reference cassette.
        float grid = Math.max(dp(2.4f), rect.width() / 48f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(0.22f), rect.width() / 900f));
        paint.setColor(inMachine ? 0x1affffff : 0x24d7cdbd);
        canvas.save();
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(path);
        for (float x = rect.left + grid; x < rect.right; x += grid) {
            canvas.drawLine(x, rect.top, x, rect.bottom, paint);
        }
        for (float y = rect.top + grid; y < rect.bottom; y += grid) {
            canvas.drawLine(rect.left, y, rect.right, y, paint);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);

        float shellInset = rect.width() * 0.067f;
        RectF label = new RectF(rect.left + shellInset, rect.top + rect.height() * 0.075f,
                rect.right - shellInset, rect.bottom - rect.height() * 0.14f);
        int paper = album == null ? 0xffeee8d7 : album.paper;
        int accent = album == null ? ORANGE : album.accent;
        boolean hasArtwork = album != null && album.artwork != null
                && !album.artwork.isRecycled();
        int ink = hasArtwork ? Color.WHITE : album == null ? 0xff20201e : album.ink;
        paint.setColor(paper);
        canvas.drawRoundRect(label, radius * 0.45f, radius * 0.45f, paint);

        if (hasArtwork) {
            canvas.save();
            path.reset();
            path.addRoundRect(label, radius * 0.45f, radius * 0.45f, Path.Direction.CW);
            canvas.clipPath(path);
            drawCenterCrop(canvas, album.artwork, label);
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(0, label.top, 0, label.bottom,
                    new int[]{0x52000000, 0x16000000, 0x72000000},
                    new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(label, paint);
            paint.setShader(null);
            canvas.restore();
        }

        paint.setColor(hasArtwork ? withAlpha(accent, 0.72f) : accent);
        RectF stripe = new RectF(label.left + label.width() * 0.40f, label.top,
                label.left + label.width() * 0.70f, label.bottom);
        canvas.drawRect(stripe, paint);

        float reelY = label.top + label.height() * 0.43f;
        float reelRadius = Math.min(label.height() * 0.22f, label.width() * 0.115f);
        float leftReelX = label.left + label.width() * 0.28f;
        float rightReelX = label.right - label.width() * 0.28f;
        float played = durationMs <= 0 ? 0.42f : clamp(positionMs / (float) durationMs, 0f, 1f);
        drawReel(canvas, leftReelX, reelY, reelRadius,
                angle, lerp(0.86f, 0.48f, played), inMachine);
        drawReel(canvas, rightReelX, reelY, reelRadius,
                -angle * 1.04f, lerp(0.48f, 0.86f, played), inMachine);

        RectF tapeWindow = new RectF(leftReelX + reelRadius * 0.82f,
                reelY - reelRadius * 0.62f,
                rightReelX - reelRadius * 0.82f,
                reelY + reelRadius * 0.62f);
        paint.setColor(0xff20201e);
        canvas.drawRoundRect(tapeWindow, reelRadius * 0.15f, reelRadius * 0.15f, paint);
        paint.setColor(0xff6e6252);
        canvas.drawOval(tapeWindow.left + tapeWindow.width() * 0.18f,
                tapeWindow.top + tapeWindow.height() * 0.18f,
                tapeWindow.right - tapeWindow.width() * 0.18f,
                tapeWindow.bottom - tapeWindow.height() * 0.18f, paint);
        paint.setColor(0xff171716);
        canvas.drawRect(tapeWindow.left + tapeWindow.width() * 0.47f, tapeWindow.top,
                tapeWindow.left + tapeWindow.width() * 0.53f, tapeWindow.bottom, paint);

        float labelScale = clamp(rect.width() / dp(260), 0.44f, 1.05f);
        CatalogModels.Track activeTrack = getSelectedTrack();
        String title = album == null ? "WALKTAPE"
                : activeTrack == null ? album.title : activeTrack.title;
        drawEllipsizedText(canvas, title, label.left + dp(8) * labelScale,
                label.bottom - dp(8) * labelScale, label.width() * 0.62f,
                dp(10) * labelScale, ink, condensedFace, Paint.Align.LEFT);
        drawText(canvas, "A", label.right - dp(10) * labelScale,
                label.bottom - dp(7) * labelScale, dp(15) * labelScale, ink,
                condensedFace, Paint.Align.RIGHT, 0);
        drawText(canvas, tapeProfile.typeLabel(), label.left + dp(7) * labelScale,
                label.top + dp(10) * labelScale, dp(4.3f) * labelScale, ink,
                labelFace, Paint.Align.LEFT, 0.45f);
        drawText(canvas, tapeProfile.manufacturer + "  " + tapeProfile.model,
                label.right - dp(7) * labelScale, label.top + dp(10) * labelScale,
                dp(4.3f) * labelScale, ink, labelFace, Paint.Align.RIGHT, 0.45f);

        // Lower head opening and guide holes.
        path.reset();
        path.moveTo(rect.left + rect.width() * 0.26f, rect.bottom - rect.height() * 0.06f);
        path.lineTo(rect.left + rect.width() * 0.34f, rect.bottom - rect.height() * 0.23f);
        path.lineTo(rect.right - rect.width() * 0.34f, rect.bottom - rect.height() * 0.23f);
        path.lineTo(rect.right - rect.width() * 0.26f, rect.bottom - rect.height() * 0.06f);
        path.close();
        paint.setColor(0x9a0e0f0e);
        canvas.drawPath(path, paint);

        float holeRadius = Math.max(dp(1.2f), rect.width() * 0.011f);
        paint.setColor(0xff050606);
        canvas.drawCircle(rect.centerX() - rect.width() * 0.17f,
                rect.bottom - rect.height() * 0.12f, holeRadius, paint);
        canvas.drawCircle(rect.centerX() + rect.width() * 0.17f,
                rect.bottom - rect.height() * 0.12f, holeRadius, paint);
        canvas.drawCircle(rect.centerX(), rect.bottom - rect.height() * 0.12f,
                holeRadius * 0.8f, paint);

        float screw = Math.max(dp(0.9f), rect.width() * 0.013f);
        drawScrew(canvas, rect.left + screw * 2.2f, rect.top + screw * 2.2f, screw);
        drawScrew(canvas, rect.right - screw * 2.2f, rect.top + screw * 2.2f, screw);
        drawScrew(canvas, rect.left + screw * 2.2f, rect.bottom - screw * 2.2f, screw);
        drawScrew(canvas, rect.right - screw * 2.2f, rect.bottom - screw * 2.2f, screw);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(0.5f), rect.width() / 380f));
        paint.setColor(inMachine ? 0x387fa4ae : 0x6eb1aa9e);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawReel(Canvas canvas, float cx, float cy, float radius,
                          float angle, float tapeScale, boolean inMachine) {
        paint.setColor(inMachine ? 0xff171819 : 0xff252521);
        canvas.drawCircle(cx, cy, radius * 1.08f, paint);
        paint.setColor(0xff7a6751);
        canvas.drawCircle(cx, cy, radius * tapeScale, paint);
        paint.setColor(0xffd6d0bd);
        canvas.drawCircle(cx, cy, radius * 0.64f, paint);
        paint.setColor(0xff222321);
        canvas.drawCircle(cx, cy, radius * 0.30f, paint);
        canvas.save();
        canvas.rotate(angle, cx, cy);
        paint.setColor(0xffede6d3);
        for (int i = 0; i < 6; i++) {
            canvas.save();
            canvas.rotate(i * 60f, cx, cy);
            RectF spoke = new RectF(cx - radius * 0.08f, cy - radius * 0.58f,
                    cx + radius * 0.08f, cy - radius * 0.22f);
            canvas.drawRoundRect(spoke, radius * 0.05f, radius * 0.05f, paint);
            canvas.restore();
        }
        canvas.restore();
    }

    private void drawScrew(Canvas canvas, float cx, float cy, float radius) {
        paint.setColor(0xff4a4842);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(0xff141514);
        paint.setStrokeWidth(Math.max(dp(0.35f), radius * 0.22f));
        canvas.drawLine(cx - radius * 0.55f, cy, cx + radius * 0.55f, cy, paint);
        canvas.drawLine(cx, cy - radius * 0.55f, cx, cy + radius * 0.55f, paint);
    }

    private void drawCoverArt(Canvas canvas, RectF rect, CatalogModels.Album album) {
        touchOrRequestArtwork(album);
        canvas.save();
        path.reset();
        path.addRoundRect(rect, dp(2.5f), dp(2.5f), Path.Direction.CW);
        canvas.clipPath(path);
        if (album.artwork != null && !album.artwork.isRecycled()) {
            drawCenterCrop(canvas, album.artwork, rect);
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[]{0x00000000, 0x08000000, 0x69000000},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            drawText(canvas, album.title, rect.left + rect.width() * 0.08f,
                    rect.bottom - rect.height() * 0.10f,
                    Math.max(dp(7), rect.width() * 0.088f), Color.WHITE,
                    labelFace, Paint.Align.LEFT, 0.2f);
            drawText(canvas, album.artist.toUpperCase(Locale.ROOT),
                    rect.left + rect.width() * 0.08f,
                    rect.bottom - rect.height() * 0.047f,
                    Math.max(dp(4), rect.width() * 0.043f), 0xffd9d6ce,
                    labelFace, Paint.Align.LEFT, 0.8f);
        } else {
            drawGeneratedCover(canvas, rect, album);
        }
        canvas.restore();
    }

    private void drawGeneratedCover(Canvas canvas, RectF rect, CatalogModels.Album album) {
        int style = Math.floorMod(album.artworkStyle, 6);
        if (style == 0) {
            paint.setColor(album.paper);
            canvas.drawRect(rect, paint);
            paint.setColor(album.accent);
            canvas.drawRect(rect.left + rect.width() * 0.54f, rect.top,
                    rect.right, rect.bottom, paint);
            paint.setColor(album.ink);
            canvas.drawCircle(rect.left + rect.width() * 0.73f,
                    rect.top + rect.height() * 0.31f, rect.width() * 0.16f, paint);
            drawText(canvas, "A", rect.left + rect.width() * 0.10f,
                    rect.top + rect.height() * 0.31f, rect.width() * 0.25f,
                    album.ink, condensedFace, Paint.Align.LEFT, 0);
        } else if (style == 1) {
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{album.paper, darken(album.paper, 0.22f), album.ink},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            for (int i = 0; i < 7; i++) {
                paint.setColor(withAlpha(album.accent, 0.18f + i * 0.05f));
                float y = rect.top + rect.height() * (0.14f + i * 0.085f);
                canvas.drawRect(rect.left + rect.width() * (0.08f + i * 0.035f), y,
                        rect.right - rect.width() * 0.08f, y + rect.height() * 0.018f, paint);
            }
            paint.setColor(0x5affffff);
            canvas.drawCircle(rect.right - rect.width() * 0.18f,
                    rect.top + rect.height() * 0.17f, rect.width() * 0.07f, paint);
        } else if (style == 2) {
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                    new int[]{album.paper, album.accent, album.ink},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            paint.setStrokeWidth(Math.max(dp(0.5f), rect.width() * 0.006f));
            paint.setColor(0x66ffffff);
            float horizon = rect.top + rect.height() * 0.55f;
            for (int i = -5; i <= 5; i++) {
                canvas.drawLine(rect.centerX(), horizon,
                        rect.centerX() + i * rect.width() * 0.18f, rect.bottom, paint);
            }
            for (int i = 0; i < 5; i++) {
                float yy = lerp(horizon, rect.bottom, (i * i) / 16f);
                canvas.drawLine(rect.left, yy, rect.right, yy, paint);
            }
            paint.setColor(0x99ffdfa0);
            canvas.drawCircle(rect.centerX(), horizon - rect.height() * 0.16f,
                    rect.width() * 0.15f, paint);
        } else if (style == 3) {
            paint.setColor(album.paper);
            canvas.drawRect(rect, paint);
            paint.setColor(withAlpha(album.accent, 0.93f));
            canvas.drawCircle(rect.centerX(), rect.top + rect.height() * 0.36f,
                    rect.width() * 0.31f, paint);
            paint.setColor(album.ink);
            canvas.drawCircle(rect.centerX(), rect.top + rect.height() * 0.36f,
                    rect.width() * 0.12f, paint);
            paint.setColor(withAlpha(album.ink, 0.7f));
            canvas.drawRect(rect.left + rect.width() * 0.12f,
                    rect.top + rect.height() * 0.69f,
                    rect.right - rect.width() * 0.12f,
                    rect.top + rect.height() * 0.71f, paint);
        } else if (style == 4) {
            paint.setColor(album.paper);
            canvas.drawRect(rect, paint);
            paint.setStrokeWidth(Math.max(dp(0.8f), rect.width() * 0.012f));
            paint.setColor(album.accent);
            path.reset();
            path.moveTo(rect.left, rect.centerY());
            for (int i = 0; i <= 20; i++) {
                float x = rect.left + rect.width() * i / 20f;
                float y = rect.centerY() + (float) Math.sin(i * 1.21) * rect.height() * (0.08f + (i % 4) * 0.025f);
                path.lineTo(x, y);
            }
            canvas.drawPath(path, paint);
            paint.setColor(withAlpha(album.ink, 0.09f));
            for (int i = 0; i < 9; i++) {
                canvas.drawRect(rect.left, rect.top + rect.height() * i / 9f,
                        rect.right, rect.top + rect.height() * i / 9f + dp(1), paint);
            }
        } else {
            paint.setAlpha(255);
            paint.setShader(new RadialGradient(rect.centerX(), rect.centerY(), rect.width() * 0.7f,
                    new int[]{album.paper, album.accent, album.ink}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            paint.setColor(0x55ffffff);
            path.reset();
            path.moveTo(rect.left, rect.top + rect.height() * 0.9f);
            path.lineTo(rect.right, rect.top + rect.height() * 0.36f);
            path.lineTo(rect.right, rect.top + rect.height() * 0.48f);
            path.lineTo(rect.left, rect.bottom);
            path.close();
            canvas.drawPath(path, paint);
        }

        float titleSize = Math.max(dp(7), rect.width() * 0.105f);
        int titleColor = style == 0 || style == 3 || style == 4 ? album.ink : 0xfff0eadb;
        drawEllipsizedText(canvas, album.title, rect.left + rect.width() * 0.08f,
                rect.bottom - rect.height() * 0.105f, rect.width() * 0.84f,
                titleSize, titleColor, labelFace, Paint.Align.LEFT);
        drawEllipsizedText(canvas, album.artist.toUpperCase(Locale.ROOT),
                rect.left + rect.width() * 0.08f,
                rect.bottom - rect.height() * 0.052f, rect.width() * 0.84f,
                Math.max(dp(4.2f), rect.width() * 0.047f), withAlpha(titleColor, 0.72f),
                labelFace, Paint.Align.LEFT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                lastTouchY = y;
                dragging = false;
                HitTarget downTarget = findTarget(x, y);
                pressedAction = downTarget == null ? null : downTarget.action;
                miniPlayerGesture = downTarget != null
                        && isMiniPlayerAction(downTarget.action);
                infoTrackGesture = scene == Scene.PLAYER
                        && infoFlipProgress >= 0.99f
                        && playerInfoBounds.contains(x, y);
                settingsGesture = settingsProgress > 0.01f || settingsTarget > 0f;
                seeking = downTarget != null && ACTION_SEEK.equals(downTarget.action);
                if (downTarget != null && ACTION_HOTLINE.equals(downTarget.action)) {
                    beginHotlinePress();
                }
                if (seeking) {
                    seekFromTouch(downTarget, x, false);
                }
                animateNextFrame();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = y - lastTouchY;
                if (hotlinePressActive) {
                    lastTouchY = y;
                    return true;
                }
                if (miniPlayerGesture) {
                    lastTouchY = y;
                    return true;
                }
                if (!dragging && (Math.abs(y - downY) > dp(6) || Math.abs(x - downX) > dp(6))) {
                    dragging = true;
                    pressedAction = null;
                }
                if (seeking) {
                    HitTarget seekTarget = findTargetByAction(ACTION_SEEK);
                    if (seekTarget != null) {
                        seekFromTouch(seekTarget, x, false);
                    }
                } else if (dragging) {
                    if (settingsGesture) {
                        // The machine selector is deliberately fixed, not a hidden shelf scroll.
                    } else if (infoTrackGesture) {
                        infoTrackScroll = clamp(infoTrackScroll - dy, 0, maxInfoTrackScroll);
                    } else if (scene == Scene.LIBRARY) {
                        libraryScroll = clamp(libraryScroll - dy, 0, maxLibraryScroll);
                    } else if (detailTarget > 0.5f && detailProgress > 0.75f) {
                        detailScroll = clamp(detailScroll - dy, 0, maxDetailScroll);
                    } else if (scene == Scene.CASE && trackSheetTarget > 0.5f && downY >= trackSheetTop) {
                        trackScroll = clamp(trackScroll - dy, 0, maxTrackScroll);
                    }
                    invalidate();
                }
                lastTouchY = y;
                return true;
            case MotionEvent.ACTION_UP:
                if (hotlinePressActive) {
                    pressedAction = null;
                    buttonPress = Math.max(buttonPress, 0.65f);
                    endHotlinePress();
                    miniPlayerGesture = false;
                    performClick();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    animateNextFrame();
                    return true;
                }
                String oldPressed = pressedAction;
                pressedAction = null;
                buttonPress = Math.max(buttonPress, oldPressed == null ? 0f : 0.65f);
                if (seeking) {
                    HitTarget seekTarget = findTargetByAction(ACTION_SEEK);
                    if (seekTarget != null) {
                        // Preview freely while dragging, but flush/decode only once on release.
                        // Sending a seek for every MOVE event repeatedly reset MediaCodec and
                        // AudioTrack, which was the main cause of post-scrub stutter.
                        seekFromTouch(seekTarget, x, true);
                    }
                    seeking = false;
                    performClick();
                    return true;
                }
                if (!dragging) {
                    HitTarget target = findTarget(x, y);
                    if (target != null) {
                        performClick();
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        handleAction(target);
                    }
                }
                dragging = false;
                miniPlayerGesture = false;
                infoTrackGesture = false;
                settingsGesture = false;
                animateNextFrame();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (hotlinePressActive) {
                    endHotlinePress();
                }
                pressedAction = null;
                dragging = false;
                seeking = false;
                miniPlayerGesture = false;
                infoTrackGesture = false;
                settingsGesture = false;
                animateNextFrame();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void handleAction(HitTarget target) {
        switch (target.action) {
            case ACTION_PLAYER_INFO_TOGGLE:
                if (playerViewLocked) {
                    announceForAccessibility("播放视图已锁定");
                } else {
                    playerInfoTarget = playerInfoTarget < 0.5f ? 1f : 0f;
                    recyclePlayerStaticFrame();
                    announceForAccessibility(playerInfoTarget < 0.5f
                            ? "全屏磁带机，播放信息已隐藏" : "显示播放信息");
                }
                break;
            case ACTION_PLAYER_VIEW_LOCK:
                playerViewLocked = !playerViewLocked;
                announceForAccessibility(playerViewLocked
                        ? "已锁定播放视图，磁带机区域触摸不会切换信息面板"
                        : "已解除播放视图锁定");
                invalidate();
                break;
            case ACTION_SETTINGS:
                settingsTarget = 1f;
                announceForAccessibility("选择磁带机、磁带配方与机器不完美感");
                break;
            case ACTION_SETTINGS_CLOSE:
                settingsTarget = 0f;
                break;
            case ACTION_SETTINGS_SECTION:
                settingsSection = Math.max(0, Math.min(3, target.index));
                announceForAccessibility(settingsSection == 0
                        ? "磁带机列表" : settingsSection == 1
                        ? "磁带配方列表" : settingsSection == 2
                        ? "录音电平列表" : "机器不完美感列表");
                invalidate();
                break;
            case ACTION_MACHINE_PROFILE:
                List<TapeMachineProfile> profiles = TapeMachineProfile.availableProfiles();
                if (target.index >= 0 && target.index < profiles.size()) {
                    TapeMachineProfile selectedProfile = profiles.get(target.index);
                    boolean changed = !selectedProfile.id.equals(profile.id);
                    setMachineProfile(selectedProfile);
                    settingsTarget = 0f;
                    if (changed && listener != null) {
                        if (!selectedProfile.hotlineSupported) {
                            listener.onHotlineChanged(false);
                        }
                        listener.onMachineProfileChanged(selectedProfile);
                        if (selectedProfile.usesTapeTypeSelector()) {
                            boolean correctPosition = tapeProfile.isHighPosition();
                            setHighTape(correctPosition);
                            listener.onToneChanged(correctPosition);
                        }
                    }
                    announceForAccessibility("已切换到 " + selectedProfile.manufacturer
                            + " " + selectedProfile.model);
                }
                break;
            case ACTION_TAPE_PROFILE:
                List<TapeStockProfile> tapeProfiles = TapeStockProfile.availableProfiles();
                if (target.index >= 0 && target.index < tapeProfiles.size()) {
                    TapeStockProfile selectedTape = tapeProfiles.get(target.index);
                    boolean changed = !selectedTape.id.equals(tapeProfile.id);
                    setTapeProfile(selectedTape);
                    settingsTarget = 0f;
                    if (changed && listener != null) {
                        listener.onTapeProfileChanged(selectedTape);
                        if (profile.usesTapeTypeSelector()) {
                            boolean correctPosition = selectedTape.isHighPosition();
                            setHighTape(correctPosition);
                            listener.onToneChanged(correctPosition);
                        }
                    }
                    announceForAccessibility("已装入 " + selectedTape.manufacturer
                            + " " + selectedTape.model + " " + selectedTape.typeShortLabel());
                }
                break;
            case ACTION_RECORD_LEVEL:
                List<RecordLevelProfile> levels = RecordLevelProfile.availableProfiles();
                if (target.index >= 0 && target.index < levels.size()) {
                    RecordLevelProfile selectedLevel = levels.get(target.index);
                    boolean levelChanged = !selectedLevel.id.equals(recordLevel.id);
                    setRecordLevel(selectedLevel);
                    settingsTarget = 0f;
                    if (levelChanged && listener != null) {
                        listener.onRecordLevelChanged(selectedLevel);
                    }
                    announceForAccessibility("录音电平已设为 " + selectedLevel.name
                            + "，峰值 " + Math.round(selectedLevel.peakOverReferenceDb)
                            + " 分贝");
                }
                break;
            case ACTION_CONDITION_PROFILE:
                List<MachineConditionProfile> conditionProfiles =
                        MachineConditionProfile.availableProfiles();
                if (target.index >= 0 && target.index < conditionProfiles.size()) {
                    MachineConditionProfile selectedCondition =
                            conditionProfiles.get(target.index);
                    boolean changed = !selectedCondition.id.equals(conditionProfile.id);
                    setConditionProfile(selectedCondition);
                    settingsTarget = 0f;
                    if (changed && listener != null) {
                        listener.onConditionProfileChanged(selectedCondition);
                    }
                    announceForAccessibility("机器状态已切换为 " + selectedCondition.name
                            + "，只模拟健康个体公差");
                }
                break;
            case ACTION_IMPORT:
                if (listener != null) {
                    listener.onImportRequested();
                }
                break;
            case ACTION_ALBUM:
                if (target.index >= 0 && target.index < albums.size()) {
                    selectedAlbumIndex = target.index;
                    selectedAlbum = albums.get(target.index);
                    selectedTrackIndex = 0;
                    positionMs = 0;
                    durationMs = selectedAlbum.tracks.isEmpty() ? 1 : selectedAlbum.tracks.get(0).durationMs;
                    scene = Scene.CASE;
                    trackSheetProgress = 0;
                    trackSheetTarget = 0;
                    detailProgress = 0;
                    detailTarget = 0;
                    detailScroll = 0;
                    trackScroll = 0;
                    restartScene();
                    announceForAccessibility("已打开 " + selectedAlbum.title + " 的磁带盒");
                }
                break;
            case ACTION_BACK:
                handleBackPressed();
                break;
            case ACTION_JCARD:
                detailTarget = 1f;
                detailScroll = 0;
                trackSheetTarget = 0;
                requestSelectedLyrics(false);
                announceForAccessibility("展开封页与歌词");
                break;
            case ACTION_DETAIL_CLOSE:
                detailTarget = 0f;
                break;
            case ACTION_DETAIL_TRACK:
                if (selectedAlbum != null && target.index >= 0 && target.index < selectedAlbum.tracks.size()) {
                    selectedTrackIndex = target.index;
                    positionMs = 0;
                    durationMs = selectedAlbum.tracks.get(target.index).durationMs;
                    detailScroll = Math.min(detailScroll, maxDetailScroll);
                    requestSelectedLyrics(false);
                    announceForAccessibility("歌词：" + selectedAlbum.tracks.get(target.index).title);
                }
                break;
            case ACTION_LYRICS_RETRY:
                CatalogModels.Track retryTrack = getSelectedTrack();
                if (retryTrack != null && retryTrack.lyricsOpenNetworkSettings
                        && listener != null) {
                    listener.onLyricsNetworkSettingsRequested(selectedAlbum, retryTrack);
                    announceForAccessibility("打开 WalkTape 网络设置");
                } else {
                    requestSelectedLyrics(true);
                    announceForAccessibility("重新检索歌词");
                }
                break;
            case ACTION_LYRICS_SOURCE:
                CatalogModels.Track sourceTrack = getSelectedTrack();
                if (sourceTrack != null && listener != null) {
                    listener.onLyricsSourceRequested(sourceTrack);
                }
                break;
            case ACTION_CASSETTE:
                trackSheetTarget = trackSheetTarget > 0.5f ? 0f : 1f;
                detailTarget = 0f;
                trackScroll = 0;
                announceForAccessibility("选择磁带曲目");
                break;
            case ACTION_SHEET_CLOSE:
                trackSheetTarget = 0f;
                break;
            case ACTION_TRACK:
                enterPlayer(target.index);
                break;
            case ACTION_MINI_PLAYER:
                returnToNowPlaying();
                break;
            case ACTION_MINI_PLAY:
                if (getNowPlayingTrack() != null) {
                    playing = !playing;
                    if (listener != null) {
                        listener.onPlayPauseRequested();
                    }
                }
                break;
            case ACTION_MINI_NEXT:
                skipTrack(1);
                break;
            case ACTION_INFO_FLIP:
                infoTrackScroll = Math.max(0f,
                        nowPlayingTrackIndex * dp(31) - dp(48));
                infoFlipTarget = 1f;
                announceForAccessibility("打开当前专辑曲目列表");
                break;
            case ACTION_INFO_FLIP_BACK:
                infoFlipTarget = 0f;
                announceForAccessibility("返回播放信息");
                break;
            case ACTION_INFO_TRACK:
                selectInfoTrack(target.index);
                break;
            case ACTION_PLAY:
                playing = !playing;
                if (listener != null) {
                    listener.onPlayPauseRequested();
                }
                announceForAccessibility(playing ? "播放" : "暂停");
                break;
            case ACTION_STOP:
                playing = false;
                positionMs = 0;
                if (listener != null) {
                    listener.onStopRequested();
                }
                announceForAccessibility("停止");
                break;
            case ACTION_PREVIOUS:
                skipTrack(-1);
                break;
            case ACTION_NEXT:
                skipTrack(1);
                break;
            case ACTION_REWIND:
                seekBy(-0.10f);
                break;
            case ACTION_FORWARD:
                seekBy(0.10f);
                break;
            case ACTION_HOTLINE:
                // HOT LINE is a physical momentary button; touch down/up owns its state.
                break;
            case ACTION_TONE:
                highTape = !highTape;
                if (listener != null) {
                    listener.onToneChanged(highTape);
                }
                announceForAccessibility(highTape ? profile.highTapeLabel : profile.lowTapeLabel);
                break;
            case ACTION_DOLBY:
                if (profile.supportsDolbyBC()) {
                    DolbyMode selected = target.index >= 0
                            ? DolbyMode.forSelectorIndex(target.index) : dolbyMode.next();
                    setDolbyMode(selected);
                    if (listener != null) {
                        listener.onDolbyModeChanged(selected);
                    }
                    announceForAccessibility("Dolby " + selected.label);
                }
                break;
            default:
                break;
        }
        animateNextFrame();
    }

    private void requestSelectedLyrics(boolean forceRefresh) {
        CatalogModels.Track track = getSelectedTrack();
        if (selectedAlbum == null || track == null || listener == null) {
            return;
        }
        if (!forceRefresh && (track.lyricsState == CatalogModels.LyricsState.LOADING
                || track.lyricsState == CatalogModels.LyricsState.READY
                || track.lyricsState == CatalogModels.LyricsState.NOT_FOUND)) {
            return;
        }
        listener.onLyricsRequested(selectedAlbum, track, forceRefresh);
    }

    private void beginHotlinePress() {
        if (hotlinePressActive || !profile.hotlineSupported) {
            return;
        }
        hotlinePressActive = true;
        hotline = true;
        if (listener != null) {
            listener.onHotlineChanged(true);
        }
        announceForAccessibility("Hot Line 已按下");
    }

    private void endHotlinePress() {
        if (!hotlinePressActive) {
            return;
        }
        hotlinePressActive = false;
        hotline = false;
        if (listener != null) {
            listener.onHotlineChanged(false);
        }
        announceForAccessibility("Hot Line 已松开");
    }

    private void enterPlayer(int trackIndex) {
        if (selectedAlbum == null || trackIndex < 0 || trackIndex >= selectedAlbum.tracks.size()) {
            return;
        }
        selectedTrackIndex = trackIndex;
        CatalogModels.Track track = selectedAlbum.tracks.get(trackIndex);
        nowPlayingAlbum = selectedAlbum;
        nowPlayingTrackIndex = trackIndex;
        positionMs = 0;
        durationMs = Math.max(1, track.durationMs);
        playing = true;
        hotline = false;
        trackSheetTarget = 0;
        trackSheetProgress = 0;
        infoFlipProgress = 0f;
        infoFlipTarget = 0f;
        infoTrackScroll = 0f;
        scene = Scene.PLAYER;
        restartScene();
        if (listener != null) {
            listener.onTrackSelected(selectedAlbum, track);
        }
        announceForAccessibility("正在播放 " + track.title);
    }

    private void skipTrack(int direction) {
        if (nowPlayingAlbum == null || nowPlayingAlbum.tracks.isEmpty()) {
            return;
        }
        nowPlayingTrackIndex = Math.floorMod(
                nowPlayingTrackIndex + direction, nowPlayingAlbum.tracks.size());
        CatalogModels.Track track = nowPlayingAlbum.tracks.get(nowPlayingTrackIndex);
        if (scene == Scene.PLAYER) {
            selectedAlbum = nowPlayingAlbum;
            selectedTrackIndex = nowPlayingTrackIndex;
        }
        positionMs = 0;
        durationMs = Math.max(1, track.durationMs);
        playing = true;
        if (listener != null) {
            listener.onSkipRequested(direction);
        }
        announceForAccessibility(track.title);
    }

    private void returnToNowPlaying() {
        CatalogModels.Track track = getNowPlayingTrack();
        if (nowPlayingAlbum == null || track == null) {
            return;
        }
        selectedAlbum = nowPlayingAlbum;
        selectedAlbumIndex = -1;
        for (int index = 0; index < albums.size(); index++) {
            if (albums.get(index).id == nowPlayingAlbum.id) {
                selectedAlbumIndex = index;
                break;
            }
        }
        selectedTrackIndex = nowPlayingTrackIndex;
        hotline = false;
        detailTarget = 0f;
        detailProgress = 0f;
        trackSheetTarget = 0f;
        trackSheetProgress = 0f;
        infoFlipTarget = 0f;
        infoFlipProgress = 0f;
        infoTrackScroll = 0f;
        scene = Scene.PLAYER;
        restartScene();
        if (listener != null) {
            listener.onReturnToPlayer();
        }
        announceForAccessibility("Now playing " + track.title);
    }

    private void selectInfoTrack(int trackIndex) {
        CatalogModels.Album album = nowPlayingAlbum == null ? selectedAlbum : nowPlayingAlbum;
        if (album == null || trackIndex < 0 || trackIndex >= album.tracks.size()) {
            return;
        }
        infoFlipTarget = 0f;
        if (album == nowPlayingAlbum && trackIndex == nowPlayingTrackIndex) {
            announceForAccessibility("返回 " + album.tracks.get(trackIndex).title);
            return;
        }

        CatalogModels.Track track = album.tracks.get(trackIndex);
        selectedAlbum = album;
        selectedTrackIndex = trackIndex;
        nowPlayingAlbum = album;
        nowPlayingTrackIndex = trackIndex;
        positionMs = 0;
        durationMs = Math.max(1, track.durationMs);
        playing = true;
        hotline = false;
        if (listener != null) {
            listener.onTrackSelected(album, track);
        }
        announceForAccessibility("正在播放 " + track.title);
    }

    private void seekBy(float delta) {
        float fraction = durationMs <= 0 ? 0 : positionMs / (float) durationMs;
        fraction = clamp(fraction + delta, 0, 1);
        positionMs = (long) (durationMs * fraction);
        if (listener != null) {
            listener.onSeekRequested(fraction);
        }
    }

    private void seekFromTouch(HitTarget target, float x, boolean commit) {
        float fraction = clamp((x - target.rect.left) / Math.max(1f, target.rect.width()), 0, 1);
        positionMs = (long) (durationMs * fraction);
        if (commit && listener != null) {
            listener.onSeekRequested(fraction);
        }
        invalidate();
    }

    private void drawPlayerBodyTexture(Canvas canvas, RectF rect) {
        float step = Math.max(dp(5), rect.height() / 35f);
        if (playerTextureShader == null) {
            playerTextureTile = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            Canvas tileCanvas = new Canvas(playerTextureTile);
            Paint tilePaint = new Paint();
            tilePaint.setColor(0x182fe0ff);
            tileCanvas.drawRect(0, 0, 1.55f, 1.55f, tilePaint);
            playerTextureShader = new BitmapShader(playerTextureTile,
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }
        textureMatrix.reset();
        float scale = step / playerTextureTile.getWidth();
        textureMatrix.setScale(scale, scale);
        playerTextureShader.setLocalMatrix(textureMatrix);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(playerTextureShader);
        canvas.drawRoundRect(rect, dp(5), dp(5), paint);
        paint.setShader(null);
    }

    private void drawBrushedMetal(Canvas canvas, RectF rect) {
        paint.setStrokeWidth(dp(0.35f));
        for (int i = 0; i < 34; i++) {
            float y = rect.top + rect.height() * i / 34f;
            paint.setColor(i % 3 == 0 ? 0x13ffffff : 0x0d151615);
            canvas.drawLine(rect.left + dp(2), y, rect.right - dp(2), y, paint);
        }
    }

    private void drawVerticalLabel(Canvas canvas, String text, float x, float bottom,
                                   float size, int color) {
        canvas.save();
        canvas.rotate(-90f, x, bottom);
        drawText(canvas, text, x, bottom, size, color, condensedFace,
                Paint.Align.LEFT, 0.5f);
        canvas.restore();
    }

    private void drawRoundIcon(Canvas canvas, RectF rect, String glyph) {
        paint.setColor(0x401f201e);
        canvas.drawOval(rect, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(0x477f7b71);
        canvas.drawOval(rect, paint);
        paint.setStyle(Paint.Style.FILL);
        drawText(canvas, glyph, rect.centerX(), rect.centerY() + dp(6),
                "‹".equals(glyph) ? dp(25) : dp(17), INK, displayFace,
                Paint.Align.CENTER, 0);
    }

    private void drawSettingsButton(Canvas canvas, RectF rect, boolean compact) {
        boolean pressed = ACTION_SETTINGS.equals(pressedAction);
        paint.setColor(pressed ? 0x38e95c2c : (compact ? 0x321f201e : 0xff252622));
        canvas.drawOval(rect, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.75f));
        paint.setColor(pressed ? ORANGE : (compact ? 0x637f7b71 : 0x665e5b53));
        canvas.drawOval(rect, paint);

        float cx = rect.centerX();
        float cy = rect.centerY();
        float gearRadius = rect.height() * 0.19f;
        paint.setStrokeWidth(dp(1.15f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(pressed ? ORANGE : INK);
        canvas.drawCircle(cx, cy, gearRadius, paint);
        for (int spoke = 0; spoke < 6; spoke++) {
            double angle = TWO_PI_FOR_DRAWING * spoke / 6.0;
            float innerX = cx + (float) Math.cos(angle) * gearRadius * 1.18f;
            float innerY = cy + (float) Math.sin(angle) * gearRadius * 1.18f;
            float outerX = cx + (float) Math.cos(angle) * gearRadius * 1.62f;
            float outerY = cy + (float) Math.sin(angle) * gearRadius * 1.62f;
            canvas.drawLine(innerX, innerY, outerX, outerY, paint);
        }
        canvas.drawCircle(cx, cy, gearRadius * 0.30f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMachineSettings(Canvas canvas) {
        float reveal = easeOut(clamp(settingsProgress, 0f, 1f));
        int width = getWidth();
        int height = getHeight();
        paint.setColor(withAlpha(Color.BLACK, reveal * 0.79f));
        canvas.drawRect(0, 0, width, height, paint);
        addHit(ACTION_SETTINGS_CLOSE, new RectF(0, 0, width, height), -1);

        float panelWidth = Math.min(width - dp(24), dp(width > height ? 470 : 332));
        float panelHeight = Math.min(height - dp(24), dp(318));
        float left = (width - panelWidth) * 0.5f;
        float top = (height - panelHeight) * 0.5f + dp(13) * (1f - reveal);
        RectF panel = new RectF(left, top, left + panelWidth, top + panelHeight);

        paint.setColor(withAlpha(Color.BLACK, reveal * 0.52f));
        canvas.drawRoundRect(panel.left + dp(5), panel.top + dp(8), panel.right + dp(8),
                panel.bottom + dp(10), dp(12), dp(12), paint);
        paint.setShader(new LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
                new int[]{0xff1a1b19, 0xff0d0f0e, 0xff131411},
                new float[]{0f, 0.56f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(panel, dp(12), dp(12), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(0x55c9c0ad);
        canvas.drawRoundRect(panel, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.FILL);
        addHit(ACTION_SETTINGS, panel, -1); // absorb taps on the modal's empty areas

        float contentLeft = panel.left + dp(18);
        float contentRight = panel.right - dp(18);
        float y = panel.top + dp(25);
        drawText(canvas, "SIGNAL CHAIN", contentLeft, y, dp(8.2f), INK,
                labelFace, Paint.Align.LEFT, 1.55f);
        y += dp(13);
        drawText(canvas, "MACHINE  ×  STOCK  ×  REC LEVEL  ×  UNIT", contentLeft, y,
                dp(4.9f), MUTED_INK, labelFace, Paint.Align.LEFT, 1.05f);

        RectF close = new RectF(panel.right - dp(45), panel.top + dp(9),
                panel.right - dp(11), panel.top + dp(43));
        drawRoundIcon(canvas, close, "×");
        addHit(ACTION_SETTINGS_CLOSE, close, -1);

        y += dp(14);
        float tabGap = dp(3);
        float tabWidth = (contentRight - contentLeft - tabGap * 3f) / 4f;
        RectF machineTab = new RectF(contentLeft, y, contentLeft + tabWidth, y + dp(22));
        RectF tapeTab = new RectF(machineTab.right + tabGap, y,
                machineTab.right + tabGap + tabWidth, y + dp(22));
        RectF levelTab = new RectF(tapeTab.right + tabGap, y,
                tapeTab.right + tabGap + tabWidth, y + dp(22));
        RectF conditionTab = new RectF(levelTab.right + tabGap, y,
                contentRight, y + dp(22));
        drawSettingsTab(canvas, machineTab, "MACHINE", settingsSection == 0);
        drawSettingsTab(canvas, tapeTab, "STOCK", settingsSection == 1);
        drawSettingsTab(canvas, levelTab, "REC LEVEL", settingsSection == 2);
        drawSettingsTab(canvas, conditionTab, "CONDITION", settingsSection == 3);
        addHit(ACTION_SETTINGS_SECTION, machineTab, 0);
        addHit(ACTION_SETTINGS_SECTION, tapeTab, 1);
        addHit(ACTION_SETTINGS_SECTION, levelTab, 2);
        addHit(ACTION_SETTINGS_SECTION, conditionTab, 3);
        y = machineTab.bottom + dp(8);

        if (settingsSection == 0) {
            drawMachineProfileCards(canvas, contentLeft, contentRight, y,
                    panel.bottom - dp(31));
        } else if (settingsSection == 1) {
            drawTapeProfileCards(canvas, contentLeft, contentRight, y,
                    panel.bottom - dp(31));
        } else if (settingsSection == 2) {
            drawRecordLevelCards(canvas, contentLeft, contentRight, y,
                    panel.bottom - dp(31));
        } else {
            drawConditionProfileCards(canvas, contentLeft, contentRight, y,
                    panel.bottom - dp(31));
        }

        String settingsFooter;
        if (settingsSection == 0) {
            settingsFooter = profile.isSonyWmF2015()
                    ? "MACHINE PATH · RADIO BYPASSED / NO DOLBY"
                    : profile.isSonyWmD6c()
                    ? "MACHINE PATH · QUARTZ SERVO / DOLBY " + dolbyMode.label
                    : profile.isAiwaHsJx707()
                    ? "MACHINE PATH · DOLBY " + dolbyMode.label + " / DSL + BBE OFF"
                    : "MACHINE PATH · TONE / OUTPUT STAGE";
        } else if (settingsSection == 1) {
            settingsFooter = "TAPE PATH · EQ / MOL / SOL / PARTICLE NOISE";
        } else if (settingsSection == 2) {
            settingsFooter = "RECORD GAIN · HEADROOM TRADED FOR NOISE";
        } else {
            settingsFooter = "TOLERANCE ONLY · NO DROPOUT / CRACKLE / DAMAGE";
        }
        drawText(canvas, settingsFooter,
                contentLeft, panel.bottom - dp(23), dp(4.05f), 0xff8a8273,
                labelFace, Paint.Align.LEFT, 0.78f);
        drawText(canvas, settingsSection == 3
                        ? "HEALTHY UNITS · CONTINUOUS, BOUNDED VARIATION"
                        : "LIVE SWITCH · REBUILDS FROM THE CURRENT PLAYHEAD",
                contentLeft, panel.bottom - dp(12), dp(4.1f), 0xff6f6b62,
                labelFace, Paint.Align.LEFT, 0.82f);
    }

    private void drawSettingsTab(Canvas canvas, RectF tab, String text, boolean selected) {
        paint.setColor(selected ? 0x24e95c2c : 0x261f211f);
        canvas.drawRoundRect(tab, dp(4), dp(4), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.65f));
        paint.setColor(selected ? 0x99e95c2c : 0x3e8b877d);
        canvas.drawRoundRect(tab, dp(4), dp(4), paint);
        paint.setStyle(Paint.Style.FILL);
        drawText(canvas, text, tab.centerX(), tab.centerY() + dp(2.1f), dp(5.2f),
                selected ? 0xffef805a : MUTED_INK, labelFace, Paint.Align.CENTER, 1.05f);
    }

    private void drawMachineProfileCards(Canvas canvas,
                                         float contentLeft,
                                         float contentRight,
                                         float top,
                                         float bottom) {
        List<TapeMachineProfile> profiles = TapeMachineProfile.availableProfiles();
        float gap = dp(profiles.size() > 3 ? 2f : 8f);
        float cardHeight = (bottom - top - gap * (profiles.size() - 1)) / profiles.size();
        cardHeight = Math.min(dp(88), cardHeight);
        float y = top;
        for (int index = 0; index < profiles.size(); index++) {
            TapeMachineProfile candidate = profiles.get(index);
            boolean selected = candidate.id.equals(profile.id);
            int accent = candidate.isAiwaHsJx707() ? 0xffc7b47f
                    : candidate.isSonyWmD6c() ? 0xffd5ae43
                    : candidate.isSonyWmF2015() ? 0xffc8ccca : ORANGE;
            RectF card = new RectF(contentLeft, y,
                    contentRight, y + cardHeight);
            drawSettingsCardFrame(canvas, card, selected, accent);

            boolean compact = cardHeight < dp(59);
            float cardLeft = card.left + dp(12);
            float cardRight = card.right - dp(12);
            float cardY = card.top + dp(compact ? 8.5f : 15f);
            drawText(canvas, candidate.manufacturer + "  /  " + candidate.year,
                    cardLeft, cardY, dp(compact ? 4.0f : 4.8f),
                    selected ? accent : MUTED_INK,
                    labelFace, Paint.Align.LEFT, 1.05f);
            cardY += dp(compact ? 11f : 16f);
            drawText(canvas, candidate.model, cardLeft, cardY,
                    dp(compact ? 8.5f : 12.2f), INK,
                    displayFace, Paint.Align.LEFT, 0.45f);
            cardY += dp(compact ? 10f : 13f);
            drawEllipsizedText(canvas, candidate.calibrationBasis, cardLeft, cardY,
                    cardRight - cardLeft - dp(2), dp(compact ? 3.6f : 4.5f), 0xffa49c8c,
                    labelFace, Paint.Align.LEFT);
            cardY += dp(compact ? 9.5f : 13f);
            String spec = candidate.frequencySpec(true) + "   ·   "
                    + (candidate.isAiwaHsJx707()
                    ? "<0.45% RMS SERVICE LIMIT" : candidate.wowFlutterSpec);
            drawEllipsizedText(canvas, spec, cardLeft, cardY,
                    cardRight - cardLeft - dp(2), dp(compact ? 3.45f : 4.35f), 0xff716d64,
                    condensedFace, Paint.Align.LEFT);

            if (selected) {
                float checkX = card.right - dp(18);
                float checkY = card.top + dp(18);
                paint.setColor(accent);
                canvas.drawCircle(checkX, checkY, dp(6.5f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(dp(1.2f));
                paint.setColor(0xff10110f);
                path.reset();
                path.moveTo(checkX - dp(3), checkY);
                path.lineTo(checkX - dp(0.7f), checkY + dp(2.3f));
                path.lineTo(checkX + dp(3.4f), checkY - dp(2.4f));
                canvas.drawPath(path, paint);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStyle(Paint.Style.FILL);
            }
            addHit(ACTION_MACHINE_PROFILE, card, index);
            y = card.bottom + gap;
        }
    }

    private void drawTapeProfileCards(Canvas canvas,
                                      float contentLeft,
                                      float contentRight,
                                      float top,
                                      float bottom) {
        List<TapeStockProfile> profiles = TapeStockProfile.availableProfiles();
        float gap = dp(6);
        float cardHeight = (bottom - top - gap * (profiles.size() - 1)) / profiles.size();
        float y = top;
        for (int index = 0; index < profiles.size(); index++) {
            TapeStockProfile candidate = profiles.get(index);
            boolean selected = candidate.id.equals(tapeProfile.id);
            int accent = candidate.accentColor;
            RectF card = new RectF(contentLeft, y, contentRight, y + cardHeight);
            drawSettingsCardFrame(canvas, card, selected, accent);

            float cardLeft = card.left + dp(12);
            float cardRight = card.right - dp(12);
            drawText(canvas, candidate.manufacturer + "  /  " + candidate.year,
                    cardLeft, card.top + dp(12), dp(4.5f), selected ? accent : MUTED_INK,
                    labelFace, Paint.Align.LEFT, 1.0f);
            drawText(canvas, candidate.typeShortLabel(), cardRight, card.top + dp(12),
                    dp(4.4f), selected ? accent : 0xff78736a,
                    labelFace, Paint.Align.RIGHT, 0.95f);
            drawText(canvas, candidate.model, cardLeft, card.top + dp(28), dp(11.2f), INK,
                    displayFace, Paint.Align.LEFT, 0.42f);
            drawEllipsizedText(canvas, candidate.character, cardLeft, card.top + dp(41),
                    cardRight - cardLeft, dp(4.25f), 0xffa49c8c,
                    labelFace, Paint.Align.LEFT);
            drawEllipsizedText(canvas, candidate.compactSpec(), cardLeft, card.top + dp(53),
                    cardRight - cardLeft, dp(4.0f), 0xff706c64,
                    condensedFace, Paint.Align.LEFT);
            addHit(ACTION_TAPE_PROFILE, card, index);
            y = card.bottom + gap;
        }
    }

    /**
     * Record level cards, laid out more tightly than the others because there are four of them.
     */
    private void drawRecordLevelCards(Canvas canvas,
                                      float contentLeft,
                                      float contentRight,
                                      float top,
                                      float bottom) {
        List<RecordLevelProfile> levels = RecordLevelProfile.availableProfiles();
        float gap = dp(5);
        float cardHeight = (bottom - top - gap * (levels.size() - 1)) / levels.size();
        float y = top;
        for (int index = 0; index < levels.size(); index++) {
            RecordLevelProfile candidate = levels.get(index);
            boolean selected = candidate.id.equals(recordLevel.id);
            int accent = candidate.accentColor;
            RectF card = new RectF(contentLeft, y, contentRight, y + cardHeight);
            drawSettingsCardFrame(canvas, card, selected, accent);

            float cardLeft = card.left + dp(12);
            float cardRight = card.right - dp(12);
            drawText(canvas, candidate.levelLabel, cardLeft, card.top + dp(11),
                    dp(4.5f), selected ? accent : MUTED_INK,
                    labelFace, Paint.Align.LEFT, 1.0f);
            drawText(canvas, candidate.compactSpec(), cardRight, card.top + dp(11),
                    dp(4.0f), selected ? accent : 0xff78736a,
                    condensedFace, Paint.Align.RIGHT, 0.6f);
            drawText(canvas, candidate.name, cardLeft, card.top + dp(25),
                    dp(9.4f), INK, displayFace, Paint.Align.LEFT, 0.42f);
            drawEllipsizedText(canvas, candidate.character, cardLeft, card.top + dp(37),
                    cardRight - cardLeft, dp(4.15f), 0xffa49c8c,
                    labelFace, Paint.Align.LEFT);
            addHit(ACTION_RECORD_LEVEL, card, index);
            y = card.bottom + gap;
        }
    }

    private void drawConditionProfileCards(Canvas canvas,
                                           float contentLeft,
                                           float contentRight,
                                           float top,
                                           float bottom) {
        List<MachineConditionProfile> profiles =
                MachineConditionProfile.availableProfiles();
        float gap = dp(6);
        float cardHeight = (bottom - top - gap * (profiles.size() - 1)) / profiles.size();
        float y = top;
        for (int index = 0; index < profiles.size(); index++) {
            MachineConditionProfile candidate = profiles.get(index);
            boolean selected = candidate.id.equals(conditionProfile.id);
            int accent = candidate.accentColor;
            RectF card = new RectF(contentLeft, y, contentRight, y + cardHeight);
            drawSettingsCardFrame(canvas, card, selected, accent);

            float cardLeft = card.left + dp(12);
            float cardRight = card.right - dp(12);
            drawText(canvas, candidate.levelLabel, cardLeft, card.top + dp(12),
                    dp(4.5f), selected ? accent : MUTED_INK,
                    labelFace, Paint.Align.LEFT, 1.0f);
            drawText(canvas, candidate.isCalibrated() ? "0" : "CHARACTER",
                    cardRight, card.top + dp(12), dp(4.4f),
                    selected ? accent : 0xff78736a,
                    labelFace, Paint.Align.RIGHT, 0.95f);
            drawText(canvas, candidate.name, cardLeft, card.top + dp(28),
                    dp(11.2f), INK, displayFace, Paint.Align.LEFT, 0.42f);
            drawEllipsizedText(canvas, candidate.character, cardLeft, card.top + dp(41),
                    cardRight - cardLeft, dp(4.25f), 0xffa49c8c,
                    labelFace, Paint.Align.LEFT);
            drawEllipsizedText(canvas, candidate.compactSpec, cardLeft, card.top + dp(53),
                    cardRight - cardLeft, dp(4.0f), 0xff706c64,
                    condensedFace, Paint.Align.LEFT);
            addHit(ACTION_CONDITION_PROFILE, card, index);
            y = card.bottom + gap;
        }
    }

    private void drawSettingsCardFrame(Canvas canvas,
                                       RectF card,
                                       boolean selected,
                                       int accent) {
        paint.setColor(selected ? withAlpha(accent, 0.13f) : 0x401f211f);
        canvas.drawRoundRect(card, dp(6), dp(6), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(selected ? dp(1.05f) : dp(0.65f));
        paint.setColor(selected ? withAlpha(accent, 0.88f) : 0x3e8b877d);
        canvas.drawRoundRect(card, dp(6), dp(6), paint);
        paint.setStyle(Paint.Style.FILL);
        if (selected) {
            paint.setColor(accent);
            canvas.drawRoundRect(card.left, card.top + dp(7), card.left + dp(2.2f),
                    card.bottom - dp(7), dp(1.1f), dp(1.1f), paint);
        }
    }

    private void drawPill(Canvas canvas, RectF rect, String text, int fill, int textColor) {
        paint.setColor(fill);
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(0x385e5b53);
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint);
        paint.setStyle(Paint.Style.FILL);
        drawText(canvas, text, rect.centerX(), rect.centerY() + dp(2.8f), dp(6.5f),
                textColor, labelFace, Paint.Align.CENTER, 1f);
    }

    private void drawAmbientGlow(Canvas canvas, float cx, float cy, float radius, int color) {
        paint.setAlpha(255);
        paint.setShader(new RadialGradient(cx, cy, radius,
                new int[]{color, color & 0x00ffffff}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }

    private void drawFineGrain(Canvas canvas, int color, float strength) {
        int width = getWidth();
        int height = getHeight();
        texturePaint.setColor(withAlpha(color, strength));
        for (int i = 0; i < 128; i++) {
            float x = Math.floorMod(i * 83 + 19, Math.max(1, width));
            float y = Math.floorMod(i * i * 31 + 47, Math.max(1, height));
            canvas.drawPoint(x, y, texturePaint);
        }
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF target) {
        float scale = Math.max(target.width() / bitmap.getWidth(), target.height() / bitmap.getHeight());
        float dx = target.centerX() - bitmap.getWidth() * scale / 2f;
        float dy = target.centerY() - bitmap.getHeight() * scale / 2f;
        bitmapMatrix.reset();
        bitmapMatrix.setScale(scale, scale);
        bitmapMatrix.postTranslate(dx, dy);
        paint.setAlpha(255);
        canvas.drawBitmap(bitmap, bitmapMatrix, paint);
    }

    private float drawParagraph(Canvas canvas,
                                String text,
                                float x,
                                float firstBaseline,
                                float maxWidth,
                                float textSize,
                                int color,
                                android.graphics.Typeface typeface,
                                float lineHeight,
                                int maxLines) {
        configureText(textSize, color, typeface, Paint.Align.LEFT, 0);
        float y = firstBaseline;
        int lines = 0;
        if (text == null) {
            return y;
        }
        String[] paragraphs = text.split("\\n", -1);
        for (String paragraph : paragraphs) {
            if (lines >= maxLines) {
                break;
            }
            if (paragraph.isEmpty()) {
                y += lineHeight;
                lines++;
                continue;
            }
            int start = 0;
            while (start < paragraph.length() && lines < maxLines) {
                int count = textPaint.breakText(paragraph, start, paragraph.length(),
                        true, maxWidth, null);
                if (count <= 0) {
                    break;
                }
                int end = start + count;
                if (end < paragraph.length()) {
                    int space = paragraph.lastIndexOf(' ', end - 1);
                    if (space > start + Math.max(2, count / 2)) {
                        end = space;
                    }
                }
                canvas.drawText(paragraph, start, end, x, y, textPaint);
                y += lineHeight;
                lines++;
                start = end;
                while (start < paragraph.length() && paragraph.charAt(start) == ' ') {
                    start++;
                }
            }
        }
        return y;
    }

    private void drawText(Canvas canvas,
                          String text,
                          float x,
                          float baseline,
                          float size,
                          int color,
                          android.graphics.Typeface typeface,
                          Paint.Align align,
                          float letterSpacing) {
        configureText(size, color, typeface, align, letterSpacing);
        canvas.drawText(text == null ? "" : text, x, baseline, textPaint);
    }

    private void drawEllipsizedText(Canvas canvas,
                                    String text,
                                    float x,
                                    float baseline,
                                    float maxWidth,
                                    float size,
                                    int color,
                                    android.graphics.Typeface typeface,
                                    Paint.Align align) {
        configureText(size, color, typeface, align, 0);
        String safe = text == null ? "" : text;
        if (textPaint.measureText(safe) > maxWidth) {
            // Reuse one TextPaint. Allocating a copy per label meant a native paint object was
            // created and finalised for every line of the info panel, every frame.
            ellipsisPaint.set(textPaint);
            safe = TextUtils.ellipsize(safe, ellipsisPaint, maxWidth,
                    TextUtils.TruncateAt.END).toString();
        }
        canvas.drawText(safe, x, baseline, textPaint);
    }

    private void configureText(float size,
                               int color,
                               android.graphics.Typeface typeface,
                               Paint.Align align,
                               float letterSpacing) {
        // Only cross into the native paint for values that actually changed. A player frame draws
        // dozens of strings that mostly share a size, face and colour, so skipping the redundant
        // setters keeps Skia's glyph strike valid instead of rebuilding it for every label.
        if (textPaintSize != size) {
            textPaintSize = size;
            textPaint.setTextSize(size);
        }
        if (textPaintColor != color) {
            textPaintColor = color;
            textPaint.setColor(color);
        }
        if (textPaintFace != typeface) {
            textPaintFace = typeface;
            textPaint.setTypeface(typeface);
        }
        if (textPaintAlign != align) {
            textPaintAlign = align;
            textPaint.setTextAlign(align);
        }
        float spacing = letterSpacing / 10f;
        if (textPaintLetterSpacing != spacing) {
            textPaintLetterSpacing = spacing;
            textPaint.setLetterSpacing(spacing);
        }
    }

    private void addHit(String action, RectF rect, int index) {
        hitTargets.add(new HitTarget(action, new RectF(rect), index));
    }

    private HitTarget findTarget(float x, float y) {
        for (int i = hitTargets.size() - 1; i >= 0; i--) {
            HitTarget target = hitTargets.get(i);
            if (target.rect.contains(x, y)) {
                return target;
            }
        }
        return null;
    }

    private HitTarget findTargetByAction(String action) {
        for (int i = hitTargets.size() - 1; i >= 0; i--) {
            HitTarget target = hitTargets.get(i);
            if (action.equals(target.action)) {
                return target;
            }
        }
        return null;
    }

    private static boolean isMiniPlayerAction(String action) {
        return ACTION_MINI_PLAYER.equals(action)
                || ACTION_MINI_PLAY.equals(action)
                || ACTION_MINI_NEXT.equals(action);
    }

    private void restartScene() {
        sceneReveal = 0f;
        caseOpen = 0f;
        lastFrameMs = SystemClock.uptimeMillis();
        animateNextFrame();
    }

    private void animateNextFrame() {
        postInvalidateOnAnimation();
    }

    private void schedulePlaybackFrame() {
        if (playbackFramePosted) {
            return;
        }
        playbackFramePosted = true;
        postDelayed(playbackFrame, PLAYER_FRAME_INTERVAL_MS);
    }

    private float dp(float value) {
        return value * density;
    }

    private static float approach(float value, float target, float amount) {
        if (Math.abs(target - value) <= amount) {
            return target;
        }
        return value + Math.signum(target - value) * amount;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    private static float easeOut(float value) {
        float p = clamp(value, 0f, 1f);
        return 1f - (float) Math.pow(1f - p, 3);
    }

    private static float easeInOut(float value) {
        float p = clamp(value, 0f, 1f);
        return p < 0.5f ? 4f * p * p * p : 1f - (float) Math.pow(-2f * p + 2f, 3) / 2f;
    }

    private static RectF inset(RectF rect, float amount) {
        return new RectF(rect.left + amount, rect.top + amount,
                rect.right - amount, rect.bottom - amount);
    }

    private static RectF expand(RectF rect, float amount) {
        return new RectF(rect.left - amount, rect.top - amount,
                rect.right + amount, rect.bottom + amount);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * clamp(alpha, 0f, 1f));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int lighten(int color, float amount) {
        return Color.rgb(
                (int) lerp(Color.red(color), 255, amount),
                (int) lerp(Color.green(color), 255, amount),
                (int) lerp(Color.blue(color), 255, amount));
    }

    private static int darken(int color, float amount) {
        return Color.rgb(
                (int) lerp(Color.red(color), 0, amount),
                (int) lerp(Color.green(color), 0, amount),
                (int) lerp(Color.blue(color), 0, amount));
    }

    private static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static final class HitTarget {
        final String action;
        final RectF rect;
        final int index;

        HitTarget(String action, RectF rect, int index) {
            this.action = action;
            this.rect = rect;
            this.index = index;
        }
    }
}
