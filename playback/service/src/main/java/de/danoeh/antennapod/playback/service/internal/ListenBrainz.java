package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.storage.preferences.MusicBreakPreferences;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Submits listens of music break tracks to ListenBrainz. Listens are kept in a queue file and sent
 * by a worker when the network is available, so listens made offline are submitted later.
 */
public class ListenBrainz {
    private static final String TAG = "ListenBrainz";
    private static final String SUBMIT_URL = "https://api.listenbrainz.org/1/submit-listens";
    private static final String QUEUE_FILE = "listenbrainz-queue.json";
    private static final String CLIENT_NAME = "AntennaPod musikpaus";
    private static final int MAX_LISTENS_PER_REQUEST = 100;
    private static final int MAX_QUEUE_SIZE = 5000;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Object QUEUE_LOCK = new Object();

    public static void submitPlayingNow(@NonNull Context context, @NonNull MusicTrack track, long durationMs) {
        if (!canSubmit(context, track)) {
            return;
        }
        try {
            JSONObject listen = new JSONObject().put("track_metadata", buildMetadata(track, durationMs));
            int code = post(context, "playing_now", new JSONArray().put(listen));
            Log.d(TAG, "Playing now submitted: " + code);
        } catch (IOException | JSONException e) {
            Log.d(TAG, "Unable to submit playing now", e);
        }
    }

    public static void submitListen(@NonNull Context context, @NonNull MusicTrack track,
                                    long listenedAtSeconds, long durationMs) {
        if (!canSubmit(context, track)) {
            return;
        }
        try {
            JSONObject listen = new JSONObject()
                    .put("listened_at", listenedAtSeconds)
                    .put("track_metadata", buildMetadata(track, durationMs));
            synchronized (QUEUE_LOCK) {
                JSONArray queue = readQueue(context);
                queue.put(listen);
                while (queue.length() > MAX_QUEUE_SIZE) {
                    queue.remove(0);
                }
                writeQueue(context, queue);
            }
            Log.d(TAG, "Queued listen of " + track.title);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Unable to queue listen", e);
            return;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SubmitWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    private static boolean canSubmit(Context context, MusicTrack track) {
        return !TextUtils.isEmpty(MusicBreakPreferences.getListenBrainzToken(context))
                && !TextUtils.isEmpty(track.artist) && !TextUtils.isEmpty(track.title);
    }

    private static JSONObject buildMetadata(MusicTrack track, long durationMs) throws JSONException {
        JSONObject info = new JSONObject()
                .put("media_player", CLIENT_NAME)
                .put("submission_client", CLIENT_NAME);
        if (durationMs > 0) {
            info.put("duration_ms", durationMs);
        }
        JSONObject metadata = new JSONObject()
                .put("artist_name", track.artist)
                .put("track_name", track.title)
                .put("additional_info", info);
        if (!TextUtils.isEmpty(track.album)) {
            metadata.put("release_name", track.album);
        }
        return metadata;
    }

    private static int post(Context context, String listenType, JSONArray listens)
            throws IOException, JSONException {
        String body = new JSONObject().put("listen_type", listenType).put("payload", listens).toString();
        Request request = new Request.Builder()
                .url(SUBMIT_URL)
                .header("Authorization", "Token " + MusicBreakPreferences.getListenBrainzToken(context))
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "ListenBrainz answered " + response.code() + ": "
                        + (response.body() != null ? response.body().string() : ""));
            }
            return response.code();
        }
    }

    private static JSONArray readQueue(Context context) throws IOException, JSONException {
        File file = new File(context.getFilesDir(), QUEUE_FILE);
        if (!file.exists()) {
            return new JSONArray();
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new JSONArray(out.toString(StandardCharsets.UTF_8.name()));
        }
    }

    private static void writeQueue(Context context, JSONArray queue) throws IOException {
        File file = new File(context.getFilesDir(), QUEUE_FILE);
        File temp = new File(context.getFilesDir(), QUEUE_FILE + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(queue.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(file)) {
            throw new IOException("Unable to replace " + file);
        }
    }

    public static class SubmitWorker extends Worker {
        public SubmitWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @NonNull
        @Override
        public Result doWork() {
            Context context = getApplicationContext();
            if (TextUtils.isEmpty(MusicBreakPreferences.getListenBrainzToken(context))) {
                return Result.success();
            }
            try {
                while (true) {
                    JSONArray batch = new JSONArray();
                    synchronized (QUEUE_LOCK) {
                        JSONArray queue = readQueue(context);
                        for (int i = 0; i < queue.length() && i < MAX_LISTENS_PER_REQUEST; i++) {
                            batch.put(queue.get(i));
                        }
                    }
                    if (batch.length() == 0) {
                        return Result.success();
                    }
                    int code = post(context, batch.length() == 1 ? "single" : "import", batch);
                    if (code == 401) {
                        return Result.failure();
                    } else if (code == 429 || code >= 500) {
                        return Result.retry();
                    }
                    if (code != 200) {
                        Log.e(TAG, "Dropping " + batch.length() + " listens rejected with " + code);
                    }
                    synchronized (QUEUE_LOCK) {
                        JSONArray queue = readQueue(context);
                        JSONArray rest = new JSONArray();
                        for (int i = batch.length(); i < queue.length(); i++) {
                            rest.put(queue.get(i));
                        }
                        writeQueue(context, rest);
                    }
                    Log.d(TAG, "Submitted " + batch.length() + " listens");
                }
            } catch (IOException e) {
                Log.d(TAG, "Unable to submit listens, retrying later", e);
                return Result.retry();
            } catch (JSONException e) {
                Log.e(TAG, "Broken listen queue", e);
                return Result.failure();
            }
        }
    }
}
