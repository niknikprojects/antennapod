package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import de.danoeh.antennapod.event.playback.MusicBreakEvent;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.MusicBreakPreferences;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits each episode into parts of roughly the configured interval and plays a random local
 * music track between them, preferably at a chapter start near each break point, otherwise in
 * a jingle, a stereo sting or else a pause of the speaker found by decoding the surroundings of the
 * break point in advance.
 * Without a natural pause the episode stops at the break point. When the episode has chapters or
 * timecodes in the show notes, every topic change gets a track, at the chapter start or in the longest
 * pause near the timecode, and only topics longer than the interval are split further.
 * Chapters that look like ads are replaced by a track. The end of an episode counts
 * as a break when the next episode starts automatically. Pauses are scheduled as player messages,
 * so they happen exactly at the position.
 * The music volume is lowered to the measured level of the episode (or a typical podcast level).
 * Tracks are submitted to ListenBrainz when a user token is set.
 */
@OptIn(markerClass = UnstableApi.class)
public class MusicBreak {
    private static final String TAG = "MusicBreak";
    public static final long MAX_TRACK_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long PROGRESS_UPDATE_MS = 500;
    private static final long MAX_WINDOW_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long MIN_LEAD_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long CHAPTER_TOLERANCE_MS = 2000;
    private static final long NO_SEEK = -1;
    private static final long SCAN_LEAD_MS = 30_000;
    private static final long AD_LOOKAHEAD_MS = 30_000;
    private static final long MAX_PAUSE_OFFSET_MS = 300;
    private static final long MAX_SCORED_SILENCE_MS = 2000;
    private static final double DISTANCE_PENALTY_MS = 150;
    private static final double MARKER_SCORE = 10_000;
    private static final long EPISODE_LEVEL_SAMPLE_MS = 60_000;
    private static final double DEFAULT_EPISODE_LEVEL_DB = -20;
    private static final float MIN_MUSIC_VOLUME = 0.05f;
    private static final long LISTEN_MIN_MS = TimeUnit.MINUTES.toMillis(4);
    private static final Pattern AD_CHAPTER = Pattern.compile(
            "\\b(ad|ads|ad break|advert|adverts|advertisement|advertising|sponsor|sponsors|sponsored|"
                    + "sponsorship|annons|annonser|reklam|werbung|anzeige)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIMECODE = Pattern.compile("\\b(?:(\\d+):)?(\\d+):(\\d{2})\\b");
    private static final int MIN_NOTE_TIMECODES = 2;
    private static final long TIMECODE_WINDOW_MS = 15_000;

    private final Context context;
    private final Player episodePlayer;
    private final ExoPlayer exoPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdate = this::updateProgress;
    private final List<Long> breakPoints = new ArrayList<>();
    private final Set<Long> handledAdStarts = new HashSet<>();
    private final List<Long> noteTimecodes = new ArrayList<>();
    private final Set<Long> timecodePoints = new HashSet<>();
    private long plannedMediaId = -1;
    private long windowMs = 0;
    private boolean active = false;
    private long resumePosition = NO_SEEK;
    @Nullable
    private ExoPlayer musicPlayer;
    @Nullable
    private Disposable pickerDisposable;
    @Nullable
    private Uri lastTrack;
    @Nullable
    private MusicTrack preparedTrack;
    @Nullable
    private Disposable prepareDisposable;
    private double episodeLevelDb = Double.NaN;
    @Nullable
    private Disposable levelDisposable;
    @Nullable
    private Disposable notesDisposable;
    private long scanPoint = NO_SEEK;
    @Nullable
    private Disposable scanDisposable;
    private List<SilenceScanner.Silence> silences = new ArrayList<>();
    @Nullable
    private SilenceScanner.Silence silenceTarget;
    @Nullable
    private PlayerMessage pendingPause;
    private long pendingPausePosition = NO_SEEK;
    @Nullable
    private MusicTrack track;
    private long trackStartedAtMs;
    private boolean playingNowSubmitted = false;
    @Nullable
    private Listener listener;

    public interface Listener {
        void onMusicBreakChanged();
    }

    public MusicBreak(@NonNull Context context, @NonNull Player episodePlayer, @NonNull ExoPlayer exoPlayer) {
        this.context = context.getApplicationContext();
        this.episodePlayer = episodePlayer;
        this.exoPlayer = exoPlayer;
    }

    public boolean isActive() {
        return active;
    }

    public void onPlaybackTick(@Nullable FeedMedia media) {
        if (active || !canBreak(media) || !episodePlayer.isPlaying()) {
            return;
        }
        long position = episodePlayer.getCurrentPosition();
        if (media.getId() != plannedMediaId) {
            noteTimecodes.clear();
            planBreaks(media, position, episodePlayer.getDuration());
            measureEpisodeLevel(media, episodePlayer.getDuration());
            loadNoteTimecodes(media, episodePlayer.getDuration());
        }
        prepareNextTrack();
        while (!breakPoints.isEmpty() && breakPoints.get(0) + getWindow(breakPoints.get(0)) < position) {
            breakPoints.remove(0);
            resetSilenceSearch();
        }
        if (MusicBreakPreferences.isReplaceAds(context) && handleAdChapters(media, position)) {
            return;
        }
        if (breakPoints.isEmpty()) {
            return;
        }
        long point = breakPoints.get(0);
        long window = getWindow(point);
        if (position < point - window - SCAN_LEAD_MS) {
            return;
        }
        long chapterStart = findChapterStart(media, Math.max(point - window, position - CHAPTER_TOLERANCE_MS),
                point + window, point);
        if (chapterStart != NO_SEEK) {
            if (position < chapterStart) {
                schedulePause(media.getId(), chapterStart, () -> {
                    Log.d(TAG, "Music break at chapter start " + chapterStart);
                    consumeBreakPoint();
                    start(chapterStart);
                });
            } else if (pendingPausePosition != chapterStart || position >= chapterStart + CHAPTER_TOLERANCE_MS) {
                Log.d(TAG, "Music break after chapter start " + chapterStart);
                consumeBreakPoint();
                start(chapterStart);
            }
            return;
        }
        if (scanPoint != point) {
            startSilenceScan(media, point, Math.max(point - window, position));
            return;
        }
        if (scanDisposable != null) {
            return;
        }
        if (silenceTarget != null) {
            if (position >= silenceTarget.endMs) {
                chooseSilenceTarget(media.getId(), point);
            }
            return;
        }
        if (position >= point) {
            Log.d(TAG, "Music break without natural pause at " + position);
            consumeBreakPoint();
            start(NO_SEEK);
        }
    }

    private boolean handleAdChapters(FeedMedia media, long position) {
        List<Chapter> chapters = media.getChapters();
        if (chapters == null) {
            return false;
        }
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            long adStart = chapter.getStart();
            long adEnd = i + 1 < chapters.size() ? chapters.get(i + 1).getStart() : episodePlayer.getDuration();
            if (chapter.getTitle() == null || !AD_CHAPTER.matcher(chapter.getTitle()).find()
                    || adEnd <= adStart || handledAdStarts.contains(adStart)
                    || position < adStart - AD_LOOKAHEAD_MS || position >= adStart + CHAPTER_TOLERANCE_MS) {
                continue;
            }
            if (position >= adStart) {
                replaceAd(adStart, adEnd);
            } else {
                schedulePause(media.getId(), adStart, () -> replaceAd(adStart, adEnd));
            }
            return true;
        }
        return false;
    }

