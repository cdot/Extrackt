package extrackt.speech;

import extrackt.dsp.FFT;

/**
 * Representation of Mel Frequency Cepstral Coefficients
 */
public class MFCC {

   // how many MFCC coefficients per frame?
    public final int numCepstra;
    private final int numMelFilters; // how many mel filters
    private static final double lowerFilterFreq = 80;
    private final float[] framedSignal;
    private final int samplesPerFrame;
    private final int samplingRate;
    private final double upperFilterFreq;
    private double[] cepc;

    /**
     * Given a framed signal, extract the MFCC coefficients for each frame.
     *
     * @param sig framed signal
     * @param sr sample rate
     * @param spf samples per frame
     * @param nc number of cepstra required
     * @param nmf number of Mel filters to use
     */
    MFCC(float[] sig, int sr, int spf, int nc, int nmf) {
        framedSignal = sig;
        samplesPerFrame = spf;
        numCepstra = nc;
        numMelFilters = nmf;
        samplingRate = sr;
        upperFilterFreq = samplingRate / 2.0;

        // FFT to get the frequency bins [framed.length]
        double[] bin = magnitudeSpectrum();

        // framedSignal = preEmphasis(framedSignal, 0.95); //never used
        // cbin=frequencies of the channels in terms of FFT bin indices (cbin[i]
        // for the i -th channel)
        int cbin[] = fftBinIndices(); // same for all

        // Mel filter bank [numMelFilters * 2]
        double fbank[] = melFilter(bin, cbin);

        // Non-linear transformation
        double f[] = nonLinearTransformation(fbank);

        // Cepstral coefficients, by DCT (inverse FFT)
        cepc= DCT(f, numCepstra, numMelFilters);
    }

    public double[] getCepstralCoefficients() {
        return cepc;
    }
    
    /**
     * Calculate (real) FFT for a frame
     */
    private double[] magnitudeSpectrum() {
        double[] m = new double[framedSignal.length];
        float[][] result = FFT.fft(framedSignal);
        // calculate magnitude spectrum
        for (int k = 0; k < framedSignal.length; k++) {
            m[k] = Math.sqrt(result[FFT.REAL][k] * result[FFT.REAL][k] + result[FFT.IMAG][k] * result[FFT.IMAG][k]);
        }
        return m;
    }

    /**
     * Emphasise high frequency signal
     *
     * @param inputSignal
     * @return outputSignal
     */
    private float[] preEmphasis(float inputSignal[], double preEmphasisAlpha) {
        // System.err.println(" inside pre Emphasis");
        float outputSignal[] = new float[inputSignal.length];
        // apply pre-emphasis to each sample
        for (int n = 1; n < inputSignal.length; n++) {
            outputSignal[n] = (float) (inputSignal[n] - preEmphasisAlpha * inputSignal[n - 1]);
        }
        return outputSignal;
    }

    /**
     * Calculate the indices of the centres of the filter bins (one per
     * numMelFilters)
     *
     * @return an array of indices into
     */
    private int[] fftBinIndices() {
        int cbin[] = new int[numMelFilters + 2];
        cbin[0] = (int) Math.round(lowerFilterFreq / samplingRate * samplesPerFrame);// cbin0
        cbin[cbin.length - 1] = (samplesPerFrame / 2); // cbin24
        for (int i = 1; i <= numMelFilters; i++) { // from cbin1 to cbin23
            double fc = centerFreq(i); // center freq for i th filter
            cbin[i] = (int) Math.round(fc / samplingRate * samplesPerFrame);
        }
        return cbin;
    }

    /**
     * Performs mel filter operation
     *
     * @param bin magnitude spectrum (| |)^2 of fft
     * @param cbin mel filter coeffs
     * @return mel filtered coeffs--> filter bank coefficients.
     */
    private double[] melFilter(double bin[], int cbin[]) {
        double temp[] = new double[numMelFilters + 2];
        for (int k = 1; k <= numMelFilters; k++) {
            double num1 = 0.0, num2 = 0.0;
            for (int i = cbin[k - 1]; i <= cbin[k]; i++) {
                num1 += ((i - cbin[k - 1] + 1) / (cbin[k] - cbin[k - 1] + 1)) * bin[i];
            }

            for (int i = cbin[k] + 1; i <= cbin[k + 1]; i++) {
                num2 += (1 - ((i - cbin[k]) / (cbin[k + 1] - cbin[k] + 1))) * bin[i];
            }

            temp[k] = num1 + num2;
        }
        double fbank[] = new double[numMelFilters];
        for (int i = 0; i < numMelFilters; i++) {
            fbank[i] = temp[i + 1];
        }
        return fbank;
    }

    /**
     * @param fbank
     * @return f log of filter bac
     */
    private static double[] nonLinearTransformation(double fbank[]) {
        double f[] = new double[fbank.length];
        final double FLOOR = -50;
        for (int i = 0; i < fbank.length; i++) {
            f[i] = Math.log(fbank[i]);
            // check if ln() returns a value less than the floor
            if (f[i] < FLOOR) {
                f[i] = FLOOR;
            }
        }
        return f;
    }

    /**
     * Calculate the centre frequency of the ith Mel filter
     *
     * @param i filter we want the centre frequency for
     * @return the centre (sample) frequency
     */
    private double centerFreq(int i) {
        double melFLow, melFHigh;
        melFLow = freqToMel(lowerFilterFreq);
        melFHigh = freqToMel(upperFilterFreq);
        double temp = melFLow + ((melFHigh - melFLow) / (numMelFilters + 1)) * i;
        return inverseMel(temp);
    }

    /**
     * The inverse of freqToMel
     *
     * @param x
     * @return a frequency
     */
    private static double inverseMel(double x) {
        double temp = Math.pow(10, x / 2595) - 1;
        return 700 * (temp);
    }

    /**
     * Convert a sample frequency to a Mel frequency
     *
     * @param freq
     * @return
     */
    protected static double freqToMel(double freq) {
        return 2595 * log10(1 + freq / 700);
    }

    private static double log10(double value) {
        return Math.log(value) / Math.log(10);
    }

    /**
     * Inverse Fourier Transform. We can use a discrete cosine transformation
     * (DCT) because there are only real coefficients.
     *
     * @param y sample data
     * @param numCepstra number of MFCC coefficients (features)
     * @param M number of Mel Filters
     * @return DCT of y
     */
    private static double[] DCT(double y[], int numCepstra, int M) {
        double cepc[] = new double[numCepstra];
        // perform DCT
        for (int n = 1; n <= numCepstra; n++) {
            for (int i = 1; i <= M; i++) {
                cepc[n - 1] += y[i - 1] * Math.cos(Math.PI * (n - 1) / M * (i - 0.5));
            }
        }
        return cepc;
    }

}
