package de.danoeh.antennapod.ui.screen.preferences;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.collection.ArrayMap;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.playback.service.internal.MusicBreakTrackPicker;
import de.danoeh.antennapod.storage.preferences.MusicBreakPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;
import de.danoeh.antennapod.ui.screen.feed.preferences.SkipPreferenceDialog;
import de.danoeh.antennapod.ui.screen.playback.VariableSpeedDialog;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PlaybackPreferencesFragment extends AnimatedPreferenceFragment {
    private static final String TAG = "PlaybackPrefFragment";
    private static final String PREF_PLAYBACK_SPEED_LAUNCHER = "prefPlaybackSpeedLauncher";
    private static final String PREF_PLAYBACK_REWIND_DELTA_LAUNCHER = "prefPlaybackRewindDeltaLauncher";
    private static final String PREF_PLAYBACK_FAST_FORWARD_DELTA_LAUNCHER = "prefPlaybackFastForwardDeltaLauncher";
    private static final String PREF_MUSIC_BREAK_FOLDERS_LAUNCHER = "prefMusicBreakFoldersLauncher";
    private static final String PREF_MUSIC_BREAK_PLAYED_LAUNCHER = "prefMusicBreakPlayedLauncher";
    private static final String PREF_MUSIC_BREAK_UPCOMING_LAUNCHER = "prefMusicBreakUpcomingLauncher";

    private Disposable roundDisposable;

    private final ActivityResultLauncher<Uri> addMusicFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::addMusicFolder);
    private final ActivityResultLauncher<String> musicPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    ListPreference sourcePref = findPreference(MusicBreakPreferences.PREF_SOURCE);
                    sourcePref.setValue(MusicBreakPreferences.SOURCE_FOLDERS);
                    updateMusicFoldersPreference();
                    Toast.makeText(requireContext(), R.string.music_break_permission_denied, Toast.LENGTH_LONG)
                            .show();
                }
            });

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_playback);

        setupPlaybackScreen();
        buildSmartMarkAsPlayedPreference();
        setupMusicBreakPreferences();
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.playback_pref);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (roundDisposable != null) {
            roundDisposable.dispose();
        }
    }

    private void setupPlaybackScreen() {
        final Activity activity = getActivity();

        findPreference(PREF_PLAYBACK_SPEED_LAUNCHER).setOnPreferenceClickListener(preference -> {
            new VariableSpeedDialog().show(getChildFragmentManager(), null);
            return true;
        });
        findPreference(PREF_PLAYBACK_REWIND_DELTA_LAUNCHER).setOnPreferenceClickListener(preference -> {
            SkipPreferenceDialog.showSkipPreference(activity, SkipPreferenceDialog.SkipDirection.SKIP_REWIND, null);
            return true;
        });
        findPreference(PREF_PLAYBACK_FAST_FORWARD_DELTA_LAUNCHER).setOnPreferenceClickListener(preference -> {
            SkipPreferenceDialog.showSkipPreference(activity, SkipPreferenceDialog.SkipDirection.SKIP_FORWARD, null);
            return true;
        });
        if (Build.VERSION.SDK_INT >= 31) {
            findPreference(UserPreferences.PREF_UNPAUSE_ON_HEADSET_RECONNECT).setVisible(false);
            findPreference(UserPreferences.PREF_UNPAUSE_ON_BLUETOOTH_RECONNECT).setVisible(false);
        }

        buildEnqueueLocationPreference();
    }

    private void buildEnqueueLocationPreference() {
        final Resources res = requireActivity().getResources();
        final Map<String, String> options = new ArrayMap<>();
        {
            String[] keys = res.getStringArray(R.array.enqueue_location_values);
            String[] values = res.getStringArray(R.array.enqueue_location_options);
            for (int i = 0; i < keys.length; i++) {
                options.put(keys[i], values[i]);
            }
        }

        ListPreference pref = requirePreference(UserPreferences.PREF_ENQUEUE_LOCATION);
        pref.setSummary(res.getString(R.string.pref_enqueue_location_sum, options.get(pref.getValue())));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (!(newValue instanceof String)) {
                return false;
            }
            String newValStr = (String) newValue;
            pref.setSummary(res.getString(R.string.pref_enqueue_location_sum, options.get(newValStr)));
            return true;
        });
    }

    @NonNull
    private <T extends Preference> T requirePreference(@NonNull CharSequence key) {
        // Possibly put it to a common method in abstract base class
        T result = findPreference(key);
        if (result == null) {
            throw new IllegalArgumentException("Preference with key '" + key + "' is not found");

        }
        return result;
    }

    private void buildSmartMarkAsPlayedPreference() {
        final Resources res = getActivity().getResources();

        ListPreference pref = findPreference(UserPreferences.PREF_SMART_MARK_AS_PLAYED_SECS);
        String[] values = res.getStringArray(R.array.smart_mark_as_played_values);
        String[] entries = new String[values.length];
        for (int x = 0; x < values.length; x++) {
            if (x == 0) {
                entries[x] = res.getString(R.string.pref_smart_mark_as_played_disabled);
            } else {
                int v = Integer.parseInt(values[x]);
                if (v < 60) {
                    entries[x] = res.getQuantityString(R.plurals.time_seconds_quantified, v, v);
                } else {
                    v /= 60;
                    entries[x] = res.getQuantityString(R.plurals.time_minutes_quantified, v, v);
                }
            }
        }
        pref.setEntries(entries);
    }

    private void setupMusicBreakPreferences() {
        final Resources res = getResources();
        ListPreference intervalPref = requirePreference(MusicBreakPreferences.PREF_INTERVAL_MINUTES);
        String[] values = res.getStringArray(R.array.music_break_interval_values);
        String[] entries = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            int minutes = Integer.parseInt(values[i]);
            entries[i] = res.getQuantityString(R.plurals.time_minutes_quantified, minutes, minutes);
        }
        intervalPref.setEntries(entries);
        intervalPref.setSummaryProvider(preference -> res.getString(R.string.pref_music_break_interval_sum,
                ((ListPreference) preference).getEntry()));

        ListPreference newSharePref = requirePreference(MusicBreakPreferences.PREF_NEW_SHARE);
        newSharePref.setSummaryProvider(preference -> res.getString(R.string.pref_music_break_new_share_sum,
                ((ListPreference) preference).getEntry()));

        ListPreference sourcePref = requirePreference(MusicBreakPreferences.PREF_SOURCE);
        sourcePref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        sourcePref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (MusicBreakPreferences.SOURCE_ALL.equals(newValue)) {
                String permission = Build.VERSION.SDK_INT >= 33
                        ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
                if (ContextCompat.checkSelfPermission(requireContext(), permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    musicPermissionLauncher.launch(permission);
                }
            }
            requirePreference(PREF_MUSIC_BREAK_FOLDERS_LAUNCHER)
                    .setVisible(MusicBreakPreferences.SOURCE_FOLDERS.equals(newValue));
            return true;
        });
        requirePreference(PREF_MUSIC_BREAK_FOLDERS_LAUNCHER).setOnPreferenceClickListener(preference -> {
            showMusicFoldersDialog();
            return true;
        });
        updateMusicFoldersPreference();
        requirePreference(PREF_MUSIC_BREAK_PLAYED_LAUNCHER).setOnPreferenceClickListener(preference -> {
            showMusicBreakRound(true);
            return true;
        });
        requirePreference(PREF_MUSIC_BREAK_UPCOMING_LAUNCHER).setOnPreferenceClickListener(preference -> {
            showMusicBreakRound(false);
            return true;
        });

        EditTextPreference listenBrainzPref = requirePreference(MusicBreakPreferences.PREF_LISTENBRAINZ_TOKEN);
        listenBrainzPref.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        listenBrainzPref.setSummaryProvider(preference -> res.getString(
                TextUtils.isEmpty(((EditTextPreference) preference).getText())
                        ? R.string.pref_music_break_listenbrainz_sum_off
                        : R.string.pref_music_break_listenbrainz_sum_on));
    }

    private void showMusicBreakRound(boolean played) {
        if (roundDisposable != null) {
            roundDisposable.dispose();
        }
        Context context = requireContext().getApplicationContext();
        roundDisposable = Single.fromCallable(() -> MusicBreakTrackPicker.getRound(context))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(round -> {
                    int total = round.played.size() + round.upcoming.size();
                    List<String> lines = new ArrayList<>();
                    if (played) {
                        // Latest first
                        for (int i = round.played.size() - 1; i >= 0; i--) {
                            MusicBreakTrackPicker.Entry entry = round.played.get(i);
                            lines.add(formatPlayedAt(entry.playedAt) + " · " + entry.name);
                        }
                    } else {
                        for (int i = 0; i < round.upcoming.size(); i++) {
                            MusicBreakTrackPicker.Entry entry = round.upcoming.get(i);
                            lines.add((i + 1) + ". " + (entry.isNew
                                    ? getString(R.string.music_break_new_label) + " · " : "") + entry.name);
                        }
                    }
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(played ? R.string.music_break_played_dialog_title
                                    : R.string.music_break_upcoming_dialog_title, lines.size(), total))
                            .setPositiveButton(R.string.close_label, null);
                    if (lines.isEmpty()) {
                        builder.setMessage(R.string.music_break_round_empty);
                    } else {
                        builder.setItems(lines.toArray(new String[0]), null);
                    }
                    builder.show();
                }, error -> Log.e(TAG, "Unable to load the music break round", error));
    }

    private String formatPlayedAt(long playedAt) {
        if (playedAt == MusicBreakTrackPicker.NOT_STARTED) {
            return getString(R.string.music_break_not_started);
        } else if (playedAt == MusicBreakTrackPicker.UNKNOWN_TIME) {
            return getString(R.string.music_break_time_unknown);
        }
        return DateUtils.formatDateTime(requireContext(), playedAt, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_NUMERIC_DATE | DateUtils.FORMAT_NO_YEAR | DateUtils.FORMAT_SHOW_TIME);
    }

    private void updateMusicFoldersPreference() {
        Preference foldersPref = requirePreference(PREF_MUSIC_BREAK_FOLDERS_LAUNCHER);
        foldersPref.setVisible(MusicBreakPreferences.SOURCE_FOLDERS.equals(
                MusicBreakPreferences.getSource(requireContext())));
        List<String> names = new ArrayList<>();
        for (String folder : MusicBreakPreferences.getFolders(requireContext())) {
            names.add(getFolderName(folder));
        }
        foldersPref.setSummary(names.isEmpty()
                ? getString(R.string.pref_music_break_folders_none) : TextUtils.join(", ", names));
    }

    private void showMusicFoldersDialog() {
        List<String> folders = new ArrayList<>(MusicBreakPreferences.getFolders(requireContext()));
        String[] names = new String[folders.size()];
        boolean[] checked = new boolean[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            names[i] = getFolderName(folders.get(i));
            checked[i] = true;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_music_break_folders_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    Set<String> kept = MusicBreakPreferences.getFolders(requireContext());
                    for (int i = 0; i < folders.size(); i++) {
                        if (!checked[i]) {
                            kept.remove(folders.get(i));
                            releaseFolderPermission(folders.get(i));
                        }
                    }
                    MusicBreakPreferences.setFolders(requireContext(), kept);
                    updateMusicFoldersPreference();
                })
                .setNeutralButton(R.string.music_break_add_folder, (dialog, which) ->
                        addMusicFolderLauncher.launch(null))
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void addMusicFolder(Uri uri) {
        if (uri == null) {
            return;
        }
        requireContext().getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Set<String> folders = MusicBreakPreferences.getFolders(requireContext());
        folders.add(uri.toString());
        MusicBreakPreferences.setFolders(requireContext(), folders);
        updateMusicFoldersPreference();
    }

    private void releaseFolderPermission(String folder) {
        try {
            requireContext().getContentResolver().releasePersistableUriPermission(Uri.parse(folder),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to release folder permission", e);
        }
    }

    private static String getFolderName(String folder) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(Uri.parse(folder));
            int separator = documentId.indexOf(':');
            return separator >= 0 ? documentId.substring(separator + 1) : documentId;
        } catch (IllegalArgumentException e) {
            return folder;
        }
    }
}
