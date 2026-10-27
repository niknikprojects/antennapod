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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Picks tracks from a shuffled deck: each round, all tracks are shuffled once and played in that order
 * before any track repeats. Tracks that appear during a round are inserted at random places among the
 * upcoming ones, tracks whose files disappear are dropped. The round is kept in a file across restarts.
 */
public class MusicBreakTrackPicker {
    private static final String TAG = "MusicBreakTrackPicker";
    private static final String ROUND_FILE = "musicbreak-round.json";
    private static final long MIN_DURATION_MS = 30_000;
    /** Played time of a picked track that has not started playing (yet). */
    public static final long NOT_STARTED = -1;
    /** Played time of a track played before the time was recorded. */
    public static final long UNKNOWN_TIME = 0;
    private static final Random RANDOM = new Random();
    private static final Object LOCK = new Object();

    public static class Pick {
        public final Uri uri;
        public final int poolSize;
        public final int leftInRound;

        Pick(Uri uri, int poolSize, int leftInRound) {
            this.uri = uri;
            this.poolSize = poolSize;
            this.leftInRound = leftInRound;
        }
    }

    /** A track of the current round. */
    public static class Entry {
        public final String name;
        /** When it started playing, {@link #NOT_STARTED} or {@link #UNKNOWN_TIME}; unused for upcoming tracks. */
        public final long playedAt;

        Entry(String name, long playedAt) {
            this.name = name;
            this.playedAt = playedAt;
        }
    }

    /** The current round: played tracks in the order they were picked, upcoming tracks in playing order. */
    public static class Round {
        public final List<Entry> played = new ArrayList<>();
        public final List<Entry> upcoming = new ArrayList<>();
    }

    private static class Deck {
        final List<String> played = new ArrayList<>();
        final Map<String, Long> playedAt = new HashMap<>();
        List<String> upcoming = new ArrayList<>();
        final Map<String, String> names = new HashMap<>();
    }

    @Nullable
    public static Pick pick(@NonNull Context context, @Nullable Uri previous) {
        List<Uri> tracks = listTracks(context);
        if (tracks.isEmpty()) {
            Log.d(TAG, "No tracks found");
            return null;
        }
        synchronized (LOCK) {
            Deck deck = sync(context, tracks);
            if (deck.upcoming.isEmpty()) {
                Log.d(TAG, "All " + tracks.size() + " tracks played, starting over");
                deck.played.clear();
                deck.playedAt.clear();
                deck.upcoming = shuffled(available(tracks));
                if (deck.upcoming.size() > 1 && previous != null && deck.upcoming.get(0).equals(previous.toString())) {
                    deck.upcoming.add(deck.upcoming.remove(0));
                }
            }
            String picked = deck.upcoming.remove(0);
            deck.played.add(picked);
            deck.playedAt.put(picked, NOT_STARTED);
            write(context, deck);
            Log.d(TAG, "Picked " + picked + ", " + deck.upcoming.size() + " of " + tracks.size() + " left this round");
            return new Pick(Uri.parse(picked), tracks.size(), deck.upcoming.size());
        }
    }

    /** Remembers when a picked track started playing and its name. */
    public static void markStarted(@NonNull Context context, @NonNull Uri uri, @NonNull String name, long timeMs) {
        synchronized (LOCK) {
            Deck deck = read(context);
            if (deck == null || !deck.playedAt.containsKey(uri.toString())) {
                return;
            }
            deck.playedAt.put(uri.toString(), timeMs);
            deck.names.put(uri.toString(), name);
            write(context, deck);
        }
    }

    /** The current round, with the files on the device. Reads the names of new tracks, so it can be slow. */
    @NonNull
    public static Round getRound(@NonNull Context context) {
        List<Uri> tracks = listTracks(context);
        Deck deck;
        synchronized (LOCK) {
            deck = sync(context, tracks);
            write(context, deck);
        }
        Map<String, String> found = new HashMap<>();
        for (String uri : deck.played) {
            if (!deck.names.containsKey(uri)) {
                found.put(uri, MusicTrack.readName(context, Uri.parse(uri)));
            }
        }
        for (String uri : deck.upcoming) {
            if (!deck.names.containsKey(uri)) {
                found.put(uri, MusicTrack.readName(context, Uri.parse(uri)));
            }
        }
        if (!found.isEmpty()) {
            synchronized (LOCK) {
                Deck current = read(context);
                if (current != null) {
                    current.names.putAll(found);
                    write(context, current);
                }
            }
            deck.names.putAll(found);
        }
        Round round = new Round();
        for (String uri : deck.played) {
            Long playedAt = deck.playedAt.get(uri);
            round.played.add(new Entry(deck.names.get(uri), playedAt != null ? playedAt : UNKNOWN_TIME));
        }
        for (String uri : deck.upcoming) {
            round.upcoming.add(new Entry(deck.names.get(uri), UNKNOWN_TIME));
        }
        return round;
    }

