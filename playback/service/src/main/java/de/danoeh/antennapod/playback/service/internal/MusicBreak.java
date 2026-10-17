package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import de.danoeh.antennapod.event.playback.MusicBreakEvent;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.storage.preferences.MusicBreakPreferences;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Splits each episode into parts of roughly the configured interval and plays a random local
 * music track between them, preferably at a chapter start near each break point, otherwise in
 * a pause of the speaker found by decoding the surroundings of the break point in advance.
 * The end of an episode counts as a break when the next episode starts automatically.
 */
public class MusicBreak {
    private static final String TAG = "MusicBreak";
    public static final long MAX_TRACK_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long PROGRESS_UPDATE_MS = 500;
    private static final long MAX_WINDOW_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long MIN_LEAD_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long CHAPTER_TOLERANCE_MS = 2000;
    private static final long NO_SEEK = -1;
    private static final long SCAN_LEAD_MS = 30_000;
    private static final long MAX_PAUSE_OFFSET_MS = 300;
    private static final long MAX_SCORED_SILENCE_MS = 2000;
    private static final double DISTANCE_PENALTY_MS = 150;

    private final Context context;
    private final Player episodePlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdate = this::updateProgress;
    private final Runnable silenceCheck = this::checkSilenceTarget;
    private final List<Long> breakPoints = new ArrayList<>();
    private long plannedMediaId = -1;
    private long windowMs = 0;
    private boolean active = false;
    private long resumePosition = NO_SEEK;
    private boolean rewindOnResume = false;
    @Nullable
    private ExoPlayer musicPlayer;
    @Nullable
    private Disposable pickerDisposable;
    @Nullable
    private Uri lastTrack;
    private long scanPoint = NO_SEEK;
    @Nullable
    private Disposable scanDisposable;
    private List<SilenceScanner.Silence> silences = new ArrayList<>();
    @Nullable
    private SilenceScanner.Silence silenceTarget;
    @Nullable
    private MusicTrack track;
    @Nullable
    private Listener listener;

    public interface Listener {
        void onMusicBreakChanged();
    }

    public MusicBreak(@NonNull Context context, @NonNull Player episodePlayer) {
        this.context = context.getApplicationContext();
        this.episodePlayer = episodePlayer;
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
            planBreaks(media.getId(), position, episodePlayer.getDuration());
        }
        while (!breakPoints.isEmpty() && breakPoints.get(0) + windowMs < position) {
            breakPoints.remove(0);
            resetSilenceSearch();
        }
        if (breakPoints.isEmpty()) {
            return;
        }
        long point = breakPoints.get(0);
        if (position < point - windowMs - SCAN_LEAD_MS) {
            return;
        }
        long chapterStart = findChapterStart(media, Math.max(point - windowMs, position - CHAPTER_TOLERANCE_MS),
                point + windowMs, point);
        if (chapterStart != NO_SEEK) {
            if (position >= chapterStart) {
                consumeBreakPoint();
                Log.d(TAG, "Music break at chapter start " + chapterStart);
                start(chapterStart, false);
            }
            return;
        }
        if (scanPoint != point) {
            startSilenceScan(media, point, Math.max(point - windowMs, position));
            return;
        }
        if (scanDisposable == null && silenceTarget == null && position >= point) {
            consumeBreakPoint();
            Log.d(TAG, "Music break without natural pause at " + position);
            start(NO_SEEK, true);
        }
    }

    private void startSilenceScan(FeedMedia media, long point, long from) {
        resetSilenceSearch();
        scanPoint = point;
        long to = point + windowMs;
        scanDisposable = Single.fromCallable(() -> SilenceScanner.scan(context, media, from, to))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    scanDisposable = null;
                    silences = result;
                    chooseSilenceTarget(point);
                }, error -> {
                    scanDisposable = null;
                    Log.e(TAG, "Silence scan failed", error);
                });
    }

    private void chooseSilenceTarget(long point) {
        handler.removeCallbacks(silenceCheck);
        silenceTarget = null;
        long position = episodePlayer.getCurrentPosition();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (SilenceScanner.Silence silence : silences) {
            if (getPauseAt(silence) <= position + MAX_PAUSE_OFFSET_MS) {
                continue;
            }
            double distance = Math.min(1.0,
                    Math.abs((silence.startMs + silence.endMs) / 2 - point) / (double) Math.max(windowMs, 1));
            double score = Math.min(silence.getLength(), MAX_SCORED_SILENCE_MS) - DISTANCE_PENALTY_MS * distance;
            if (score > bestScore) {
                bestScore = score;
                silenceTarget = silence;
            }
        }
        Log.d(TAG, "Silence target for break at " + point + ": " + silenceTarget);
        if (silenceTarget != null) {
            handler.post(silenceCheck);
        }
    }

    private void checkSilenceTarget() {
        if (active || silenceTarget == null) {
            return;
        }
        long position = episodePlayer.getCurrentPosition();
        long pauseAt = getPauseAt(silenceTarget);
        if (episodePlayer.isPlaying() && position >= pauseAt && position < silenceTarget.endMs) {
            Log.d(TAG, "Music break at silence " + silenceTarget);
            consumeBreakPoint();
            start(NO_SEEK, false);
            return;
        }
        if (position >= silenceTarget.endMs) {
            chooseSilenceTarget(scanPoint);
            return;
        }
        boolean far = !episodePlayer.isPlaying() || pauseAt - position > 3000;
        handler.postDelayed(silenceCheck, far ? 1000 : 50);
    }

    private static long getPauseAt(SilenceScanner.Silence silence) {
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
        handler.removeCallbacks(silenceCheck);
        scanPoint = NO_SEEK;
        silences = new ArrayList<>();
        silenceTarget = null;
    }

    public void startBetweenEpisodes() {
        if (active) {
            return;
        }
        Log.d(TAG, "Music break between episodes");
        start(NO_SEEK, false);
    }

    public boolean canBreak(@Nullable FeedMedia media) {
        return media != null && media.getMediaType() != MediaType.VIDEO
                && MusicBreakPreferences.isEnabled(context)
                && episodePlayer.getDeviceInfo().playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE;
    }

    private void planBreaks(long mediaId, long position, long duration) {
        plannedMediaId = mediaId;
        breakPoints.clear();
        resetSilenceSearch();
        long interval = MusicBreakPreferences.getIntervalMillis(context);
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

    private void start(long resumeAt, boolean rewind) {
        active = true;
        resumePosition = resumeAt;
        rewindOnResume = rewind;
        track = null;
        episodePlayer.pause();
        notifyChanged();
        handler.post(progressUpdate);
        pickerDisposable = Maybe.fromCallable(() -> {
            Uri uri = MusicBreakTrackPicker.pick(context, lastTrack);
            return uri == null ? null : MusicTrack.load(context, uri);
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::playTrack, error -> {
                    Log.e(TAG, "Unable to pick track", error);
                    finish(true);
                }, () -> finish(true));
    }

    private void playTrack(MusicTrack newTrack) {
        pickerDisposable = null;
        if (!active) {
            return;
        }
        Log.d(TAG, "Playing " + newTrack.uri + " (" + newTrack.title + ")");
        track = newTrack;
        lastTrack = newTrack.uri;
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
        if (rewindOnResume) {
            episodePlayer.play();
        } else {
            if (resumePosition != NO_SEEK) {
                episodePlayer.seekTo(resumePosition);
            }
            episodePlayer.setPlayWhenReady(true);
        }
    }

    public void release() {
        resetSilenceSearch();
        finish(false);
    }
}
