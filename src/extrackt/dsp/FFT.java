package extrackt.dsp;

/**
 * FFT class for real signals. Upon return, data[REAL] and data{IMAG] contain the
 * DFT output.
 */
public class FFT {

    public static final int REAL = 0;
    public static int IMAG = 1;

    public static float[][] fft(float[] signal) {
        int numPoints = signal.length;
        // initialize real & imag
        float [][] data = new float[2][numPoints];
        System.arraycopy(signal, 0, data[REAL], 0, numPoints);
        // set all of the samples in the imaginary part to zero
        for (int i = 0; i < numPoints; i++) {
            data[IMAG][i] = 0;
        }

        final int numStages = (int) (Math.log(numPoints) / Math.log(2));
        int halfNumPoints = numPoints >> 1;
        int j = halfNumPoints;
        // FFT time domain decomposition carried out by "bit reversal sorting"
        // algorithm
        int k;
        for (int i = 1; i < numPoints - 2; i++) {
            if (i < j) {
                // swap
                float tempReal = data[REAL][j];
                float tempImag = data[IMAG][j];
                data[REAL][j] = data[REAL][i];
                data[IMAG][j] = data[IMAG][i];
                data[REAL][i] = tempReal;
                data[IMAG][i] = tempImag;
            }
            k = halfNumPoints;
            while (k <= j) {
                j -= k;
                k >>= 1;
            }
            j += k;
        }

        // loop for each stage
        for (int stage = 1; stage <= numStages; stage++) {
            int LE = 1;
            for (int i = 0; i < stage; i++) {
                LE <<= 1;
            }
            int LE2 = LE >> 1;
            double UR = 1;
            double UI = 0;
            // calculate sine & cosine values
            double SR = Math.cos(Math.PI / LE2);
            double SI = -Math.sin(Math.PI / LE2);
            // loop for each sub DFT
            for (int subDFT = 1; subDFT <= LE2; subDFT++) {
                // loop for each butterfly
                for (int butterfly = subDFT - 1; butterfly <= numPoints - 1; butterfly += LE) {
                    int ip = butterfly + LE2;
                    // butterfly calculation
                    float tempReal = (float) (data[REAL][ip] * UR - data[IMAG][ip] * UI);
                    float tempImag = (float) (data[REAL][ip] * UI + data[IMAG][ip] * UR);
                    data[REAL][ip] = data[REAL][butterfly] - tempReal;
                    data[IMAG][ip] = data[IMAG][butterfly] - tempImag;
                    data[REAL][butterfly] += tempReal;
                    data[IMAG][butterfly] += tempImag;
                }

                double tempUR = UR;
                UR = tempUR * SR - UI * SI;
                UI = tempUR * SI + UI * SR;
            }
        }
        return data;
    }
}
