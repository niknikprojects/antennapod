package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MusicBreakPreferences {
    public static final String PREF_ENABLED = "prefMusicBreakEnabled";
    public static final String PREF_INTERVAL_MINUTES = "prefMusicBreakInterval";
    public static final String PREF_SOURCE = "prefMusicBreakSource";
    public static final String PREF_FOLDERS = "prefMusicBreakFolders";
    public static final String PREF_REPLACE_ADS = "prefMusicBreakReplaceAds";
    public static final String PREF_LISTENBRAINZ_TOKEN = "prefMusicBreakListenBrainzToken";
    private static final String PREF_PLAYED_TRACKS = "prefMusicBreakPlayedTracks";

    public static final String SOURCE_ALL = "all";
    public static final String SOURCE_FOLDERS = "folders";

    private static SharedPreferences prefs(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(PREF_ENABLED, false);
    }

    public static boolean isReplaceAds(@NonNull Context context) {
        return prefs(context).getBoolean(PREF_REPLACE_ADS, true);
    }

    public static long getIntervalMillis(@NonNull Context context) {
        return TimeUnit.MINUTES.toMillis(Long.parseLong(prefs(context).getString(PREF_INTERVAL_MINUTES, "10")));
    }

    @NonNull
    public static String getSource(@NonNull Context context) {
        return prefs(context).getString(PREF_SOURCE, SOURCE_FOLDERS);
    }

    @NonNull
    public static Set<String> getFolders(@NonNull Context context) {
        return new HashSet<>(prefs(context).getStringSet(PREF_FOLDERS, new HashSet<>()));
    }

    public static void setFolders(@NonNull Context context, @NonNull Set<String> folders) {
        prefs(context).edit().putStringSet(PREF_FOLDERS, new HashSet<>(folders)).apply();
    }

    @NonNull
    public static String getListenBrainzToken(@NonNull Context context) {
        return prefs(context).getString(PREF_LISTENBRAINZ_TOKEN, "").trim();
    }

    /** Played tracks as remembered before the round was kept in a file. */
    @NonNull
    public static Set<String> getPlayedTracks(@NonNull Context context) {
        return new HashSet<>(prefs(context).getStringSet(PREF_PLAYED_TRACKS, new HashSet<>()));
    }
}
