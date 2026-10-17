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
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.storage.preferences.MusicBreakPreferences;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.concurrent.TimeUnit;

/**
 * Pauses the episode after a configured amount of listening time, plays a random local
 * music track in a separate player and resumes the episode afterwards.
 * Media3 keeps the service in foreground for 10 minutes after pausing, so a break never lasts longer than 9.
 */
public class MusicBreak {
    private static final String TAG = "MusicBreak";
    public static final long MAX_BREAK_MS = TimeUnit.MINUTES.toMillis(9);
    private static final long MIN_REMAINING_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long TICK_MS = 1000;

    private final Context context;
    private final Player episodePlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable maxDurationReached = () -> finish(true);
    private long listenedMs = 0;
    private boolean active = false;
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
        if (active || pickerDisposable != null || !MusicBreakPreferences.isEnabled(context)) {
            return;
        }
        if (media == null || media.getMediaType() == MediaType.VIDEO || !episodePlayer.isPlaying()
                || episodePlayer.getDeviceInfo().playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            return;
        }
        listenedMs += TICK_MS;
        if (listenedMs < MusicBreakPreferences.getIntervalMillis(context)) {
            return;
        }
        long duration = episodePlayer.getDuration();
        if (duration > 0 && duration - episodePlayer.getCurrentPosition() < MIN_REMAINING_MS) {
            return;
        }
        pickerDisposable = Maybe.fromCallable(() -> MusicBreakTrackPicker.pick(context, lastTrack))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::start, error -> {
                    Log.e(TAG, "Unable to pick track", error);
                    pickerDisposable = null;
                    listenedMs = 0;
                }, () -> {
                    pickerDisposable = null;
                    listenedMs = 0;
                });
    }

    private void start(Uri track) {
        pickerDisposable = null;
        if (!episodePlayer.isPlaying()) {
            return;
        }
        Log.d(TAG, "Starting music break: " + track);
        listenedMs = 0;
        lastTrack = track;
        active = true;
        episodePlayer.pause();
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
        handler.removeCallbacks(maxDurationReached);
        ExoPlayer player = musicPlayer;
        musicPlayer = null;
        if (player != null) {
            handler.post(player::release);
        }
        if (resumeEpisode) {
            episodePlayer.play();
        }
    }

    public void release() {
        if (pickerDisposable != null) {
            pickerDisposable.dispose();
            pickerDisposable = null;
        }
        finish(false);
    }
}
