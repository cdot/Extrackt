package extrackt.dsp;

import java.io.IOException;

/**
 * Various window functions applied to frames.
 *
 * @reference
 * <a href="http://en.wikipedia.org/wiki/Window_function">Wikipedia</a>
 */
public class WindowFrames implements FrameSource {

    public static final double twoPI = 2 * Math.PI;
    
    /**
     * Interface implemented by windowing functions.
     */
    public interface Function {

        /**
         * Windowing function. Applies the window to all the samples in
         * the frame, in-place.
         *
         * @param frame the frame containing the samples
         * @param N number of samples in the frame
         */
        public float window(float[] frame, int N);
    }

    private final Function window;
    private final FrameSource source;
    final int N;

    /**
     * Construct a frame source that, given a frame source, applies the
     * given window function to each frame in isolation. The windowed frames
     * are passed on to consumers.
     * @param src the source of frames
     * @param windowFunction the function to apply
     */
    public WindowFrames(FrameSource src, Function windowFunction) {
        source = src;
        N = src.getFrameSize();
        window = windowFunction;
    }

    @Override
    public int getFrameSize() {
        return N;
    }

    /**
     * Hann (Hanning) window.
     */
    public static class Hann implements Function {

        @Override
        public float window(float[] frame, int N) {
            for (int n = 0; n < N; n++) {
                frame[n] *= 0.5 * (1 - Math.cos(twoPI * n / (N - 1)));
            }
            return N;
        }
    }

    /**
     * Hamming window.
     */
    public static class Hamming implements Function {

        @Override
        public float window(float[] frame, int N) {
            for (int n = 0; n < N; n++) {
                frame[n] *= (0.54 - 0.46 * Math.cos(twoPI * n) / (N - 1));
            }
            return N;
        }
    }

    /**
     * Normalise the signal amplitudes by dividing by the maximum amplitude
     */
    public static class Normalise implements Function {

        @Override
        public float window(float[] frame, int N) {
            float max = frame[0];
            for (int n = 1; n < N; n++) {
                if (max < Math.abs(frame[n])) {
                    max = Math.abs(frame[n]);
                }
            }
            for (int i = 0; i < N; i++) {
                frame[i] /= max;
            }
            return max;
        }
    }

    /**
     * Find the fundamental frequency in FFT data using  the Harmonic Product
     * Spectrum. To minimisediscontinuities at the limits of the sample data,
     * it should be processed through a Hann window before the FFT is taken.
     *
     * The window is a NOP. It just calls the listener on the result,
     * the harmonic product spectrum peak F0 corresponding to the fundamental
     * frequency of the signal.
     * <h3>Harmonic Product Spectrum Theory</h3>
     * <p>
     * If the input signal has a fundamental frequency, then its spectrum should
     * consist of a series of peaks, corresponding to fundamental frequency with
     * harmonic components at integer multiples of the fundamental frequency.
     * Hence when we compress the spectrum a number of times (downsampling), and
     * compare it with the original spectrum, we can see that the strongest
     * harmonic peaks line up. The first peak in the original spectrum coincides
     * with the second peak in the spectrum compressed by a factor of two, which
     * coincides with the third peak in the spectrum compressed by a factor of
     * three. Hence, when the various spectrums are multiplied together, the
     * result will form a clear peak at the fundamental frequency.
     * </p>
     * <h3>Method</h3>
     * <p>
     * First, we divide the input signal into segments and apply a Hann window.
     * For each window, we calculate the Fourier Transform. Then we apply the
     * Harmonic Product Spectrum technique.
     * </p><p>
     * The Hz involves two steps: downsampling and multiplication. To
     * downsample, we compress the spectrum five times in each window by
     * resampling: the first time, we compress the original spectrum by two and
     * the second time, by three etc. Once this is completed, we multiply the
     * spectra together and find the frequency that corresponds to the peak
     * (maximum value). This particular frequency corresponds to the fundamental
     * frequency of that particular window.
     * </p>
     * <h3>Limitations of the method</h3>
     * <p>
     * Some nice features of this method include: it is computationally
     * inexpensive, reasonably resistant to additive and multiplicative noise,
     * and adjustable to different kind of inputs. For instance, we could change
     * the number of compressed spectra to use, and we could replace the
     * spectral multiplication with a spectral addition. However, since human
     * pitch perception is basically logarithmic, this means that low pitches
     * may be tracked less accurately than high pitches. Another severe
     * shortfall of the Hz method is that it its resolution is only as good as
     * the length of the FFT used to calculate the spectrum. If we perform a
     * short and fast FFT, we are limited in the number of discrete frequencies
     * we can consider. In order to gain a higher resolution in our output (and
     * therefore see less granularity in our pitch output), we need to take a
     * longer FFT which requires more time.
     * </p>
     */
    public static class HarmonicProductSpectrum implements Function {

        private static final int FACTORS = 5;

        private final float sampleRate;

        public HarmonicProductSpectrum(float sampleRate) {
            this.sampleRate = sampleRate;
        }

        @Override
        public float window(float[] frame, int N) {

            int upperBound = N / FACTORS;
            double[] hpsValues = new double[upperBound];
            int i, j;
            // Compress the spectrum
            for (i = 0; i < upperBound; i++) {
                double hps = 1.0;
                // Downsample FACTORS times
                for (j = 0; j < FACTORS; j++) {
                    hps *= frame[(i + 1) * (j + 1) - 1];
                }
                hpsValues[i] = hps;
            }
            // Find the peak
            double maxHPS = 0;
            int peak = 0;
            for (i = 0; i < upperBound; i++) {
                if (hpsValues[i] > maxHPS) {
                    maxHPS = hpsValues[i];
                    peak = i;
                }
            }

            // Use the sample rate to convert to a frequency in Hz
            return peak * sampleRate / N;
        }
    }

    /**
     * Read frames and apply the chosen window function to each frame.
     *
     * @param frames [numberOfFramesToRead][N] array
     * @return number of frames read
     * @throws IOException
     */
    @Override
    public int readFrames(float[][] frames) throws IOException {
        int read = source.readFrames(frames);
        for (int i = 0; i < read; i++) {
            // Can add a listener for the result if it's ever needed
            window.window(frames[i], N);
        }
        return read;
    }
}
