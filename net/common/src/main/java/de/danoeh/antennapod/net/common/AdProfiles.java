package de.danoeh.antennapod.net.common;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.danoeh.antennapod.storage.preferences.SynchronizationCredentials;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Ad profiles of the podcast analysis next to the sync server (oPodSync, port 8089). Profiles are
 * kept on the phone as they are fetched, so ads are skipped in flight mode too.
 */
public class AdProfiles {
    private static final int PORT = 8089;
    private static final String DIR = "adprofiles";

    private AdProfiles() {
    }

    @Nullable
    public static HttpUrl getServer() {
        String host = SynchronizationCredentials.getHosturl();
        if (host == null || host.isEmpty()) {
            return null;
        }
        HttpUrl url = HttpUrl.parse(host.contains("://") ? host : "https://" + host);
        return url == null ? null : url.newBuilder().port(PORT).encodedPath("/").build();
    }

    /** Fetches the profile of the episode from the server and keeps it. */
    @NonNull
    public static JSONObject fetch(@NonNull Context context, @NonNull String episodeUrl) throws Exception {
        HttpUrl server = getServer();
        if (server == null) {
            throw new IOException("No sync server");
        }
        String key = key(episodeUrl);
        Request request = new Request.Builder().url(server.newBuilder().addPathSegment(key + ".json").build()).build();
        String body;
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            body = response.body().string();
        }
        JSONObject profile = new JSONObject(body);
        File dir = new File(context.getFilesDir(), DIR);
        File temp = new File(dir, key + ".tmp");
        if (dir.mkdirs() || dir.isDirectory()) {
            try (OutputStream out = new FileOutputStream(temp)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(new File(dir, key + ".json"))) {
                throw new IOException("Unable to keep profile " + key);
            }
        }
        return profile;
    }

    /** The kept profile of the episode, null if it has not been fetched. */
    @Nullable
    public static JSONObject cached(@NonNull Context context, @NonNull String episodeUrl) {
        File file = new File(new File(context.getFilesDir(), DIR), key(episodeUrl) + ".json");
        if (!file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Removes kept profiles of episodes that are no longer downloaded. */
    public static void keepOnly(@NonNull Context context, @NonNull Set<String> episodeUrls) {
        Set<String> keep = new HashSet<>();
        for (String url : episodeUrls) {
            keep.add(key(url) + ".json");
        }
        File[] files = new File(context.getFilesDir(), DIR).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!keep.contains(file.getName())) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /** The server's name of the profile: SHA-1 of the episode URL in hex. */
    @NonNull
    static String key(@NonNull String episodeUrl) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(episodeUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
