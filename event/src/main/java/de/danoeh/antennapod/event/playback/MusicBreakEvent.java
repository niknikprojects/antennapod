package de.danoeh.antennapod.event.playback;

import androidx.annotation.Nullable;

public class MusicBreakEvent {
    private final boolean active;
    private final String title;
    private final String subtitle;
    @Nullable
    private final byte[] artwork;
    private final long position;
    private final long duration;
    private final boolean playing;

    private MusicBreakEvent(boolean active, String title, String subtitle, @Nullable byte[] artwork,
                            long position, long duration, boolean playing) {
        this.active = active;
        this.title = title;
        this.subtitle = subtitle;
        this.artwork = artwork;
        this.position = position;
        this.duration = duration;
        this.playing = playing;
    }

    public static MusicBreakEvent playing(String title, String subtitle, @Nullable byte[] artwork,
                                          long position, long duration, boolean playing) {
        return new MusicBreakEvent(true, title, subtitle, artwork, position, duration, playing);
    }

    public static MusicBreakEvent ended() {
        return new MusicBreakEvent(false, "", "", null, 0, 0, false);
    }

    public boolean isActive() {
        return active;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    @Nullable
    public byte[] getArtwork() {
        return artwork;
    }

    public long getPosition() {
        return position;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isPlaying() {
        return playing;
    }
}
