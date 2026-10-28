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
import java.util.concurrent.TimeUnit;

/**
 * Picks tracks from a shuffled queue. A played track waits until a share of the tracks has played since or
 * {@link #FORGET_MS} has passed, then goes back among the last ones in the queue, so no track is locked out
 * for long however many tracks are added. Tracks that appeared lately and have not played yet get some of
 * the breaks of their own. When each track last played is remembered for a while after its file is gone,
 * so a track that comes back soon is neither new nor played again too soon. Then it is forgotten, so the
 * state does not grow. The state is kept in a file across restarts.
 */
public class MusicBreakTrackPicker {
    private static final String TAG = "MusicBreakTrackPicker";
    private static final String ROUND_FILE = "musicbreak-round.json";
    private static final long MIN_DURATION_MS = 30_000;
    /** How long a track that has not played counts as new after it first appeared. */
    private static final long NEW_MS = TimeUnit.DAYS.toMillis(7);
    /** Longest wait of a played track, and how long a track whose file is gone is remembered. */
    private static final long FORGET_MS = TimeUnit.DAYS.toMillis(14);
    /** Played time of a picked track that has not started playing (yet). */
    public static final long NOT_STARTED = -1;
    /** Played time of a track played before the time was recorded. */
    public static final long UNKNOWN_TIME = 0;
    private static final long NEVER_PICKED = -1;
    private static final Random RANDOM = new Random();
    private static final Object LOCK = new Object();

    public static class Pick {
        public final Uri uri;
        public final int poolSize;
        /** Tracks that can play now, including this one. */
        public final int leftInRound;

        Pick(Uri uri, int poolSize, int leftInRound) {
            this.uri = uri;
            this.poolSize = poolSize;
            this.leftInRound = leftInRound;
        }
    }

    /** A track on the device. */
    public static class Entry {
        public final String name;
        /** When it started playing, {@link #NOT_STARTED} or {@link #UNKNOWN_TIME}; unused for upcoming tracks. */
        public final long playedAt;
        public final boolean isNew;

        Entry(String name, long playedAt, boolean isNew) {
            this.name = name;
            this.playedAt = playedAt;
            this.isNew = isNew;
        }
    }

    /** Played tracks that have to wait, oldest first, and the tracks that can play in playing order. */
    public static class Round {
        public final List<Entry> played = new ArrayList<>();
        public final List<Entry> upcoming = new ArrayList<>();
    }

    /** What is remembered about a track, also while its file is gone. */
    private static class Track {
        long firstSeen;
        long lastSeen;
        /** Number of the pick that last played it, {@link #NEVER_PICKED}. */
        long pick = NEVER_PICKED;
        /** When it was last picked, 0 if unknown. */
        long pickedAt;
        long playedAt = UNKNOWN_TIME;
        @Nullable
        String name;
    }

    private static class Deck {
        long picks;
        /** Percentage points saved up towards the next new track. */
        int credit;
        List<String> queue = new ArrayList<>();
        final List<String> fresh = new ArrayList<>();
        final Map<String, Track> tracks = new HashMap<>();
    }

    @Nullable
    public static Pick pick(@NonNull Context context, @Nullable Uri previous) {
        List<Uri> tracks = listTracks(context);
        if (tracks.isEmpty()) {
            Log.d(TAG, "No tracks found");
            return null;
        }
        int newShare = MusicBreakPreferences.getNewSharePercent(context);
        synchronized (LOCK) {
            Set<String> available = available(tracks);
            Deck deck = sync(context, available);
            int left = deck.queue.size() + deck.fresh.size();
            String picked;
            if (left == 0) {
                picked = longestWaiting(deck, available, previous);
                Log.d(TAG, "Every track played lately, picking the one played longest ago");
            } else if (takeNew(deck, newShare)) {
                picked = deck.fresh.remove(0);
            } else {
                picked = deck.queue.remove(0);
            }
            Track track = deck.tracks.get(picked);
            deck.picks++;
            track.pick = deck.picks;
            track.pickedAt = System.currentTimeMillis();
            track.playedAt = NOT_STARTED;
            write(context, deck);
            Log.d(TAG, "Picked " + picked + ", " + deck.fresh.size() + " new and " + deck.queue.size()
                    + " more of " + available.size() + " can play");
            return new Pick(Uri.parse(picked), available.size(), Math.max(left, 1));
        }
    }