    private void replaceAd(long adStart, long adEnd) {
        Log.d(TAG, "Replacing ad chapter " + adStart + "-" + adEnd + " with music");
        handledAdStarts.add(adStart);
        if (!breakPoints.isEmpty() && Math.abs(breakPoints.get(0) - adStart) <= windowMs + SCAN_LEAD_MS) {
            breakPoints.remove(0);
        }
        resetSilenceSearch();
        start(adEnd);
    }

    private void schedulePause(long mediaId, long positionMs, Runnable action) {
        if (pendingPause != null && pendingPausePosition == positionMs) {
            return;
        }
        cancelPendingPause();
        pendingPausePosition = positionMs;
        pendingPause = exoPlayer.createMessage((messageType, payload) -> {
            pendingPause = null;
            pendingPausePosition = NO_SEEK;
            if (!active && mediaId == plannedMediaId && episodePlayer.isPlaying()) {
                action.run();
            }
        })
                .setLooper(Looper.getMainLooper())
                .setPosition(positionMs)
                .setDeleteAfterDelivery(true)
                .send();
        Log.d(TAG, "Scheduled pause at " + positionMs);
    }

    private void cancelPendingPause() {
        if (pendingPause != null) {
            pendingPause.cancel();
            pendingPause = null;
        }
        pendingPausePosition = NO_SEEK;
    }