    private static List<Uri> listTracks(Context context) {
        return MusicBreakPreferences.SOURCE_ALL.equals(MusicBreakPreferences.getSource(context))
                ? queryMediaStore(context) : scanFolders(context);
    }

    /** Reads the round and brings it up to date with the tracks on the device. Call with LOCK held. */
    private static Deck sync(Context context, List<Uri> tracks) {
        Set<String> available = available(tracks);
        Deck deck = read(context);
        if (deck == null) {
            // First run with a round file: carry over the played tracks remembered before
            deck = new Deck();
            for (String uri : MusicBreakPreferences.getPlayedTracks(context)) {
                if (available.contains(uri)) {
                    deck.played.add(uri);
                    deck.playedAt.put(uri, UNKNOWN_TIME);
                }
            }
            for (String uri : available) {
                if (!deck.playedAt.containsKey(uri)) {
                    deck.upcoming.add(uri);
                }
            }
            Collections.shuffle(deck.upcoming, RANDOM);
            return deck;
        }
        deck.played.retainAll(available);
        deck.playedAt.keySet().retainAll(available);
        deck.upcoming.retainAll(available);
        deck.names.keySet().retainAll(available);
        if (deck.played.isEmpty() && deck.upcoming.isEmpty()) {
            deck.upcoming = shuffled(available);
            return deck;
        }
        Set<String> known = new HashSet<>(deck.played);
        known.addAll(deck.upcoming);
        List<String> added = new ArrayList<>();
        for (String uri : available) {
            if (!known.contains(uri)) {
                added.add(uri);
            }
        }
        Collections.shuffle(added, RANDOM);
        for (String uri : added) {
            deck.upcoming.add(RANDOM.nextInt(deck.upcoming.size() + 1), uri);
        }
        if (!added.isEmpty()) {
            Log.d(TAG, added.size() + " new tracks added to the round");
        }
        return deck;
    }

    private static Set<String> available(List<Uri> tracks) {
        Set<String> result = new LinkedHashSet<>();
        for (Uri track : tracks) {
            result.add(track.toString());
        }
        return result;
    }

    private static List<String> shuffled(Set<String> tracks) {
        List<String> result = new ArrayList<>(tracks);
        Collections.shuffle(result, RANDOM);
        return result;
    }

    @Nullable
    private static Deck read(Context context) {
        File file = new File(context.getFilesDir(), ROUND_FILE);
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            Deck deck = new Deck();
            JSONArray played = json.getJSONArray("played");
            for (int i = 0; i < played.length(); i++) {
                JSONObject entry = played.getJSONObject(i);
                deck.played.add(entry.getString("uri"));
                deck.playedAt.put(entry.getString("uri"), entry.getLong("at"));
            }
            JSONArray upcoming = json.getJSONArray("upcoming");
            for (int i = 0; i < upcoming.length(); i++) {
                deck.upcoming.add(upcoming.getString(i));
            }
            JSONObject names = json.getJSONObject("names");
            Iterator<String> keys = names.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                deck.names.put(key, names.getString(key));
            }
            return deck;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Unable to read the round, starting a new one", e);
            return null;
        }
    }

    private static void write(Context context, Deck deck) {
        try {
            JSONArray played = new JSONArray();
            for (String uri : deck.played) {
                Long playedAt = deck.playedAt.get(uri);
                played.put(new JSONObject().put("uri", uri).put("at", playedAt != null ? playedAt : UNKNOWN_TIME));
            }
            JSONObject json = new JSONObject()
                    .put("played", played)
                    .put("upcoming", new JSONArray(deck.upcoming))
                    .put("names", new JSONObject(deck.names));
            File file = new File(context.getFilesDir(), ROUND_FILE);
            File temp = new File(context.getFilesDir(), ROUND_FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) {
                Log.e(TAG, "Unable to save the round");
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Unable to save the round", e);
        }
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