    /** Whether the next pick takes a new track. Updates the saved up credit. */
    private static boolean takeNew(Deck deck, int newShare) {
        if (deck.fresh.isEmpty()) {
            deck.credit = 0;
            return false;
        }
        deck.credit += newShare;
        if (deck.credit >= 100) {
            deck.credit -= 100;
            return true;
        }
        return deck.queue.isEmpty();
    }

    private static String longestWaiting(Deck deck, Set<String> available, @Nullable Uri previous) {
        String result = null;
        for (String uri : available) {
            boolean better = result == null || deck.tracks.get(uri).pick < deck.tracks.get(result).pick;
            if (better && (available.size() == 1 || previous == null || !uri.equals(previous.toString()))) {
                result = uri;
            }
        }
        return result;
    }

    /** Remembers when a picked track started playing and its name. */
    public static void markStarted(@NonNull Context context, @NonNull Uri uri, @NonNull String name, long timeMs) {
        synchronized (LOCK) {
            Deck deck = read(context);
            Track track = deck != null ? deck.tracks.get(uri.toString()) : null;
            if (track == null || track.pick == NEVER_PICKED) {
                return;
            }
            track.playedAt = timeMs;
            track.name = name;
            write(context, deck);
        }
    }

    /** The tracks on the device. Reads the names of new tracks, so it can be slow. */
    @NonNull
    public static Round getRound(@NonNull Context context) {
        Set<String> available = available(listTracks(context));
        int newShare = MusicBreakPreferences.getNewSharePercent(context);
        Deck deck;
        synchronized (LOCK) {
            deck = sync(context, available);
            write(context, deck);
        }
        Map<String, String> found = new HashMap<>();
        for (String uri : available) {
            if (deck.tracks.get(uri).name == null) {
                found.put(uri, MusicTrack.readName(context, Uri.parse(uri)));
            }
        }
        if (!found.isEmpty()) {
            synchronized (LOCK) {
                Deck current = read(context);
                if (current != null) {
                    for (Map.Entry<String, String> name : found.entrySet()) {
                        Track track = current.tracks.get(name.getKey());
                        if (track != null) {
                            track.name = name.getValue();
                        }
                    }
                    write(context, current);
                }
            }
            for (Map.Entry<String, String> name : found.entrySet()) {
                deck.tracks.get(name.getKey()).name = name.getValue();
            }
        }
        Round round = new Round();
        Set<String> canPlay = new HashSet<>(deck.queue);
        canPlay.addAll(deck.fresh);
        List<String> waiting = new ArrayList<>();
        for (String uri : available) {
            if (!canPlay.contains(uri)) {
                waiting.add(uri);
            }
        }
        Collections.sort(waiting, (a, b) -> Long.compare(deck.tracks.get(a).pick, deck.tracks.get(b).pick));
        for (String uri : waiting) {
            Track track = deck.tracks.get(uri);
            round.played.add(new Entry(track.name, track.playedAt, false));
        }
        // Upcoming in the order pick() takes them
        Deck simulated = new Deck();
        simulated.credit = deck.credit;
        simulated.queue = new ArrayList<>(deck.queue);
        simulated.fresh.addAll(deck.fresh);
        while (!simulated.fresh.isEmpty() || !simulated.queue.isEmpty()) {
            boolean isNew = takeNew(simulated, newShare);
            String uri = isNew ? simulated.fresh.remove(0) : simulated.queue.remove(0);
            round.upcoming.add(new Entry(deck.tracks.get(uri).name, UNKNOWN_TIME, isNew));
        }
        return round;
    }

