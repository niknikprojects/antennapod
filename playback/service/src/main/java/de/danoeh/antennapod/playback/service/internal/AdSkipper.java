package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.AdProfiles;
import de.danoeh.antennapod.storage.preferences.MusicBreakPreferences;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Skips ads that the podcast analysis next to the sync server (oPodSync, port 8089) found by
 * downloading the episode with three different user agents: frames that all copies share are the
 * episode itself. The server sends an anchor of four frame hashes per block of the episode, so the
 * blocks are found in the downloaded file, which has ads of its own with other lengths. Everything
 * between two blocks is an ad. With music breaks and "replace ads" on, MusicBreak plays a track
 * there instead, otherwise the ad is skipped. Profiles are kept on the phone (AdProfiles), so this
 * works offline once the profile was fetched after a sync.
 */
public class AdSkipper {
    private static final String TAG = "AdSkipper";
    private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_TRIES = 12;
    private static final long MIN_AD_MS = 3000;
    private static final long END_MARGIN_MS = 1000;
    private static final int[][] BITRATES = {
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};
    private static final int[][] RATES = {{11025, 12000, 8000}, {}, {22050, 24000, 16000}, {44100, 48000, 32000}};

    private final Context context;
    private final Player episodePlayer;
    private long mediaId = -1;
    private List<long[]> ads = new ArrayList<>();
    private final Set<Long> handledStarts = new HashSet<>();
    private int tries = 0;
    private long nextTryAt = 0;
    @Nullable
    private Disposable loadDisposable;

    public AdSkipper(@NonNull Context context, @NonNull Player episodePlayer) {
        this.context = context.getApplicationContext();
        this.episodePlayer = episodePlayer;
    }

    /** Ad ranges [start, end] in ms of the media, empty until they are loaded. */
    @NonNull
    public List<long[]> getAds(long forMediaId) {
        return forMediaId == mediaId ? ads : new ArrayList<>();
    }

    public void onPlaybackTick(@Nullable FeedMedia media, boolean musicReplacesAds) {
        if (media == null || !MusicBreakPreferences.isSkipAds(context)) {
            return;
        }
        if (media.getId() != mediaId) {
            reset(media.getId());
        }
        if (tries < MAX_TRIES && loadDisposable == null && System.currentTimeMillis() >= nextTryAt) {
            load(media);
        }
        if (musicReplacesAds || !episodePlayer.isPlaying()) {
            return;
        }
        long position = episodePlayer.getCurrentPosition();
        for (long[] ad : ads) {
            if (position >= ad[0] && position < ad[1] - END_MARGIN_MS && !handledStarts.contains(ad[0])) {
                // Once per ad, so seeking back into it plays it
                handledStarts.add(ad[0]);
                Log.d(TAG, "Skipping ad " + ad[0] + "-" + ad[1]);
                episodePlayer.seekTo(ad[1]);
                return;
            }
        }
    }

    private void reset(long newMediaId) {
        release();
        mediaId = newMediaId;
        ads = new ArrayList<>();
        handledStarts.clear();
        tries = 0;
        nextTryAt = 0;
    }

