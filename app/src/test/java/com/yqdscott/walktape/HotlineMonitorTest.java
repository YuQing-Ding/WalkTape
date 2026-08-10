package com.yqdscott.walktape;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HotlineMonitorTest {

    @Test
    public void electretModelRejectsDcButKeepsSpeechBand() {
        short[] dc = new short[8_192];
        for (int index = 0; index < dc.length; index++) {
            dc[index] = 10_000;
        }
        HotlineMonitor.MicDsp dcDsp = new HotlineMonitor.MicDsp(48_000);
        dcDsp.process(dc, dc.length);
        assertTrue("HOT LINE must not amplify microphone DC",
                rms(dc, dc.length - 1_024, dc.length) < 150.0);

        short[] speech = new short[4_800];
        for (int index = 0; index < speech.length; index++) {
            speech[index] = (short) (Math.sin(index * Math.PI * 2.0 * 1_000.0 / 48_000.0)
                    * 4_000.0);
        }
        HotlineMonitor.MicDsp speechDsp = new HotlineMonitor.MicDsp(48_000);
        speechDsp.process(speech, speech.length);
        assertTrue("Speech band should receive audible preamp gain",
                rms(speech, 400, speech.length) > 4_500.0);
    }

    private static double rms(short[] samples, int start, int end) {
        double sum = 0.0;
        for (int index = start; index < end; index++) {
            sum += (double) samples[index] * samples[index];
        }
        return Math.sqrt(sum / Math.max(1, end - start));
    }
}