    private static List<Uri> listTracks(Context context) {
        return MusicBreakPreferences.SOURCE_ALL.equals(MusicBreakPreferences.getSource(context))
                ? queryMediaStore(context) : scanFolders(context);
    }

    /**
     * Reads the state and brings it up to date with the tracks on the device: lets played tracks that have
     * waited long enough back into the queue and places tracks that appeared. Call with LOCK held.
     */
    private static Deck sync(Context context, Set<String> available) {
        long now = System.currentTimeMillis();
        boolean newEnabled = MusicBreakPreferences.getNewSharePercent(context) > 0;
        Deck saved = read(context);
        Deck deck = saved != null ? saved : carryOver(context, available);
        for (Iterator<Map.Entry<String, Track>> it = deck.tracks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Track> entry = it.next();
            Track track = entry.getValue();
            if (track.pick != NEVER_PICKED && track.pickedAt == 0) {
                // Played before the time was recorded: the wait starts now
                track.pickedAt = now;
            }
            if (available.contains(entry.getKey()) || track.lastSeen == 0) {
                track.lastSeen = now;
            } else if (now - track.lastSeen > FORGET_MS) {
                it.remove();
            }
        }
        for (String uri : available) {
            if (!deck.tracks.containsKey(uri)) {
                Track track = new Track();
                track.firstSeen = now;
                track.lastSeen = now;
                deck.tracks.put(uri, track);
            }
        }
        deck.queue.retainAll(available);
        deck.fresh.retainAll(available);
        List<String> unplayed = new ArrayList<>();
        for (Iterator<String> it = deck.fresh.iterator(); it.hasNext(); ) {
            String uri = it.next();
            if (!newEnabled || !isNew(deck.tracks.get(uri), now)) {
                it.remove();
                unplayed.add(uri);
            }
        }
        Set<String> placed = new HashSet<>(deck.queue);
        placed.addAll(deck.fresh);
        long wait = waitPicks(context, available.size());
        List<String> fresh = new ArrayList<>();
        List<String> released = new ArrayList<>();
        for (String uri : available) {
            if (placed.contains(uri)) {
                continue;
            }
            Track track = deck.tracks.get(uri);
            if (track.pick == NEVER_PICKED) {
                (newEnabled && isNew(track, now) ? fresh : unplayed).add(uri);
            } else if (deck.picks - track.pick >= wait || now - track.pickedAt >= FORGET_MS) {
                released.add(uri);
            }
        }
        Collections.shuffle(fresh, RANDOM);
        for (String uri : fresh) {
            deck.fresh.add(RANDOM.nextInt(deck.fresh.size() + 1), uri);
        }
        Collections.shuffle(unplayed, RANDOM);
        for (String uri : unplayed) {
            deck.queue.add(RANDOM.nextInt(deck.queue.size() + 1), uri);
        }
        // Among the last quarter, so a track already in the queue moves ahead about once per pick
        Collections.sort(released, (a, b) -> Long.compare(deck.tracks.get(a).pick, deck.tracks.get(b).pick));
        for (String uri : released) {
            int size = deck.queue.size();
            deck.queue.add(size - RANDOM.nextInt(size / 4 + 1), uri);
        }
        if (!fresh.isEmpty() || !released.isEmpty()) {
            Log.d(TAG, fresh.size() + " new tracks, " + released.size() + " tracks back in the queue");
        }
        return deck;
    }

    /** First run with a state file: carries over the played tracks remembered before, nothing is new. */
    private static Deck carryOver(Context context, Set<String> available) {
        Deck deck = new Deck();
        for (String uri : MusicBreakPreferences.getPlayedTracks(context)) {
            if (available.contains(uri)) {
                Track track = new Track();
                deck.picks++;
                track.pick = deck.picks;
                deck.tracks.put(uri, track);
            }
        }
        for (String uri : available) {
            if (!deck.tracks.containsKey(uri)) {
                deck.tracks.put(uri, new Track());
            }
        }
        return deck;
    }

