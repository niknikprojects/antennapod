package de.danoeh.antennapod.playback.service.internal;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.danoeh.antennapod.storage.preferences.MusicBreakPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Picks tracks like a shuffled deck: every track is played once before any track repeats.
 * The played tracks are remembered across restarts and forgotten when their files disappear.
 */
public class MusicBreakTrackPicker {
    private static final String TAG = "MusicBreakTrackPicker";
    private static final long MIN_DURATION_MS = 30_000;
    private static final Random RANDOM = new Random();

    @Nullable
    public static Uri pick(@NonNull Context context, @Nullable Uri previous) {
        List<Uri> tracks = MusicBreakPreferences.SOURCE_ALL.equals(MusicBreakPreferences.getSource(context))
                ? queryMediaStore(context) : scanFolders(context);
        if (tracks.isEmpty()) {
            Log.d(TAG, "No tracks found");
            return null;
        }
        Set<String> available = new HashSet<>();
        for (Uri track : tracks) {
            available.add(track.toString());
        }
        Set<String> played = MusicBreakPreferences.getPlayedTracks(context);
        played.retainAll(available);
        List<Uri> candidates = new ArrayList<>();
        for (Uri track : tracks) {
            if (!played.contains(track.toString())) {
                candidates.add(track);
            }
        }
        if (candidates.isEmpty()) {
            Log.d(TAG, "All " + tracks.size() + " tracks played, starting over");
            played.clear();
            candidates.addAll(tracks);
            if (candidates.size() > 1) {
                candidates.remove(previous);
            }
        }
        Uri picked = candidates.get(RANDOM.nextInt(candidates.size()));
        played.add(picked.toString());
        MusicBreakPreferences.setPlayedTracks(context, played);
        Log.d(TAG, "Picked " + picked + ", " + (candidates.size() - 1) + " of " + tracks.size() + " left this round");
        return picked;
    }

    private static List<Uri> queryMediaStore(Context context) {
        List<Uri> result = new ArrayList<>();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Audio.Media._ID};
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                + MediaStore.Audio.Media.DURATION + " BETWEEN ? AND ?";
        String[] args = {String.valueOf(MIN_DURATION_MS), String.valueOf(MusicBreak.MAX_TRACK_MS)};
        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, args, null)) {
            if (cursor == null) {
                return result;
            }
            while (cursor.moveToNext()) {
                result.add(ContentUris.withAppendedId(collection, cursor.getLong(0)));
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore query failed", e);
        }
        return result;
    }

    private static List<Uri> scanFolders(Context context) {
        List<Uri> result = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        for (String folder : MusicBreakPreferences.getFolders(context)) {
            try {
                Uri treeUri = Uri.parse(folder);
                scanFolder(resolver, treeUri, DocumentsContract.getTreeDocumentId(treeUri), result);
            } catch (Exception e) {
                Log.e(TAG, "Unable to scan " + folder, e);
            }
        }
        return result;
    }

    private static void scanFolder(ContentResolver resolver, Uri treeUri, String documentId, List<Uri> result) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        List<String> subFolders = new ArrayList<>();
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String mimeType = cursor.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    subFolders.add(id);
                } else if (isAudioFile(mimeType)) {
                    result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id));
                }
            }
        }
        for (String subFolder : subFolders) {
            scanFolder(resolver, treeUri, subFolder, result);
        }
    }

    private static boolean isAudioFile(@Nullable String mimeType) {
        return mimeType != null && mimeType.startsWith("audio/")
                && !mimeType.contains("mpegurl") && !mimeType.contains("scpls");
    }
}
