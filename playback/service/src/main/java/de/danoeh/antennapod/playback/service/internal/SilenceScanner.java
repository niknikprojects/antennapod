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
import java.util.Collections;
import java.util.List;

/**
 * Decodes a part of an audio file to find the silent intervals of a downloaded episode, in episode time,
 * or the average level of speech or music. Besides pauses it finds jingles (a few seconds of steady level,
 * unlike speech) and stereo stings in otherwise mono speech, which often mark a topic change.
 */
public class SilenceScanner {
    private static final String TAG = "SilenceScanner";
    private static final long FRAME_US = 20_000;
    private static final long MIN_SILENCE_MS = 400;
    private static final double MAX_THRESHOLD_DB = -30;
    private static final double NOISE_FLOOR_MARGIN_DB = 6;
    private static final long TIMEOUT_US = 10_000;
    private static final long MAX_SCAN_TIME_MS = 20_000;
    private static final double LEVEL_GATE_DB = -45;
    private static final double NO_SIDE_DB = -120;
    private static final int BLOCK_FRAMES = 25;
    private static final int STEADY_WINDOW_FRAMES = 50;
    private static final double STEADY_MAX_STD_DB = 4.5;
    private static final int JINGLE_MIN_BLOCKS = 4;
    private static final double STEREO_MARGIN_DB = 20;
    private static final int STEREO_MIN_BLOCKS = 2;

    public static final int KIND_PAUSE = 0;
    public static final int KIND_STEREO = 1;
    public static final int KIND_JINGLE = 2;

    public static class Silence {
        public final long startMs;
        public final long endMs;
        public final int kind;

        Silence(long startMs, long endMs, int kind) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.kind = kind;
        }

        public long getLength() {
            return endMs - startMs;
        }

