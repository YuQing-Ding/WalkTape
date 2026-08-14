package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/**
 * Measures each rendered stock the way a cassette is measured on a deck, and requires it to agree
 * with the figures the profile publishes.
 *
 * <p>Without this gate the published columns are decoration: the renderer had its own independent
 * constants, and the stock it produced was 6 to 10 dB late reaching maximum output level, never
 * saturated at 10 kHz at all, and hissed some 11 dB louder than the tape it named. Everything here
 * is derived from the profile's own published data, so re-voicing the tape means re-deriving the
 * constants rather than quietly drifting away from the label.</p>
 *
 * <p>All levels are relative to {@link TapeStockProfile#REFERENCE_FLUX_LEVEL}. Noise is weighted
 * with the analog IEC 61672 A curve applied in the frequency domain, because a bilinear-transformed
 * weighting filter is several dB wrong in the top octave at 48 kHz, which is precisely where tape
 * hiss lives.</p>
 */
public class TapeStockCalibrationTest {

    private static final int RATE = 48_000;
    private static final int WINDOW = RATE / 4;
    private static final double DOLBY = TapeStockProfile.REFERENCE_FLUX_LEVEL;

    @Test
    public void distortionAtDolbyLevelMatchesEachPublishedFigure() {
        for (TapeStockProfile profile : TapeStockProfile.availableProfiles()) {
            double measured = thd(profile, 400, DOLBY) * 100.0;
            assertEquals(profile.manufacturer + " " + profile.model + " THD at Dolby level",
                    profile.thdAtReferencePercent, measured, 0.10);
        }
    }

    @Test
    public void maximumOutputLevelMatchesEachPublishedFigure() {
        for (TapeStockProfile profile : TapeStockProfile.availableProfiles()) {
            double measured = molDb(profile);
            assertEquals(profile.manufacturer + " " + profile.model + " MOL",
                    profile.mol315Db, measured, 0.6);
        }
    }

    @Test
    public void saturationOutputLevelAtTenKilohertzMatchesEachPublishedFigure() {
        for (TapeStockProfile profile : TapeStockProfile.availableProfiles()) {
            double measured = solDb(profile);
            assertEquals(profile.manufacturer + " " + profile.model + " SOL at 10 kHz",
                    profile.sol10kDb, measured, 0.8);
        }
    }

    @Test
    public void biasNoiseMatchesEachPublishedFigure() {
        for (TapeStockProfile profile : TapeStockProfile.availableProfiles()) {
            double measured = noiseADb(profile);
            assertEquals(profile.manufacturer + " " + profile.model + " A-weighted bias noise",
                    profile.biasNoiseDb, measured, 0.8);
        }
    }

    /**
     * The whole point of a saturation output level is that there is one.
     *
     * <p>A curve that merely compresses keeps producing more output for more input for ever, which
     * is what the previous rational magnetisation curve did; it approached a straight line rather
     * than a ceiling.</p>
     */
    @Test
    public void highLevelInputCannotDriveTheCoatingWithoutLimit() {
        for (TapeStockProfile profile : TapeStockProfile.availableProfiles()) {
            double atTwelve = fundamental(profile, 10_000, DOLBY * Math.pow(10, 12 / 20.0));
            double atThirty = fundamental(profile, 10_000, DOLBY * Math.pow(10, 30 / 20.0));
            double extraDb = 20 * Math.log10(atThirty / atTwelve);
            assertTrue(profile.model + " gained " + extraDb
                            + " dB at 10 kHz for 18 dB more drive; the coating must saturate",
                    extraDb < 1.5);
        }
    }

    @Test
    public void betterStockHoldsMoreLevelAndHissesLess() {
        TapeStockProfile ferric = TapeStockProfile.sonyChf1978();
        TapeStockProfile chrome = TapeStockProfile.tdkSa1988();
        TapeStockProfile metal = TapeStockProfile.tdkMaX1990();
        assertTrue("Type II must reach a higher maximum output level than early ferric",
                molDb(chrome) > molDb(ferric));
        assertTrue("Metal must reach a higher maximum output level than Type II",
                molDb(metal) > molDb(chrome));
        assertTrue("Type II must be quieter than early ferric",
                noiseADb(chrome) < noiseADb(ferric));
    }

