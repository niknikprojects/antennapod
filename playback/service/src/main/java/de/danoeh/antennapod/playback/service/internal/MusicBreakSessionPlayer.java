package de.danoeh.antennapod.playback.service.internal;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.FlagSet;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The player the media session sees. While a music break is active it reports the music track
 * instead of the paused episode and forwards the transport controls to the music break, so the
 * track shows up in the notification, on the lock screen and in Android Auto.
 * The playback service itself keeps using the wrapped player and therefore still sees the episode.
 */
@OptIn(markerClass = UnstableApi.class)
public class MusicBreakSessionPlayer extends ForwardingPlayer implements MusicBreak.Listener {
    private static final Player.Events STATE_EVENTS = new Player.Events(new FlagSet.Builder()
            .addAll(EVENT_MEDIA_METADATA_CHANGED, EVENT_PLAY_WHEN_READY_CHANGED,
                    EVENT_PLAYBACK_STATE_CHANGED, EVENT_IS_PLAYING_CHANGED)
            .build());

    private final MusicBreak musicBreak;
    private final List<Player.Listener> listeners = new CopyOnWriteArrayList<>();

    public MusicBreakSessionPlayer(@NonNull Player player, @NonNull MusicBreak musicBreak) {
        super(player);
        this.musicBreak = musicBreak;
        musicBreak.setListener(this);
    }

    @Override
    public void onMusicBreakChanged() {
        MediaMetadata mediaMetadata = getMediaMetadata();
        boolean playWhenReady = getPlayWhenReady();
        int playbackState = getPlaybackState();
        boolean playing = isPlaying();
        for (Player.Listener listener : listeners) {
            listener.onMediaMetadataChanged(mediaMetadata);
            listener.onPlayWhenReadyChanged(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
            listener.onPlaybackStateChanged(playbackState);
            listener.onIsPlayingChanged(playing);
            listener.onEvents(this, STATE_EVENTS);
        }
    }

    @Override
    public void addListener(@NonNull Player.Listener listener) {
        super.addListener(listener);
        listeners.add(listener);
    }

    @Override
    public void removeListener(@NonNull Player.Listener listener) {
        super.removeListener(listener);
        listeners.remove(listener);
    }

    @NonNull
    @Override
    public MediaMetadata getMediaMetadata() {
        return musicBreak.isActive() ? musicBreak.getMediaMetadata() : super.getMediaMetadata();
    }

    @Override
    public boolean getPlayWhenReady() {
        return musicBreak.isActive() ? musicBreak.getMusicPlayWhenReady() : super.getPlayWhenReady();
    }

    @Override
    public int getPlaybackState() {
        return musicBreak.isActive() ? musicBreak.getMusicPlaybackState() : super.getPlaybackState();
    }

    @Override
    public int getPlaybackSuppressionReason() {
        return musicBreak.isActive() ? PLAYBACK_SUPPRESSION_REASON_NONE : super.getPlaybackSuppressionReason();
    }

    @Override
    public boolean isPlaying() {
        return musicBreak.isActive() ? musicBreak.isMusicPlaying() : super.isPlaying();
    }

    @Override
    public long getCurrentPosition() {
        return musicBreak.isActive() ? musicBreak.getMusicPosition() : super.getCurrentPosition();
    }

    @Override
    public long getContentPosition() {
        return musicBreak.isActive() ? musicBreak.getMusicPosition() : super.getContentPosition();
    }

    @Override
    public long getBufferedPosition() {
        return musicBreak.isActive() ? musicBreak.getMusicBufferedPosition() : super.getBufferedPosition();
    }

    @Override
    public long getContentBufferedPosition() {
        return musicBreak.isActive() ? musicBreak.getMusicBufferedPosition() : super.getContentBufferedPosition();
    }

    @Override
    public long getTotalBufferedDuration() {
        return musicBreak.isActive()
                ? Math.max(0, musicBreak.getMusicBufferedPosition() - musicBreak.getMusicPosition())
                : super.getTotalBufferedDuration();
    }

    @Override
    public long getDuration() {
        return musicBreak.isActive() ? musicBreak.getMusicDuration() : super.getDuration();
    }

    @Override
    public long getContentDuration() {
        return musicBreak.isActive() ? musicBreak.getMusicDuration() : super.getContentDuration();
    }

    @Override
    public void play() {
        if (musicBreak.isActive()) {
            musicBreak.playMusic();
        } else {
            super.play();
        }
    }

    @Override
    public void pause() {
        if (musicBreak.isActive()) {
            musicBreak.pauseMusic();
        } else {
            super.pause();
        }
    }

    @Override
    public void setPlayWhenReady(boolean playWhenReady) {
        if (musicBreak.isActive()) {
            if (playWhenReady) {
                musicBreak.playMusic();
            } else {
                musicBreak.pauseMusic();
            }
        } else {
            super.setPlayWhenReady(playWhenReady);
        }
    }

    @Override
    public void seekTo(long positionMs) {
        if (musicBreak.isActive()) {
            musicBreak.seekMusic(positionMs);
        } else {
            super.seekTo(positionMs);
        }
    }

    @Override
    public void seekTo(int mediaItemIndex, long positionMs) {
        if (musicBreak.isActive()) {
            musicBreak.seekMusic(positionMs);
        } else {
            super.seekTo(mediaItemIndex, positionMs);
        }
    }

    @Override
    public void seekForward() {
        if (musicBreak.isActive()) {
            musicBreak.skip();
        } else {
            super.seekForward();
        }
    }

    @Override
    public void seekToNext() {
        if (musicBreak.isActive()) {
            musicBreak.skip();
        } else {
            super.seekToNext();
        }
    }

    @Override
    public void seekToNextMediaItem() {
        if (musicBreak.isActive()) {
            musicBreak.skip();
        } else {
            super.seekToNextMediaItem();
        }
    }

    @Override
    public void seekBack() {
        if (musicBreak.isActive()) {
            musicBreak.seekMusic(0);
        } else {
            super.seekBack();
        }
    }

    @Override
    public void seekToPrevious() {
        if (musicBreak.isActive()) {
            musicBreak.seekMusic(0);
        } else {
            super.seekToPrevious();
        }
    }

    @Override
    public void seekToPreviousMediaItem() {
        if (musicBreak.isActive()) {
            musicBreak.seekMusic(0);
        } else {
            super.seekToPreviousMediaItem();
        }
    }

    @Override
    public void stop() {
        musicBreak.cancel();
        super.stop();
    }
}
