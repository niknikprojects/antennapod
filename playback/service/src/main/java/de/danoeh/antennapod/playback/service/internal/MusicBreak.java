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
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Splits each episode into parts of roughly the configured interval and plays a random local
 * music track between them, preferably at a chapter start near each break point. The end of an
 * episode counts as a break when the next episode starts automatically.
 * Media3 keeps the service in foreground for 10 minutes after pausing, so a break never lasts longer than 9.
 */
public class MusicBreak {
    private static final String TAG = "MusicBreak";
    public static final long MAX_BREAK_MS = TimeUnit.MINUTES.toMillis(9);
    private static final long MAX_WINDOW_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long MIN_LEAD_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long CHAPTER_TOLERANCE_MS = 2000;
    private static final long NO_SEEK = -1;

    private final Context context;
    private final Player episodePlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable maxDurationReached = () -> finish(true);
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
        }
        if (breakPoints.isEmpty()) {
            return;
        }
        long point = breakPoints.get(0);
        if (position < point - windowMs) {
            return;
        }
        long chapterStart = findChapterStart(media, Math.max(point - windowMs, position - CHAPTER_TOLERANCE_MS),
                point + windowMs, point);
        if (chapterStart != NO_SEEK) {
            if (position >= chapterStart) {
                breakPoints.remove(0);
                Log.d(TAG, "Music break at chapter start " + chapterStart);
                start(chapterStart, false);
            }
        } else if (position >= point) {
            breakPoints.remove(0);
            Log.d(TAG, "Music break at " + position);
            start(NO_SEEK, true);
        }
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
        finish(false);
    }
}