    /** Input level above Dolby reference at which total harmonic distortion reaches 3 per cent. */
    private static double molDb(TapeStockProfile profile) {
        double low = -12;
        double high = 30;
        for (int step = 0; step < 20; step++) {
            double mid = (low + high) / 2;
            if (thd(profile, 400, DOLBY * Math.pow(10.0, mid / 20.0)) < 0.03) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }

    /**
     * Highest output the stock reaches at 10 kHz, in dB relative to Dolby reference.
     *
     * <p>Published saturation output level is referenced to a standard recorded flux, while the
     * renderer's output also carries this stock's replay sensitivity. Taking that back out leaves
     * the figure the tape catalogue quotes.</p>
     */
    private static double solDb(TapeStockProfile profile) {
        double best = 0;
        for (double drive = 0; drive <= 40; drive += 1.0) {
            best = Math.max(best,
                    fundamental(profile, 10_000, DOLBY * Math.pow(10.0, drive / 20.0)));
        }
        return 20 * Math.log10(best / DOLBY) - profile.sensitivityDb;
    }

    private static double thd(TapeStockProfile profile, int hertz, double amplitude) {
        double[] spectrum = analyse(profile, hertz, amplitude);
        double harmonics = 0;
        for (int h = 2; h <= 5; h++) {
            harmonics += spectrum[h] * spectrum[h];
        }
        return Math.sqrt(harmonics) / Math.max(1e-12, spectrum[1]);
    }

    private static double fundamental(TapeStockProfile profile, int hertz, double amplitude) {
        return analyse(profile, hertz, amplitude)[1];
    }

    private static double[] analyse(TapeStockProfile profile, int hertz, double amplitude) {
        TapeMediumDsp tape = new TapeMediumDsp(RATE, profile);
        float[] block = new float[2 * 1024];
        long phase = 0;
        for (int b = 0; b < RATE / 4 / 1024; b++) {
            phase = fill(block, phase, hertz, amplitude);
            tape.process(block, 1024);
        }
        float[] captured = new float[WINDOW];
        int written = 0;
        while (written < WINDOW) {
            phase = fill(block, phase, hertz, amplitude);
            tape.process(block, 1024);
            for (int i = 0; i < 1024 && written < WINDOW; i++) {
                captured[written++] = block[i * 2];
            }
        }
        double[] result = new double[6];
        for (int h = 1; h <= 5; h++) {
            double real = 0;
            double imaginary = 0;
            double omega = 2 * Math.PI * hertz * h / RATE;
            for (int n = 0; n < WINDOW; n++) {
                real += captured[n] * Math.cos(omega * n);
                imaginary += captured[n] * Math.sin(omega * n);
            }
            result[h] = 2 * Math.hypot(real, imaginary) / WINDOW;
        }
        return result;
    }

    private static long fill(float[] block, long phase, int hertz, double amplitude) {
        for (int frame = 0; frame < 1024; frame++) {
            float value = (float) (amplitude
                    * Math.sin(2 * Math.PI * hertz * (phase + frame) / RATE));
            block[frame * 2] = value;
            block[frame * 2 + 1] = value;
        }
        return phase + 1024;
    }

    private static double noiseADb(TapeStockProfile profile) {
        TapeMediumDsp tape = new TapeMediumDsp(RATE, profile);
        float[] block = new float[2 * 1024];
        for (int b = 0; b < 96; b++) {
            Arrays.fill(block, 0f);
            tape.process(block, 1024);
        }

        final int size = 4_096;
        double[] power = new double[size / 2 + 1];
        double[] real = new double[size];
        double[] imaginary = new double[size];
        double[] window = new double[size];
        double windowPower = 0;
        for (int n = 0; n < size; n++) {
            window[n] = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / size);
            windowPower += window[n] * window[n];
        }
        windowPower /= size;

        int frames = 48;
        for (int frame = 0; frame < frames; frame++) {
            for (int n = 0; n < size; n += 1024) {
                Arrays.fill(block, 0f);
                tape.process(block, 1024);
                for (int i = 0; i < 1024; i++) {
                    real[n + i] = block[i * 2] * window[n + i];
                    imaginary[n + i] = 0;
                }
            }
            fft(real, imaginary);
            for (int bin = 0; bin <= size / 2; bin++) {
                power[bin] += real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
            }
        }

        double total = 0;
        for (int bin = 1; bin < size / 2; bin++) {
            double weight = analogAWeight(bin * (double) RATE / size);
            total += 2 * power[bin] / frames * weight * weight;
        }
        return 20 * Math.log10(Math.sqrt(total) / size / Math.sqrt(windowPower) / DOLBY)
                - profile.sensitivityDb;
    }