    private static boolean isNew(Track track, long now) {
        return track.pick == NEVER_PICKED && track.firstSeen > 0 && now - track.firstSeen < NEW_MS;
    }

    /** Number of picks a played track waits before it can play again. */
    private static long waitPicks(Context context, int poolSize) {
        long wait = (long) poolSize * MusicBreakPreferences.getRepeatPercent(context) / 100;
        return Math.max(1, Math.min(wait, poolSize - 1));
    }

    private static Set<String> available(List<Uri> tracks) {
        Set<String> result = new LinkedHashSet<>();
        for (Uri track : tracks) {
            result.add(track.toString());
        }
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
            return json.has("played") ? readRound(json) : readDeck(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Unable to read the round, starting a new one", e);
            return null;
        }
    }

    private static Deck readDeck(JSONObject json) throws JSONException {
        Deck deck = new Deck();
        deck.picks = json.getLong("picks");
        deck.credit = json.getInt("credit");
        JSONArray queue = json.getJSONArray("queue");
        for (int i = 0; i < queue.length(); i++) {
            deck.queue.add(queue.getString(i));
        }
        JSONArray fresh = json.getJSONArray("fresh");
        for (int i = 0; i < fresh.length(); i++) {
            deck.fresh.add(fresh.getString(i));
        }
        JSONObject tracks = json.getJSONObject("tracks");
        Iterator<String> keys = tracks.keys();
        while (keys.hasNext()) {
            String uri = keys.next();
            JSONObject entry = tracks.getJSONObject(uri);
            Track track = new Track();
            track.firstSeen = entry.getLong("first");
            track.lastSeen = entry.optLong("seen");
            track.pick = entry.getLong("pick");
            track.pickedAt = entry.optLong("picked");
            track.playedAt = entry.getLong("at");
            track.name = entry.has("name") ? entry.getString("name") : null;
            deck.tracks.put(uri, track);
        }
        return deck;
    }

    /** Reads the earlier format, one shuffled round: the played tracks keep their order, nothing is new. */
    private static Deck readRound(JSONObject json) throws JSONException {
        Deck deck = new Deck();
        JSONArray played = json.getJSONArray("played");
        for (int i = 0; i < played.length(); i++) {
            JSONObject entry = played.getJSONObject(i);
            Track track = new Track();
            deck.picks++;
            track.pick = deck.picks;
            track.playedAt = entry.getLong("at");
            deck.tracks.put(entry.getString("uri"), track);
        }
        JSONArray upcoming = json.getJSONArray("upcoming");
        for (int i = 0; i < upcoming.length(); i++) {
            deck.queue.add(upcoming.getString(i));
            deck.tracks.put(upcoming.getString(i), new Track());
        }
        JSONObject names = json.getJSONObject("names");
        Iterator<String> keys = names.keys();
        while (keys.hasNext()) {
            String uri = keys.next();
            Track track = deck.tracks.get(uri);
            if (track != null) {
                track.name = names.getString(uri);
            }
        }
        return deck;
    }

    private static void write(Context context, Deck deck) {
        try {
            JSONObject tracks = new JSONObject();
            for (Map.Entry<String, Track> entry : deck.tracks.entrySet()) {
                Track track = entry.getValue();
                JSONObject json = new JSONObject()
                        .put("first", track.firstSeen)
                        .put("seen", track.lastSeen)
                        .put("pick", track.pick)
                        .put("picked", track.pickedAt)
                        .put("at", track.playedAt);
                if (track.name != null) {
                    json.put("name", track.name);
                }
                tracks.put(entry.getKey(), json);
            }
            JSONObject json = new JSONObject()
                    .put("picks", deck.picks)
                    .put("credit", deck.credit)
                    .put("queue", new JSONArray(deck.queue))
                    .put("fresh", new JSONArray(deck.fresh))
                    .put("tracks", tracks);
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
