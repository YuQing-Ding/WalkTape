package com.yqdscott.walktape;

import static org.junit.Assert.assertTrue;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.beatofthedrum.alacdecoder.AlacFrameDecoder;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;

/** Device-only proof that a real MediaStore ALAC file can be parsed and decoded without MediaCodec. */
@RunWith(AndroidJUnit4.class)
public class AlacDeviceTest {

    @Test
    public void decodesARealAlacFileFromMediaStore() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String requestedUri = InstrumentationRegistry.getArguments().getString("alacUri");
        Uri alacUri = requestedUri == null
                ? findAlacUri(context)
                : Uri.parse(requestedUri);
        Assume.assumeNotNull(alacUri);

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, alacUri, null);
            MediaFormat format = null;
            int trackIndex = -1;
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat candidate = extractor.getTrackFormat(track);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if ("audio/alac".equals(mime) || "audio/x-alac".equals(mime)) {
                    format = candidate;
                    trackIndex = track;
                    break;
                }
            }
            assertTrue("Expected an ALAC audio track", trackIndex >= 0 && format != null);
            extractor.selectTrack(trackIndex);

            ByteBuffer codecData = format.getByteBuffer("csd-0");
            assertTrue("Expected ALAC codec configuration", codecData != null);
            ByteBuffer configView = codecData.duplicate();
            byte[] config = new byte[configView.remaining()];
            configView.get(config);
            AlacFrameDecoder alac = new AlacFrameDecoder(config);
            int bits = alac.getBitsPerSample();
            assertTrue(bits == 16 || bits == 24);

            ByteBuffer packetBuffer = ByteBuffer.allocateDirect(256 * 1024);
            byte[] packet = new byte[256 * 1024];
            int[] decoded = new int[73_728];
            byte[] pcm = new byte[73_728];
            long decodedBytes = 0L;
            int packets = 0;
            long startedMs = SystemClock.elapsedRealtime();
            while (packets < 200) {
                packetBuffer.clear();
                int packetSize = extractor.readSampleData(packetBuffer, 0);
                if (packetSize < 0) {
                    break;
                }
                assertTrue("ALAC packet exceeded the test buffer", packetSize <= packet.length);
                packetBuffer.position(0);
                packetBuffer.limit(packetSize);
                packetBuffer.get(packet, 0, packetSize);

                int bytes = alac.decode(packet, packetSize, decoded);
                assertTrue("Expected a decoded ALAC packet", bytes > 0);
                PlaybackController.packAlacPcm(
                        decoded, bytes, alac.getBytesPerSample(), pcm);
                decodedBytes += bytes;
                packets++;
                extractor.advance();
            }
            long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - startedMs);
            long audioMs = decodedBytes * 1_000L
                    / (alac.getSampleRate() * alac.getChannelCount() * alac.getBytesPerSample());
            assertTrue("Expected multiple ALAC packets", packets >= 20);
            assertTrue("ALAC decoder must stay ahead of real-time playback: decoded "
                    + audioMs + " ms in " + elapsedMs + " ms", elapsedMs < audioMs);
        } finally {
            extractor.release();
        }
    }

    private static Uri findAlacUri(Context context) throws Exception {
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] projection = {MediaStore.Audio.Media._ID};
        try (Cursor cursor = context.getContentResolver().query(
                collection,
                projection,
                MediaStore.Audio.Media.IS_MUSIC + " != 0",
                null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                return null;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            while (cursor.moveToNext()) {
                Uri candidate = ContentUris.withAppendedId(collection, cursor.getLong(idColumn));
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(context, candidate, null);
                    for (int track = 0; track < extractor.getTrackCount(); track++) {
                        MediaFormat format = extractor.getTrackFormat(track);
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        if ("audio/alac".equals(mime) || "audio/x-alac".equals(mime)) {
                            return candidate;
                        }
                    }
                } catch (Exception ignored) {
                    // A damaged unrelated library item must not prevent finding the next ALAC file.
                } finally {
                    extractor.release();
                }
            }
        }
        return null;
    }
}