    private void startSilenceScan(FeedMedia media, long point, long from) {
        resetSilenceSearch();
        scanPoint = point;
        long to = point + getWindow(point);
        long mediaId = media.getId();
        scanDisposable = Single.fromCallable(() -> SilenceScanner.scan(context, media, from, to))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    scanDisposable = null;
                    silences = result;
                    chooseSilenceTarget(mediaId, point);
                }, error -> {
                    scanDisposable = null;
                    Log.e(TAG, "Silence scan failed", error);
                });
    }

    private void chooseSilenceTarget(long mediaId, long point) {
        cancelPendingPause();
        silenceTarget = null;
        long position = episodePlayer.getCurrentPosition();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (SilenceScanner.Silence silence : silences) {
            if (getPauseAt(silence) <= position + MAX_PAUSE_OFFSET_MS) {
                continue;
            }
            double distance = Math.min(1.0,
                    Math.abs((silence.startMs + silence.endMs) / 2 - point) / (double) Math.max(getWindow(point), 1));
            double score = silence.kind * MARKER_SCORE
                    + Math.min(silence.getLength(), MAX_SCORED_SILENCE_MS) - DISTANCE_PENALTY_MS * distance;
            if (score > bestScore) {
                bestScore = score;
                silenceTarget = silence;
            }
        }
        Log.d(TAG, "Silence target for break at " + point + ": " + silenceTarget);
        if (silenceTarget != null) {
            SilenceScanner.Silence target = silenceTarget;
            schedulePause(mediaId, getPauseAt(target), () -> {
                Log.d(TAG, "Music break at silence " + target);
                consumeBreakPoint();
                start(NO_SEEK);
            });
        }
    }

    private static long getPauseAt(SilenceScanner.Silence silence) {
        if (silence.kind != SilenceScanner.KIND_PAUSE) {
            return silence.startMs;
        }
        return silence.startMs + Math.min(MAX_PAUSE_OFFSET_MS, silence.getLength() / 3);
    }

    private void consumeBreakPoint() {
        if (!breakPoints.isEmpty()) {
            breakPoints.remove(0);
        }
        resetSilenceSearch();
    }

    private void resetSilenceSearch() {
        if (scanDisposable != null) {
            scanDisposable.dispose();
            scanDisposable = null;
        }
        cancelPendingPause();
        scanPoint = NO_SEEK;
        silences = new ArrayList<>();
        silenceTarget = null;
    }

    public void startBetweenEpisodes() {
        if (active) {
            return;
        }
        Log.d(TAG, "Music break between episodes");
        start(NO_SEEK);
    }

    public boolean canBreak(@Nullable FeedMedia media) {
        return media != null && media.getMediaType() != MediaType.VIDEO
                && MusicBreakPreferences.isEnabled(context)
                && episodePlayer.getDeviceInfo().playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE;
    }

    private void planBreaks(FeedMedia media, long position, long duration) {
        plannedMediaId = media.getId();
        breakPoints.clear();
        timecodePoints.clear();
        handledAdStarts.clear();
        resetSilenceSearch();
        long interval = MusicBreakPreferences.getIntervalMillis(context);
        List<Long> topicStarts = findTopicStarts(media, duration);
        if (!topicStarts.isEmpty()) {
            planTopicBreaks(topicStarts, position, duration, interval);
            return;
        }
        int parts = Math.max(1, Math.round((float) duration / interval));
        long spacing = duration / parts;
        windowMs = Math.min(MAX_WINDOW_MS, spacing / 4);
        for (int i = 1; i < parts; i++) {
            long point = spacing * i;
            if (point - windowMs > position + MIN_LEAD_MS) {
                breakPoints.add(point);
            }
        }
        Log.d(TAG, "Planned music breaks at " + breakPoints + " (window " + windowMs + ")");
    }

    private void planTopicBreaks(List<Long> topicStarts, long position, long duration, long interval) {
        windowMs = Math.min(MAX_WINDOW_MS, interval / 4);
        long segmentStart = 0;
        for (int i = 0; i <= topicStarts.size(); i++) {
            long segmentEnd = i < topicStarts.size() ? topicStarts.get(i) : duration;
            int parts = Math.max(1, Math.round((float) (segmentEnd - segmentStart) / interval));
            for (int j = 1; j < parts; j++) {
                addBreakPoint(segmentStart + (segmentEnd - segmentStart) * j / parts, position);
            }
            if (i < topicStarts.size()) {
                if (noteTimecodes.contains(segmentEnd)) {
                    timecodePoints.add(segmentEnd);
                }
                addBreakPoint(segmentEnd, position);
            }
            segmentStart = segmentEnd;
        }
        Log.d(TAG, "Planned music breaks at topics " + topicStarts + ": " + breakPoints
                + " (window " + windowMs + ", timecodes " + timecodePoints + ")");
    }

    private void addBreakPoint(long point, long position) {
        if (point - getWindow(point) > position + MIN_LEAD_MS) {
            breakPoints.add(point);
        }
    }

    private long getWindow(long point) {
        return timecodePoints.contains(point) ? TIMECODE_WINDOW_MS : windowMs;
    }

    private List<Long> findTopicStarts(FeedMedia media, long duration) {
        Set<Long> starts = new TreeSet<>(noteTimecodes);
        List<Chapter> chapters = media.getChapters();
        if (chapters != null) {
            for (int i = 0; i < chapters.size(); i++) {
                if (isAdChapter(chapters.get(i)) || (i > 0 && isAdChapter(chapters.get(i - 1)))) {
                    continue;
                }
                starts.add(chapters.get(i).getStart());
            }
        }
        List<Long> topicStarts = new ArrayList<>();
        for (long start : starts) {
            if (start > 0 && start < duration) {
                topicStarts.add(start);
            }
        }
        return topicStarts;
    }

    private static boolean isAdChapter(Chapter chapter) {
        return chapter.getTitle() != null && AD_CHAPTER.matcher(chapter.getTitle()).find();
    }

    private void measureEpisodeLevel(FeedMedia media, long duration) {
        if (levelDisposable != null) {
            levelDisposable.dispose();
            levelDisposable = null;
        }
        episodeLevelDb = Double.NaN;
        if (!media.localFileAvailable()) {
            return;
        }
        String file = media.getLocalFileUrl();
        long from = Math.max(0, (duration - EPISODE_LEVEL_SAMPLE_MS) / 3);
        levelDisposable = Single.fromCallable(() -> SilenceScanner.measureLevel(context, file,
                        from, from + EPISODE_LEVEL_SAMPLE_MS))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(level -> {
                    levelDisposable = null;
                    episodeLevelDb = level;
                }, error -> {
                    levelDisposable = null;
                    Log.e(TAG, "Unable to measure episode level", error);
                });
    }

    private void loadNoteTimecodes(FeedMedia media, long duration) {
        if (notesDisposable != null) {
            notesDisposable.dispose();
            notesDisposable = null;
        }
        long mediaId = media.getId();
        long itemId = media.getItemId();
        notesDisposable = Single.fromCallable(() -> {
            FeedItem item = DBReader.getFeedItem(itemId);
            if (item == null) {
                return new ArrayList<Long>();
            }
            DBReader.loadDescriptionOfFeedItem(item);
            return parseTimecodes(item.getDescription(), duration);
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(timecodes -> {
                    notesDisposable = null;
                    if (mediaId == plannedMediaId && !timecodes.isEmpty()) {
                        Log.d(TAG, "Timecodes in show notes: " + timecodes);
                        noteTimecodes.addAll(timecodes);
                        planBreaks(media, episodePlayer.getCurrentPosition(), episodePlayer.getDuration());
                    }
                }, error -> {
                    notesDisposable = null;
                    Log.e(TAG, "Unable to read show notes", error);
                });
    }

    private static List<Long> parseTimecodes(@Nullable String notes, long duration) {
        List<Long> timecodes = new ArrayList<>();
        if (notes == null) {
            return timecodes;
        }
        Matcher matcher = TIMECODE.matcher(notes);
        while (matcher.find()) {
            long hours = matcher.group(1) != null ? Long.parseLong(matcher.group(1)) : 0;
            long minutes = Long.parseLong(matcher.group(2));
            long seconds = Long.parseLong(matcher.group(3));
            if (seconds >= 60 || (hours > 0 && minutes >= 60)) {
                continue;
            }
            long position = ((hours * 60 + minutes) * 60 + seconds) * 1000;
            if (position > 0 && position < duration && !timecodes.contains(position)) {
                timecodes.add(position);
            }
        }
        if (timecodes.size() < MIN_NOTE_TIMECODES) {
            timecodes.clear();
        }
        return timecodes;
    }

    private float getMusicVolume(MusicTrack musicTrack) {
        if (Double.isNaN(musicTrack.levelDb)) {
            return 1f;
        }
        double target = Double.isNaN(episodeLevelDb) ? DEFAULT_EPISODE_LEVEL_DB : episodeLevelDb;
        float volume = (float) Math.pow(10, (target - musicTrack.levelDb) / 20);
        volume = Math.max(MIN_MUSIC_VOLUME, Math.min(1f, volume * episodePlayer.getVolume()));
        Log.d(TAG, "Music level " + musicTrack.levelDb + " dB, episode " + target + " dB, volume " + volume);
        return volume;
    }

    private static long findChapterStart(FeedMedia media, long from, long to, long target) {
        List<Chapter> chapters = media.getChapters();
        if (chapters == null) {
            return NO_SEEK;
        }
        long best = NO_SEEK;
        for (Chapter chapter : chapters) {
            long start = chapter.getStart();
            if (start > 0 && start >= from && start <= to
                    && (best == NO_SEEK || Math.abs(start - target) < Math.abs(best - target))) {
                best = start;
            }
        }
        return best;
    }

    private void start(long resumeAt) {
        cancelPendingPause();
        active = true;
        resumePosition = resumeAt;
        track = null;
        episodePlayer.pause();
        notifyChanged();
        handler.post(progressUpdate);
        if (preparedTrack != null) {
            MusicTrack prepared = preparedTrack;
            preparedTrack = null;
            playTrack(prepared);
            return;
        }
        if (prepareDisposable != null) {
            // The prepared track starts as soon as it is loaded, see prepareNextTrack
            return;
        }
        pickerDisposable = loadNextTrack()
                .subscribe(this::playTrack, error -> {
                    Log.e(TAG, "Unable to pick track", error);
                    finish(true);
                }, () -> finish(true));
    }

    /**
     * Picks and loads the next track while the episode plays. Scanning the music folders and measuring
     * the level of the track takes seconds, which would otherwise be silence at the start of the break.
     */
    private void prepareNextTrack() {
        if (preparedTrack != null || prepareDisposable != null) {
            return;
        }
        prepareDisposable = loadNextTrack()
                .subscribe(loaded -> {
                    prepareDisposable = null;
                    if (active && track == null && musicPlayer == null) {
                        playTrack(loaded);
                    } else {
                        preparedTrack = loaded;
                    }
                }, error -> {
                    Log.e(TAG, "Unable to prepare track", error);
                    prepareDisposable = null;
                    if (active && track == null) {
                        finish(true);
                    }
                }, () -> {
                    prepareDisposable = null;
                    if (active && track == null) {
                        finish(true);
                    }
                });
    }

    private Maybe<MusicTrack> loadNextTrack() {
        Uri previous = lastTrack;
        return Maybe.fromCallable(() -> {
            MusicBreakTrackPicker.Pick pick = MusicBreakTrackPicker.pick(context, previous);
            if (pick == null) {
                return null;
            }
            MusicTrack loaded = MusicTrack.load(context, pick.uri);
            loaded.poolSize = pick.poolSize;
            loaded.leftInRound = pick.leftInRound;
            return loaded;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private void playTrack(MusicTrack newTrack) {
        pickerDisposable = null;
        if (!active) {
            return;
        }
        Log.d(TAG, "Playing " + newTrack.uri + " (" + newTrack.title + ")");
        track = newTrack;
        lastTrack = newTrack.uri;
        trackStartedAtMs = System.currentTimeMillis();
        playingNowSubmitted = false;
        ExoPlayer player = new ExoPlayer.Builder(context)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onEvents(@NonNull Player eventPlayer, @NonNull Player.Events events) {
                if (eventPlayer.getPlaybackState() == Player.STATE_ENDED) {
                    finish(true);
                } else {
                    if (!playingNowSubmitted && eventPlayer.isPlaying()) {
                        playingNowSubmitted = true;
                        long duration = eventPlayer.getDuration();
                        Schedulers.io().scheduleDirect(() -> ListenBrainz.submitPlayingNow(context, newTrack,
                                duration == C.TIME_UNSET ? 0 : duration));
                    }
                    notifyChanged();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "Unable to play " + newTrack.uri, error);
                finish(true);
            }
        });
        musicPlayer = player;
        player.setMediaItem(MediaItem.fromUri(newTrack.uri));
        player.prepare();
        player.play();
        player.setVolume(getMusicVolume(newTrack));
        notifyChanged();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @NonNull
    public MediaMetadata getMediaMetadata() {
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setTitle(track != null ? track.title : context.getString(R.string.music_break));
        if (track != null) {
            builder.setArtist(track.artist).setAlbumTitle(track.album);
            if (track.artwork != null) {
                builder.setArtworkData(track.artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
            }
        }
        return builder.build();
    }

    public boolean getMusicPlayWhenReady() {
        return musicPlayer == null || musicPlayer.getPlayWhenReady();
    }

    public int getMusicPlaybackState() {
        return musicPlayer == null ? Player.STATE_BUFFERING : musicPlayer.getPlaybackState();
    }

    public boolean isMusicPlaying() {
        return musicPlayer != null && musicPlayer.isPlaying();
    }

    public long getMusicPosition() {
        return musicPlayer == null ? 0 : musicPlayer.getCurrentPosition();
    }

    public long getMusicBufferedPosition() {
        return musicPlayer == null ? 0 : musicPlayer.getBufferedPosition();
    }

    public long getMusicDuration() {
        return musicPlayer == null ? C.TIME_UNSET : musicPlayer.getDuration();
    }

    public void playMusic() {
        if (musicPlayer != null) {
            musicPlayer.play();
        }
    }

    public void pauseMusic() {
        if (musicPlayer != null) {
            musicPlayer.pause();
        }
    }

    public void seekMusic(long positionMs) {
        if (musicPlayer != null) {
            musicPlayer.seekTo(positionMs);
        }
    }

    public void skip() {
        finish(true);
    }

    public void cancel() {
        finish(false);
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onMusicBreakChanged();
        }
    }

    private void updateProgress() {
        if (!active) {
            return;
        }
        if (getMusicPosition() >= MAX_TRACK_MS) {
            finish(true);
            return;
        }
        long duration = getMusicDuration();
        EventBus.getDefault().postSticky(MusicBreakEvent.playing(
                track != null ? track.title : context.getString(R.string.music_break),
                track != null ? track.getSubtitle() : "", track != null ? track.artwork : null,
                getMusicPosition(), duration == C.TIME_UNSET ? 0 : duration,
                getMusicPlayWhenReady()));
        handler.postDelayed(progressUpdate, PROGRESS_UPDATE_MS);
    }

    private void finish(boolean resumeEpisode) {
        if (!active) {
            return;
        }
        active = false;
        if (pickerDisposable != null) {
            pickerDisposable.dispose();
            pickerDisposable = null;
        }
        handler.removeCallbacks(progressUpdate);
        ExoPlayer player = musicPlayer;
        musicPlayer = null;
        if (player != null && track != null) {
            submitListen(player, track);
        }
        track = null;
        if (player != null) {
            handler.post(player::release);
        }
        EventBus.getDefault().removeStickyEvent(MusicBreakEvent.class);
        EventBus.getDefault().post(MusicBreakEvent.ended());
        notifyChanged();
        if (!resumeEpisode) {
            return;
        }
        if (resumePosition != NO_SEEK) {
            episodePlayer.seekTo(resumePosition);
        }
        episodePlayer.setPlayWhenReady(true);
    }

    private void submitListen(ExoPlayer player, MusicTrack listenedTrack) {
        long duration = player.getDuration() == C.TIME_UNSET ? 0 : player.getDuration();
        long played = player.getCurrentPosition();
        if (played < (duration > 0 ? Math.min(duration / 2, LISTEN_MIN_MS) : LISTEN_MIN_MS)) {
            Log.d(TAG, "Not a listen, played " + played + " of " + duration);
            return;
        }
        long listenedAt = trackStartedAtMs / 1000;
        Schedulers.io().scheduleDirect(() -> ListenBrainz.submitListen(context, listenedTrack, listenedAt, duration));
    }

    public void release() {
        resetSilenceSearch();
        if (levelDisposable != null) {
            levelDisposable.dispose();
            levelDisposable = null;
        }
        if (notesDisposable != null) {
            notesDisposable.dispose();
            notesDisposable = null;
        }
        if (prepareDisposable != null) {
            prepareDisposable.dispose();
            prepareDisposable = null;
        }
        preparedTrack = null;
        cancel();
    }
}