        @NonNull
        @Override
        public String toString() {
            return (kind == KIND_JINGLE ? "jingle " : kind == KIND_STEREO ? "stereo " : "") + startMs + "-" + endMs;
        }
    }

    @NonNull
    public static List<Silence> scan(@NonNull Context context, @NonNull FeedMedia media, long fromMs, long toMs) {
        if (!media.localFileAvailable()) {
            return new ArrayList<>();
        }
        List<long[]> levels = decode(context, media.getLocalFileUrl(), fromMs, toMs);
        List<Silence> silences = findSilences(levels);
        silences.addAll(findMarkers(levels));
        Log.d(TAG, "Silences between " + fromMs + " and " + toMs + ": " + silences);
        return silences;
    }

    /**
     * Returns the average level in dBFS of the non-silent parts between the given positions,
     * or NaN if the file can not be decoded.
     */
    public static double measureLevel(@NonNull Context context, @NonNull String file, long fromMs, long toMs) {
        double energySum = 0;
        int frames = 0;
        for (long[] level : decode(context, file, fromMs, toMs)) {
            double db = level[1] / 100.0;
            if (db > LEVEL_GATE_DB) {
                energySum += Math.pow(10, db / 10);
                frames++;
            }
        }
        double result = frames == 0 ? Double.NaN : 10 * Math.log10(energySum / frames);
        Log.d(TAG, "Level of " + file + " between " + fromMs + " and " + toMs + ": " + result + " dB");
        return result;
    }

    private static List<long[]> decode(Context context, String file, long fromMs, long toMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
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
            return decodeLevels(extractor, codec, fromMs * 1000, toMs * 1000);
        } catch (Exception e) {
            Log.e(TAG, "Unable to decode " + file, e);
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
     * Returns frame start time in microseconds, frame level in hundredths of dBFS and the level of the side
     * signal relative to the mid signal in hundredths of dB.
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
        double midSum = 0;
        double sideSum = 0;
        double left = 0;
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
                        if (channels == 2 && frameSamples % 2 == 0) {
                            left = sample;
                        } else if (channels == 2) {
                            midSum += (left + sample) * (left + sample) / 4;
                            sideSum += (left - sample) * (left - sample) / 4;
                        }
                        frameSamples++;
                        index++;
                        if (frameSamples >= samplesPerFrame) {
                            double rms = Math.sqrt(frameSum / frameSamples);
                            double db = 20 * Math.log10(Math.max(rms, 1e-6));
                            double sideDb = channels != 2 ? NO_SIDE_DB : Math.max(NO_SIDE_DB,
                                    10 * Math.log10(Math.max(sideSum, 1e-14) / Math.max(midSum, 1e-12)));
                            levels.add(new long[]{frameStartUs, Math.round(db * 100), Math.round(sideDb * 100)});
                            frameStartUs = -1;
                            frameSum = 0;
                            midSum = 0;
                            sideSum = 0;
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
            silences.add(new Silence(startUs / 1000, endUs / 1000, KIND_PAUSE));
        }
    }

    private static List<Silence> findMarkers(List<long[]> levels) {
        List<Silence> markers = new ArrayList<>();
        int blocks = levels.size() / BLOCK_FRAMES;
        double[] blockLevel = new double[blocks];
        double[] blockSide = new double[blocks];
        boolean[] steady = new boolean[blocks];
        List<Double> loudSides = new ArrayList<>();
        for (int b = 0; b < blocks; b++) {
            blockLevel[b] = median(levels, b * BLOCK_FRAMES, (b + 1) * BLOCK_FRAMES, 1);
            blockSide[b] = median(levels, b * BLOCK_FRAMES, (b + 1) * BLOCK_FRAMES, 2);
            int from = Math.max(0, b * BLOCK_FRAMES - STEADY_WINDOW_FRAMES / 4);
            int to = Math.min(levels.size(), from + STEADY_WINDOW_FRAMES);
            steady[b] = blockLevel[b] > LEVEL_GATE_DB && standardDeviation(levels, from, to) < STEADY_MAX_STD_DB;
            if (blockLevel[b] > LEVEL_GATE_DB) {
                loudSides.add(blockSide[b]);
            }
        }
        if (loudSides.isEmpty()) {
            return markers;
        }
        Collections.sort(loudSides);
        double baseSide = loudSides.get(loudSides.size() / 2);
        boolean[] stereo = new boolean[blocks];
        for (int b = 0; b < blocks; b++) {
            stereo[b] = blockLevel[b] > LEVEL_GATE_DB && blockSide[b] > baseSide + STEREO_MARGIN_DB;
        }
        addRuns(markers, levels, steady, JINGLE_MIN_BLOCKS, KIND_JINGLE);
        addRuns(markers, levels, stereo, STEREO_MIN_BLOCKS, KIND_STEREO);
        return markers;
    }

    private static void addRuns(List<Silence> markers, List<long[]> levels, boolean[] blocks, int minBlocks,
                                int kind) {
        int runStart = -1;
        for (int b = 0; b <= blocks.length; b++) {
            boolean inRun = b < blocks.length && blocks[b];
            if (inRun && runStart < 0) {
                runStart = b;
            } else if (!inRun && runStart >= 0) {
                if (b - runStart >= minBlocks) {
                    long startUs = levels.get(runStart * BLOCK_FRAMES)[0];
                    long endUs = levels.get(b * BLOCK_FRAMES - 1)[0] + FRAME_US;
                    markers.add(new Silence(startUs / 1000, endUs / 1000, kind));
                }
                runStart = -1;
            }
        }
    }

    private static double median(List<long[]> levels, int from, int to, int field) {
        long[] values = new long[to - from];
        for (int i = from; i < to; i++) {
            values[i - from] = levels.get(i)[field];
        }
        Arrays.sort(values);
        return values[values.length / 2] / 100.0;
    }

    private static double standardDeviation(List<long[]> levels, int from, int to) {
        double sum = 0;
        double sumSquares = 0;
        for (int i = from; i < to; i++) {
            double db = levels.get(i)[1] / 100.0;
            sum += db;
            sumSquares += db * db;
        }
        double mean = sum / (to - from);
        return Math.sqrt(Math.max(0, sumSquares / (to - from) - mean * mean));
    }
}
