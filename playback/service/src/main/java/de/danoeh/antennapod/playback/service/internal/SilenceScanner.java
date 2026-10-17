package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import de.danoeh.antennapod.model.feed.FeedMedia;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes a part of a downloaded episode and returns the silent intervals in it, in episode time.
 */
public class SilenceScanner {
    private static final String TAG = "SilenceScanner";
    private static final long FRAME_US = 20_000;
    private static final long MIN_SILENCE_MS = 400;
    private static final double MAX_THRESHOLD_DB = -30;
    private static final double NOISE_FLOOR_MARGIN_DB = 6;
    private static final long TIMEOUT_US = 10_000;
    private static final long MAX_SCAN_TIME_MS = 20_000;

    public static class Silence {
        public final long startMs;
        public final long endMs;

        Silence(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public long getLength() {
            return endMs - startMs;
        }

        @NonNull
        @Override
        public String toString() {
            return startMs + "-" + endMs;
        }
    }

    @NonNull
    public static List<Silence> scan(@NonNull Context context, @NonNull FeedMedia media, long fromMs, long toMs) {
        if (!media.localFileAvailable()) {
            return new ArrayList<>();
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            String file = media.getLocalFileUrl();
            if (file.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(file), null);
            } else {
                extractor.setDataSource(file);
            }
            int track = findAudioTrack(extractor);
            if (track < 0) {
                return new ArrayList<>();
            }
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            List<long[]> levels = decodeLevels(extractor, codec, fromMs * 1000, toMs * 1000);
            List<Silence> silences = findSilences(levels);
            Log.d(TAG, "Silences between " + fromMs + " and " + toMs + ": " + silences);
            return silences;
        } catch (Exception e) {
            Log.e(TAG, "Unable to scan for silence", e);
            return new ArrayList<>();
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Unable to stop codec", e);
                }
                codec.release();
            }
            extractor.release();
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns pairs of frame start time in microseconds and frame level in hundredths of dBFS.
     */
    private static List<long[]> decodeLevels(MediaExtractor extractor, MediaCodec codec, long fromUs, long toUs) {
        List<long[]> levels = new ArrayList<>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int channels = 1;
        int sampleRate = 44100;
        boolean inputDone = false;
        long deadline = System.currentTimeMillis() + MAX_SCAN_TIME_MS;
        long frameStartUs = -1;
        double frameSum = 0;
        int frameSamples = 0;
        while (System.currentTimeMillis() < deadline) {
            if (!inputDone) {
                int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer input = codec.getInputBuffer(inputIndex);
                    int size = input == null ? -1 : extractor.readSampleData(input, 0);
                    if (size < 0 || extractor.getSampleTime() > toUs) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = codec.getOutputFormat();
                if (Build.VERSION.SDK_INT >= 24 && outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        && outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                    Log.w(TAG, "Unsupported PCM encoding");
                    return new ArrayList<>();
                }
                channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            } else if (outputIndex >= 0) {
                ByteBuffer output = codec.getOutputBuffer(outputIndex);
                if (output != null && info.size > 0 && info.presentationTimeUs >= fromUs) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    ShortBuffer samples = output.order(ByteOrder.nativeOrder()).asShortBuffer();
                    long samplesPerFrame = sampleRate * FRAME_US / 1_000_000 * channels;
                    int index = 0;
                    while (samples.hasRemaining()) {
                        if (frameStartUs < 0) {
                            frameStartUs = info.presentationTimeUs + index / channels * 1_000_000L / sampleRate;
                        }
                        double sample = samples.get() / 32768.0;
                        frameSum += sample * sample;
                        frameSamples++;
                        index++;
                        if (frameSamples >= samplesPerFrame) {
                            double rms = Math.sqrt(frameSum / frameSamples);
                            double db = 20 * Math.log10(Math.max(rms, 1e-6));
                            levels.add(new long[]{frameStartUs, Math.round(db * 100)});
                            frameStartUs = -1;
                            frameSum = 0;
                            frameSamples = 0;
                        }
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
        return levels;
    }

    private static List<Silence> findSilences(List<long[]> levels) {
        List<Silence> silences = new ArrayList<>();
        if (levels.isEmpty()) {
            return silences;
        }
        long[] sorted = new long[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            sorted[i] = levels.get(i)[1];
        }
        Arrays.sort(sorted);
        double noiseFloorDb = sorted[sorted.length / 10] / 100.0;
        double thresholdDb = Math.min(noiseFloorDb + NOISE_FLOOR_MARGIN_DB, MAX_THRESHOLD_DB);
        Log.d(TAG, "Noise floor " + noiseFloorDb + " dB, silence threshold " + thresholdDb + " dB");
        long runStartUs = -1;
        for (long[] level : levels) {
            boolean silent = level[1] / 100.0 < thresholdDb;
            if (silent && runStartUs < 0) {
                runStartUs = level[0];
            } else if (!silent && runStartUs >= 0) {
                addIfLongEnough(silences, runStartUs, level[0]);
                runStartUs = -1;
            }
        }
        if (runStartUs >= 0) {
            addIfLongEnough(silences, runStartUs, levels.get(levels.size() - 1)[0] + FRAME_US);
        }
        return silences;
    }

    private static void addIfLongEnough(List<Silence> silences, long startUs, long endUs) {
        if ((endUs - startUs) / 1000 >= MIN_SILENCE_MS) {
            silences.add(new Silence(startUs / 1000, endUs / 1000));
        }
    }
}
