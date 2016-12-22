package extrackt;

import java.io.IOException;

/**
 * Filter that scans for silences in a sample stream and removes them. The
 * sample stream is assumed to contain voice, of which at least the first 200ms
 * is silence. WARNING: mark() and reset() cannot be used in consumers
 * Works by framing the input signal into 10ms frames that are then accepted or
 * rejected based on their number of voiced versus unvoiced samples.
 * 
 * @reference 'A New Silence Removal and Endpoint Detection Algorithm for Speech
 * and Speaker Recognition Applications' by IIT, Khragpur
 */
public class SampleSourceSilentFramesRemoved extends SampleWatcher {

    private static final float FRAME_LENGTH = 0.01f; // 10ms
    // number of frames to sample for mean and standard deviation, 200ms+
    // assumed to be noise (no signal)
    private static final int FIRST_FRAMES = (int) (0.055 / FRAME_LENGTH);

    private final int nSamplesInFrame;
    
    private boolean initialised;
    private double m, sd; // mean, standard deviation
    private int unvoiced, voiced; // counts of voiced and unvoiced samples
    private final AudioRangeListener listener;
    private int totalRead;
    
    /**
     * Creates a new instance
     *
     * @param ai source of audio data
     */
    public SampleSourceSilentFramesRemoved(SampleSource ai, AudioRangeListener arl) {
        super(ai);
        initialised = false;
        nSamplesInFrame = (int) (source.getSampleRate() * FRAME_LENGTH);
        listener = arl;
        totalRead = 0;
    }

    @Override
    public void mark() {
        throw new Error("mark() not supported by SilenceFilter");
    }

    @Override
    public void reset() throws IOException {
        throw new Error("reset() not supported by SilenceFilter");
    }

    @Override
    public int readSamples(float[][] samples, int offset, int length) throws IOException {
        if (!initialised) {
            // 1. calculation of mean and standard deviation over first 200ms
            source.mark();
            int firstSamples = (int) (FIRST_FRAMES * nSamplesInFrame);
            float[][] fb = new float[firstSamples][source.getNumChannels()];
            int reads = source.readSamples(fb, 0, firstSamples);

            double sum = 0;
            for (int i = 0; i < reads; i++) {
                for (int c = 0; c < source.getNumChannels(); c++) {
                    sum += fb[i][c];
                }
            }
            m = sum / reads;

            sum = 0;
            for (int i = 0; i < reads; i++) {
                float sample = 0;
                for (int c = 0; c < source.getNumChannels(); c++) {
                    sample += fb[i][c];
                }
                sum += Math.pow((sample - m), 2);
            }
            sd = Math.sqrt(sum / reads);
            source.reset();
            voiced = unvoiced = 0;
            initialised = true;
        }

        int read = 0;
        int readp, writep;

        while (read < length) {
            readp = writep = 0;
            int read_now = source.readSamples(samples, offset, length);
            boolean eos = (read_now < length);
            while (readp < read_now) {
                float sample = 0;
                for (int c = 0; c < source.getNumChannels(); c++) {
                    sample += samples[offset + readp][c];
                }
                totalRead++;
                // 3. see whether one-dimensional Mahalanobis distance function
                // |x-u|/s is greater than 3 or not.
                double distance = Math.abs(sample - m) / sd;
                if (distance > 3) {
                    voiced++;
                } else {
                    unvoiced++;
                }
                if (voiced + unvoiced == nSamplesInFrame
                        || readp + voiced + unvoiced == read_now) {
                    int sread = voiced + unvoiced;
                    // We've had an entire frame
                    if (voiced > unvoiced) {
                        // We want this frame
                        if (writep + sread != readp) {
                            for (int i = 0; i < sread; i++) {
                                samples[offset + writep++] = samples[offset + readp++];
                            }
                        } else {
                            writep = readp;
                        }
                        read += sread;
                    } else if (listener != null) {                       
                        listener.rangeEvent((totalRead - nSamplesInFrame)  / source.getSampleRate(),
                                totalRead / source.getSampleRate(), null);
                    }
                    voiced = unvoiced = 0;
                }
                readp++;
            }
            if (eos) {
                break;
            }
            length -= writep;
            offset += writep;
        }
        return read;
    }
}
