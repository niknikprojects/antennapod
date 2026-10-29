package de.danoeh.antennapod.net.sync.service;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.net.common.AdProfiles;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.MusicBreakPreferences;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Fetches the ad profiles of downloaded episodes after a sync, so ads are skipped in flight mode.
 * The server analyses an episode some minutes after the sync told it about the download, so
 * missing profiles are tried again every 10 minutes for an hour.
 */
public class AdProfileWorker extends Worker {
    private static final String TAG = "AdProfileWorker";
    private static final String WORK_ID = "AdProfileWorker";
    private static final String KEY_ROUND = "round";
    private static final int ROUNDS = 7;
    private static final long ROUND_DELAY_MIN = 10;
    // The server only analyses episodes downloaded the last three days
    private static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(3);

    public AdProfileWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** After a sync: starts over from the first round. */
    public static void enqueue(@NonNull Context context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_ID, ExistingWorkPolicy.REPLACE, request(0, 0));
    }

    private static OneTimeWorkRequest request(int round, long delayMinutes) {
        return new OneTimeWorkRequest.Builder(AdProfileWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(new Data.Builder().putInt(KEY_ROUND, round).build())
                .build();
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!MusicBreakPreferences.isSkipAds(context) || AdProfiles.getServer() == null) {
            return Result.success();
        }
        Set<String> downloaded = new HashSet<>();
        int fetched = 0;
        int missing = 0;
        for (FeedItem item : DBReader.getEpisodes(0, Integer.MAX_VALUE,
                new FeedItemFilter(FeedItemFilter.DOWNLOADED), SortOrder.DATE_NEW_OLD)) {
            FeedMedia media = item.getMedia();
            if (media == null || media.getDownloadUrl() == null || !media.localFileAvailable()) {
                continue;
            }
            String url = media.getDownloadUrl();
            downloaded.add(url);
            if (AdProfiles.cached(context, url) != null
                    || System.currentTimeMillis() - media.getDownloadDate() > MAX_AGE_MS) {
                continue;
            }
            try {
                AdProfiles.fetch(context, url);
                fetched++;
            } catch (Exception e) {
                missing++;
            }
        }
        AdProfiles.keepOnly(context, downloaded);
        int round = getInputData().getInt(KEY_ROUND, 0);
        Log.d(TAG, "Round " + round + ": fetched " + fetched + ", missing " + missing);
        if (missing > 0 && round + 1 < ROUNDS) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_ID, ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request(round + 1, ROUND_DELAY_MIN));
        }
        return Result.success();
    }
}