    @Test
    public void replaySensitivityMakesEachStockPlayBackAtItsPublishedLevel() {
        TapeStockProfile reference = TapeStockProfile.sonyChf1978();
        double referenceLevel = 20 * Math.log10(
                fundamental(reference, 1_000, DOLBY * 0.1) / (DOLBY * 0.1));
        for (TapeStockProfile profile : TapeStockProfile.availableProfiles()) {
            double level = 20 * Math.log10(
                    fundamental(profile, 1_000, DOLBY * 0.1) / (DOLBY * 0.1));
            // The residual is the coating's own small-signal gain, which differs a little between
            // formulations because their drive and knee differ; sensitivity rides on top of it.
            assertEquals(profile.model + " small-signal replay level",
                    profile.sensitivityDb - reference.sensitivityDb, level - referenceLevel, 1.0);
        }
    }

    /** Analog IEC 61672 A-weighting magnitude, normalised to unity at 1 kHz. */
    static double analogAWeight(double hertz) {
        double f2 = hertz * hertz;
        double c1 = 20.598997 * 20.598997;
        double c2 = 107.65265 * 107.65265;
        double c3 = 737.86223 * 737.86223;
        double c4 = 12194.217 * 12194.217;
        double raw = c4 * f2 * f2
                / ((f2 + c1) * Math.sqrt((f2 + c2) * (f2 + c3)) * (f2 + c4));
        double reference = c4 * 1e6 * 1e6
                / ((1e6 + c1) * Math.sqrt((1e6 + c2) * (1e6 + c3)) * (1e6 + c4));
        return raw / reference;
    }

    @Test
    public void weightingCurveAgreesWithTheStandardTable() {
        double[] hertz = {31.5, 63, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000};
        double[] published = {-39.4, -26.2, -16.1, -8.6, -3.2, 0.0, 1.2, 1.0, -1.1, -6.6};
        for (int index = 0; index < hertz.length; index++) {
            assertEquals("A-weighting at " + hertz[index] + " Hz",
                    published[index], 20 * Math.log10(analogAWeight(hertz[index])), 0.15);
        }
    }

    private static void fft(double[] real, double[] imaginary) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tr = real[i];
                real[i] = real[j];
                real[j] = tr;
                double ti = imaginary[i];
                imaginary[i] = imaginary[j];
                imaginary[j] = ti;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2 * Math.PI / length;
            double wr = Math.cos(angle);
            double wi = Math.sin(angle);
            for (int i = 0; i < n; i += length) {
                double cr = 1;
                double ci = 0;
                for (int j = 0; j < length / 2; j++) {
                    int a = i + j;
                    int b = a + length / 2;
                    double tr = real[b] * cr - imaginary[b] * ci;
                    double ti = real[b] * ci + imaginary[b] * cr;
                    real[b] = real[a] - tr;
                    imaginary[b] = imaginary[a] - ti;
                    real[a] += tr;
                    imaginary[a] += ti;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
