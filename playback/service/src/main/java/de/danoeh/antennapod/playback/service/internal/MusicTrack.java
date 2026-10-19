package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;

public class MusicTrack {
    private static final String TAG = "MusicTrack";
    private static final int MAX_ARTWORK_SIZE = 512;
    private static final long LEVEL_SAMPLE_MS = 30_000;

    public final Uri uri;
    public final String title;
    @Nullable
    public final String artist;
    @Nullable
    public final String album;
    @Nullable
    public final byte[] artwork;
    /** Average level in dBFS of a part from the middle of the track, NaN if unknown. */
    public final double levelDb;

    private MusicTrack(Uri uri, String title, @Nullable String artist, @Nullable String album,
                       @Nullable byte[] artwork, double levelDb) {
        this.uri = uri;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.artwork = artwork;
        this.levelDb = levelDb;
    }

    public String getSubtitle() {
        if (!TextUtils.isEmpty(artist) && !TextUtils.isEmpty(album)) {
            return artist + " – " + album;
        }
        return !TextUtils.isEmpty(artist) ? artist : (album != null ? album : "");
    }

    @NonNull
    public static MusicTrack load(@NonNull Context context, @NonNull Uri uri) {
        String title = null;
        String artist = null;
        String album = null;
        byte[] artwork = null;
        long durationMs = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            artwork = scaleArtwork(retriever.getEmbeddedPicture());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            durationMs = duration != null ? Long.parseLong(duration) : 0;
        } catch (Exception e) {
            Log.w(TAG, "Unable to read metadata of " + uri, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.w(TAG, "Unable to release retriever", e);
            }
        }
        if (TextUtils.isEmpty(title)) {
            title = getFileName(context, uri);
        }
        long levelStart = Math.max(0, (durationMs - LEVEL_SAMPLE_MS) / 2);
        double levelDb = SilenceScanner.measureLevel(context, uri.toString(), levelStart, levelStart + LEVEL_SAMPLE_MS);
        return new MusicTrack(uri, title, artist, album, artwork, levelDb);
    }

    @Nullable
    private static byte[] scaleArtwork(@Nullable byte[] picture) {
        if (picture == null) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(picture, 0, picture.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= MAX_ARTWORK_SIZE) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
        if (bitmap == null) {
            return null;
        }
        float scale = Math.min(1f, (float) MAX_ARTWORK_SIZE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (scale < 1f) {
            bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale),
                    Math.round(bitmap.getHeight() * scale), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return out.toByteArray();
    }

    private static String getFileName(Context context, Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                name = cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to query name of " + uri, e);
        }
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
