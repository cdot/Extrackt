package extrackt;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Sits on an audio stream and performs an FFT on *all* the samples in it.
 * Input data is passed on* untouched to the consumer, and the FFT data is
 * passed to watchers. The Threading here is fairly complex. Each FFTWatcher
 * is a thread, and each FFT it performs is another thread. This allows for
 * good interruptability with useful results even from a partial FFT.
 * The major purpose of this class is to generate an FFT of the whole of a short
 * piece of signal. It's not useful for continuous FFT of a sample stream.
 * 
 * @author crawford
 */
public class FFTWatcher implements SampleWatcher.Watcher {

    private double[] d;
    private int d_len;

    private final float[] f; // sum of frequency power
    private final int windowSize; // window size, in samples
    private final int windowClip;
    private Thread waiter;
    ArrayList<Thread> ffts = new ArrayList<>();
    ArrayList<double[]> results = new ArrayList<>();
    
    /**
     * @param ws FFT window size, in number of samples. the result will
     * be ws/2 in size, as we discard the negative frequencies
     * @param clip of the maximum frequency we are interested in
     */
    public FFTWatcher(int ws, int clip) {
        waiter = null;
        windowSize = ws;
        windowClip = clip;
        f = new float[windowSize / 2]; // Not interested in the -ve frequencies
        d = new double[windowSize * 2]; // [Re, Im]
        d_len = 0;
    }

    public void interrupt() {
        if (waiter != null) {
            waiter.interrupt();
        }
    }

    private Thread fft(final double[] d) {
        // The human voice covers a range of 80 Hz to a peak in the 1-3 kHz
        // region and falls off pretty rapidly afterwards. There's not a lot
        // of energy above 10 kHz. Most of the energy is 125 Hz to about 6 kHz.
        // So a first approximation is to find that power band. To do that I
        // need to scale the results of the FFT.

        Thread th = new Thread() {
            FFT fft;

            @Override
            public void run() {
                try {
                    fft = new FFT(FFT.FORWARD, d);
                    // The input data is real, so each negative frequency (the second
                    // half of the result) is the complex conjugate (same real part, but
                    // with imaginary parts of equal magnitude and opposite sign) of the
                    // corresponding entry in the first half. So we can simply ignore the
                    // second half of the array.
                    results.add(d);
                } catch (InterruptedException ie) {
                    System.out.println("FFT  thread interrupted");
                }
            }

            @Override
            public void interrupt() {
                fft.interrupted = true;
                super.interrupt();
            }
        };
        th.start();
        return th;
    }

    @Override
    public void addSamples(float[][] b) {
        // Fill the sample buffer with amplitude data
        float[] window = new float[windowSize];
        for (int i = 0; i < windowSize; i++) {
            float sample = 0;
            // Sum all the channels
            for (int chan = 0; chan < b[0].length; chan++) {
                sample += b[i][chan];
            }
            window[i] = sample;
        }
//        WindowFrames.Function hann = new WindowFrames.Hann();
//        hann.window(window, windowSize);
        
        for (int i = 0; i < windowSize; i++) {
            d[d_len++] = window[i]; // Re
            d[d_len++] = 0;  // Im

            if (d_len == 2 * windowSize) {
                ffts.add(fft(d));

                // Allocate a new buffer for the next window
                d = new double[2 * windowSize];
                d_len = 0;
            }
        }
    }

    public void wait(final SampleWatcher.Watcher whenReady) {
        // Wait for all the computation threads to complete before
        // sending the result to the sucker.
        final ArrayList<Thread> waitFor = ffts;
        final ArrayList<double[]> res = results;
        ffts = new ArrayList<>(); // ready for the next run
        results = new ArrayList<>();
        waiter = new Thread() {
            @Override
            public void run() {
                Iterator<Thread> fi = waitFor.iterator();
                while (fi.hasNext()) {
                    Thread fft = fi.next();
                    if (fft.isAlive()) {
                        try {
                            fft.join();
                        } catch (InterruptedException ie) {
                        }
                    }
                }
                // Average the collected results for each window
                int clip = windowClip;
                if (clip <= 0 || clip > windowSize / 2)
                    clip = windowSize / 2;
                float[][] a = new float[clip][1];
                Iterator<double[]> it = res.iterator();
                while (it.hasNext()) {
                    double[] d = it.next();
                     for (int i = 0; i < clip; i++) {
                        int j = i * 2;
                        double re = d[j];
                        double im = d[j + 1]; // Imag
                        a[i][0] += (float)Math.sqrt(re * re + im * im) / windowSize;
                    }
                }
                for (int i = 0; i < clip; i++) {
                    // 20 * log10(sqrt(Re^2 + Im^2))
                     a[i][0] /= results.size();
                }
                whenReady.addSamples(a);
            }

            @Override
            public void interrupt() {
                Iterator<Thread> fi = ffts.iterator();
                while (fi.hasNext()) {
                    fi.next().interrupt();
                }
                super.interrupt();
            }
        };
        waiter.start();
    }
}
