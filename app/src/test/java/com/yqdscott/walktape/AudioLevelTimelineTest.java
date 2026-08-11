package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AudioLevelTimelineTest {
    @Test
    public void finalPcmPeaksAreRetrievedAtTheirAudibleMediaTime() {
        AudioLevelTimeline timeline = new AudioLevelTimeline();
        float[] pcm = new float[960 * 2];
        for (int frame = 0; frame < 480; frame++) {
            pcm[frame * 2] = 0.25f;
            pcm[frame * 2 + 1] = -0.50f;
        }
        for (int frame = 480; frame < 960; frame++) {
            pcm[frame * 2] = -0.80f;
            pcm[frame * 2 + 1] = 0.10f;
        }
        timeline.recordPcm(2_000_000L, 48_000, pcm, 960);
        assertEquals(2, timeline.pointCountForTest());

        float[] levels = new float[2];
        timeline.sample(2_005_000L, levels);
        assertEquals(0.25f, levels[0], 0f);
        assertEquals(0.50f, levels[1], 0f);
        timeline.sample(2_015_000L, levels);
        assertEquals(0.80f, levels[0], 0f);
        assertEquals(0.10f, levels[1], 0f);
        timeline.sample(3_000_000L, levels);
        assertEquals(0f, levels[0], 0f);
        assertEquals(0f, levels[1], 0f);
    }

    @Test
    public void d6cFiveStepScaleUsesRealThresholdsAndEitherChannelCanDriveIt() {
        assertEquals(0, WalkTapeView.d6cMeterSegmentCountForTest(0f));
        assertEquals(1, WalkTapeView.d6cMeterSegmentCountForTest(
                (float) Math.pow(10.0, -16.0 / 20.0)));
        assertEquals(3, WalkTapeView.d6cMeterSegmentCountForTest(
                (float) Math.pow(10.0, -6.0 / 20.0)));
        assertEquals(4, WalkTapeView.d6cMeterSegmentCountForTest(
                (float) Math.pow(10.0, -3.0 / 20.0)));
        assertEquals(5, WalkTapeView.d6cMeterSegmentCountForTest(1f));
    }
}
