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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.storage.preferences.MusicBreakPreferences;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Splits each episode into parts of roughly the configured interval and plays a random local
 * music track between them, preferably at a chapter start near each break point, otherwise in
 * a pause of the speaker found by decoding the surroundings of the break point in advance.
 * The end of an episode counts as a break when the next episode starts automatically.
 * Media3 keeps the service in foreground for 10 minutes after pausing, so a break never lasts longer than 9.
 */
public class MusicBreak {
    private static final String TAG = "MusicBreak";
    public static final long MAX_BREAK_MS = TimeUnit.MINUTES.toMillis(9);
    private static final long MAX_WINDOW_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long MIN_LEAD_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long CHAPTER_TOLERANCE_MS = 2000;
    private static final long NO_SEEK = -1;
    private static final long SCAN_LEAD_MS = 30_000;
    private static final long MAX_PAUSE_OFFSET_MS = 300;
    private static final long MAX_SCORED_SILENCE_MS = 2000;

    private final Context context;
    private final Player episodePlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable maxDurationReached = () -> finish(true);
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
        double bestScore = 0;
        for (SilenceScanner.Silence silence : silences) {
            if (getPauseAt(silence) <= position + MAX_PAUSE_OFFSET_MS) {
                continue;
            }
            double distance = Math.min(1.0,
                    Math.abs((silence.startMs + silence.endMs) / 2 - point) / (double) Math.max(windowMs, 1));
            double score = Math.min(silence.getLength(), MAX_SCORED_SILENCE_MS) * (1 - 0.6 * distance);
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

    /**
     * Called when the next episode was loaded after the previous one ended.
     * Returns true if a break started; the break starts playback of the next episode when it is done.
     */
    public boolean startBetweenEpisodes(@Nullable FeedMedia nextMedia) {
        if (active || !canBreak(nextMedia)) {
            return false;
        }
        Log.d(TAG, "Music break between episodes");
        start(NO_SEEK, false);
        return true;
    }

    private boolean canBreak(@Nullable FeedMedia media) {
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
        episodePlayer.pause();
        pickerDisposable = Maybe.fromCallable(() -> MusicBreakTrackPicker.pick(context, lastTrack))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::playTrack, error -> {
                    Log.e(TAG, "Unable to pick track", error);
                    finish(true);
                }, () -> finish(true));
    }

    private void playTrack(Uri track) {
        pickerDisposable = null;
        if (!active) {
            return;
        }
        Log.d(TAG, "Playing " + track);
        lastTrack = track;
        ExoPlayer player = new ExoPlayer.Builder(context)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    finish(true);
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (!playWhenReady) {
                    finish(false);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "Unable to play " + track, error);
                finish(true);
            }
        });
        musicPlayer = player;
        player.setMediaItem(MediaItem.fromUri(track));
        player.prepare();
        player.play();
        handler.postDelayed(maxDurationReached, MAX_BREAK_MS);
    }

    public void cancel() {
        finish(false);
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
        handler.removeCallbacks(maxDurationReached);
        ExoPlayer player = musicPlayer;
        musicPlayer = null;
        if (player != null) {
            handler.post(player::release);
        }
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
