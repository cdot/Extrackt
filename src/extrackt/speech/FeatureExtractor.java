package extrackt.speech;

/**
 * Feature extraction, cepstral mean substraction, and merging with deltas.
 * The resulting feature vector is MFCC.numCepstra
 *
 * @author Ganesh Tiwari
 * @author Crawford Currie
 */
public class FeatureExtractor {

    private final double[][] cepstralCoefficients;
    public double[][] mfccFeature;
    public double[][] featureVector;
    public static int numCepstra = 12;
    public static final int numMelFilters = 30; // how many mel filters

    /**
     * Generates feature vector by combining MFCC, delta and delta deltas, with
     * energy and its deltas and delta deltas/
     */
    FeatureExtractor(float[][] framedSignal, int samplingRate, int samplesPerFrame) {

        int noOfFrames = framedSignal.length;

        cepstralCoefficients = new double[noOfFrames][numCepstra];
        // Calculates MFCC coefficients of each frame
        for (int i = 0; i < noOfFrames; i++) {
            // for each frame i, make mfcc from current framed signal
            MFCC mfcc = new MFCC(framedSignal[i], samplesPerFrame, samplingRate, numCepstra, numMelFilters); // 2D data
            cepstralCoefficients[i] = mfcc.getCepstralCoefficients();
        }

        // Subtract cepstral mean to remove channel effect
        normaliseToCepstralMean(cepstralCoefficients, noOfFrames);

        // delta [noOfFrames][numCepstra]
        double[][] deltaMfcc = calculateDelta2D(2, cepstralCoefficients);

        // delta delta [noOfFrames][numCepstra]
        double[][] deltaDeltaMfcc = calculateDelta2D(1, deltaMfcc);

        // energy [noOfFrames]
        double[] energyVal = calculateEnergy(framedSignal);

        // energy delta [noOfFrames]
        double[] deltaEnergy = calculateDelta1D(1, energyVal);

        // energy delta delta [noOfFrames];
        double[] deltaDeltaEnergy = calculateDelta1D(1, deltaEnergy);

        // Build the feature vector
        featureVector = new double[noOfFrames][3 * numCepstra + 3];
        for (int i = 0; i < framedSignal.length; i++) {
            System.arraycopy(mfccFeature[i], 0, featureVector[i], 0, framedSignal.length);
            System.arraycopy(deltaMfcc[i], 0, featureVector[i], numCepstra, numCepstra);
            System.arraycopy(deltaDeltaMfcc[i], 0, featureVector[i], 2 * numCepstra, numCepstra);
            featureVector[i][3 * numCepstra] = energyVal[i];
            featureVector[i][3 * numCepstra + 1] = deltaEnergy[i];
            featureVector[i][3 * numCepstra + 2] = deltaDeltaEnergy[i];
        }
    }

    // Subtraction of cepstral mean to remove channel effect.
    private static void normaliseToCepstralMean(double[][] cepstralCoefficients, int noOfFrames) {
        double sum;
        double mean;
        double mCeps[][] = new double[noOfFrames][numCepstra - 1];

        for (int i = 0; i < numCepstra - 1; i++) {
            sum = 0.0;
            for (int j = 0; j < noOfFrames; j++) {
                sum += cepstralCoefficients[j][i]; // ith coeff of each frame
            }
            mean = sum / noOfFrames;
            for (int j = 0; j < noOfFrames; j++) {
                mCeps[j][i] = cepstralCoefficients[j][i] - mean;
            }
        }
    }

    /**
     * Algorithm to calculate energy for a set of frames.
     *
     * @reference Spectral Features for Automatic Text-Independent Speaker
     * Recognition
     * @author Tomi Kinnunen
     * @param samplesPerFrame number of samples per frame
     * @param frames [noOfFrames][samplesPerFrame] array
     * @return array giving total energy for each frame
     */
    public static double[] calculateEnergy(float[][] frames) {
        double[] energy = new double[frames.length];
        for (int i = 0; i < frames.length; i++) {
            float[] samples = frames[i];
            float sum = 0;
            for (int j = 0; j < samples.length; j++) {
                sum += Math.pow(samples[j], 2);
            }
            energy[i] = Math.log(sum);
        }
        return energy;
    }

    /**
     * Calculate deltas of 2D data by linear regression.
     *
     * @reference Spectral Features for Automatic Text-Independent Speaker
     * Recognition @author Tomi Kinnunen, @fromPage 83
     * @param M regression window size i.e.,number of frames to take into
     * account while taking delta
     * @param data data to analyse
     * @return vector of deltas
     */
    public static double[][] calculateDelta2D(int M, double[][] data) {
        int noOfMfcc = data[0].length;
        int frameCount = data.length;
        int i, j, k;

        // 1. calculate sum of mSquare i.e., denominator
        double mSqSum = 0;
        for (i = -M; i < M; i++) {
            mSqSum += Math.pow(i, 2);
        }

        // 2.calculate numerator
        double delta[][] = new double[frameCount][noOfMfcc];
        for (i = 0; i < noOfMfcc; i++) {
            // 0..M
            for (k = 0; k < M; k++) {
                delta[k][i] = data[k][i];
            }
            // M..frameCount-M
            for (j = M; j < frameCount - M; j++) {
                double sumDataMulM = 0;
                for (int m = -M; m <= +M; m++) {
                    sumDataMulM += m * data[m + j][i];
                }
                // 3. divide
                delta[j][i] = sumDataMulM / mSqSum;
            }
            // frameCount-M..frameCount
            for (k = frameCount - M; k < frameCount; k++) {
                // delta[l][i] = 0;
                delta[k][i] = data[k][i];
            }

        }

        return delta;
    }

    /**
     * Calculate deltas of 1D data by linear regression.
     *
     * @reference Spectral Features for Automatic Text-Independent Speaker
     * Recognition @author Tomi Kinnunen, @fromPage 83
     * @param M regression window size <br>
     * i.e.,number of frames to take into account while taking delta
     * @param data data to analyse
     * @return vector of deltas
     */
    public static double[] calculateDelta1D(int M, double[] data) {
        int frameCount = data.length;
        int i;

        double mSqSum = 0;
        for (i = -M; i < M; i++) {
            mSqSum += Math.pow(i, 2);
        }
        double[] delta = new double[frameCount];

        // 0..M
        System.arraycopy(data, 0, delta, 0, M);

        // frameCount-M .. frameCount
        System.arraycopy(data, frameCount - M, delta, frameCount - M, M);

        // M .. frameCount-M
        for (i = M; i < frameCount - M; i++) {
            // travel from -M to +M
            double sumDataMulM = 0;
            for (int m = -M; m <= +M; m++) {
                // System.out.println("Current m -->\t"+m+ "current j -->\t"+j +
                // "data [m+j][i] -->\t"+data[m + j][i]);
                sumDataMulM += m * data[m + i];
            }
            // 3. divide
            delta[i] = sumDataMulM / mSqSum;
        }
        // System.out.println("Delta 1d **************");
        // ArrayWriter.printDoubleArrayToConole(delta);
        return delta;
    }
}