    private void load(FeedMedia media) {
        String file = media.getLocalFileUrl();
        String url = media.getDownloadUrl();
        if (!media.localFileAvailable() || file == null || url == null || AdProfiles.getServer() == null) {
            tries = MAX_TRIES;
            return;
        }
        tries++;
        long loadingId = media.getId();
        loadDisposable = Single.fromCallable(() -> {
            // A kept profile works offline, it was fetched after the sync or when last played
            JSONObject profile = AdProfiles.cached(context, url);
            if (profile == null) {
                profile = AdProfiles.fetch(context, url);
            }
            return findAds(profile, new File(file));
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(found -> {
                    loadDisposable = null;
                    if (loadingId != mediaId) {
                        return;
                    }
                    tries = MAX_TRIES;
                    ads = found;
                    StringBuilder log = new StringBuilder();
                    for (long[] ad : found) {
                        log.append(' ').append(ad[0]).append('-').append(ad[1]);
                    }
                    Log.d(TAG, "Ads in " + url + ":" + log);
                }, error -> {
                    loadDisposable = null;
                    nextTryAt = System.currentTimeMillis() + RETRY_MS;
                    Log.d(TAG, "No ad profile yet for " + url + ": " + error.getMessage());
                });
    }

    /** Finds the server's blocks of the episode in the local file, the gaps between them are ads. */
    static List<long[]> findAds(JSONObject profile, File file) throws Exception {
        List<long[]> found = new ArrayList<>();
        JSONArray blocks = profile.optJSONArray("blocks");
        if (!profile.optBoolean("dai") || blocks == null || blocks.length() == 0) {
            return found;
        }
        Frames frames = Frames.read(file);
        long[] starts = new long[blocks.length()];
        long[] ends = new long[blocks.length()];
        for (int b = 0; b < blocks.length(); b++) {
            JSONObject block = blocks.getJSONObject(b);
            JSONArray anchor = block.getJSONArray("anchor");
            long[] want = new long[anchor.length()];
            for (int k = 0; k < want.length; k++) {
                String hex = anchor.getString(k);
                want[k] = (Long.parseLong(hex.substring(0, 8), 16) << 32) | Long.parseLong(hex.substring(8), 16);
            }
            int at = frames.find(want);
            starts[b] = -1;
            if (at >= 0) {
                int start = at - block.getInt("offset");
                starts[b] = start;
                ends[b] = start + block.getInt("frames");
            }
        }
        long minFrames = MIN_AD_MS * frames.rate / (1000L * frames.samplesPerFrame);
        if (starts[0] >= minFrames) {
            found.add(new long[] {0, frames.toMs(starts[0])});
        }
        for (int b = 0; b + 1 < starts.length; b++) {
            if (starts[b] >= 0 && starts[b + 1] >= 0 && starts[b + 1] - ends[b] >= minFrames) {
                found.add(new long[] {frames.toMs(ends[b]), frames.toMs(starts[b + 1])});
            }
        }
        int last = starts.length - 1;
        if (starts[last] >= 0 && frames.count - ends[last] >= minFrames) {
            found.add(new long[] {frames.toMs(ends[last]), frames.toMs(frames.count)});
        }
        return found;
    }

    public void release() {
        if (loadDisposable != null) {
            loadDisposable.dispose();
            loadDisposable = null;
        }
    }

    /**
     * MPEG audio layer III frames of a file, as the first 8 bytes of the MD5 of each frame without
     * its header. Same parsing as the server (podanalys.py): ID3v2 is skipped, a leading Xing/Info
     * frame does not count, like in ExoPlayer's timeline.
     */
    static class Frames {
        long[] hashes = new long[0];
        int count = 0;
        int samplesPerFrame = 1152;
        int rate = 44100;

        long toMs(long frame) {
            return frame * samplesPerFrame * 1000 / rate;
        }

        int find(long[] want) {
            for (int i = 0; i + want.length <= count; i++) {
                int k = 0;
                while (k < want.length && hashes[i + k] == want[k]) {
                    k++;
                }
                if (k == want.length) {
                    return i;
                }
            }
            return -1;
        }

        static Frames read(File file) throws Exception {
            Frames frames = new Frames();
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                    FileChannel channel = raf.getChannel()) {
                MappedByteBuffer data = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
                int n = data.limit();
                int i = 0;
                if (n >= 10 && data.get(0) == 'I' && data.get(1) == 'D' && data.get(2) == '3') {
                    i = 10 + (((data.get(6) & 0x7f) << 21) | ((data.get(7) & 0x7f) << 14)
                            | ((data.get(8) & 0x7f) << 7) | (data.get(9) & 0x7f));
                }
                long[] hashes = new long[(int) Math.min(Integer.MAX_VALUE - 8, n / 100 + 16)];
                int count = 0;
                byte[] head = new byte[40];
                while (i + 4 <= n) {
                    int b1 = data.get(i + 1) & 0xff;
                    if ((data.get(i) & 0xff) != 0xff || (b1 & 0xe0) != 0xe0) {
                        i++;
                        continue;
                    }
                    int version = (b1 >> 3) & 3;
                    int layer = (b1 >> 1) & 3;
                    int b2 = data.get(i + 2) & 0xff;
                    int bitrateIndex = b2 >> 4;
                    int rateIndex = (b2 >> 2) & 3;
                    if (layer != 1 || version == 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
                        i++;
                        continue;
                    }
                    int sampleRate = RATES[version][rateIndex];
                    int bitrate = BITRATES[version == 3 ? 0 : 1][bitrateIndex] * 1000;
                    int size = (version == 3 ? 144 : 72) * bitrate / sampleRate + ((b2 >> 1) & 1);
                    if (i + size > n) {
                        break;
                    }
                    if (count == 0) {
                        int len = Math.min(head.length, size - 4);
                        data.position(i + 4);
                        data.get(head, 0, len);
                        String start = new String(head, 0, len, StandardCharsets.ISO_8859_1);
                        if (start.contains("Xing") || start.contains("Info")) {
                            i += size;
                            continue;
                        }
                    }
                    data.limit(i + size).position(i + 4);
                    md5.update(data);
                    data.limit(n);
                    byte[] digest = md5.digest();
                    long hash = 0;
                    for (int k = 0; k < 8; k++) {
                        hash = (hash << 8) | (digest[k] & 0xff);
                    }
                    if (count == hashes.length) {
                        hashes = Arrays.copyOf(hashes, count * 2);
                    }
                    hashes[count++] = hash;
                    frames.samplesPerFrame = version == 3 ? 1152 : 576;
                    frames.rate = sampleRate;
                    i += size;
                }
                frames.hashes = hashes;
                frames.count = count;
            }
            return frames;
        }
    }
}
