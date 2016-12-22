package extrackt;

import java.io.IOException;

/**
 * A source that provides a stream of audio samples. Samples can be read as
 * individual channels, or the channels can be summed to give a mono
 * amplitude for the sample time.
 *
 * @author Crawford Currie
 */
public interface SampleSource {
    /**
     * Get the sample rate that this source uses.
     * @return the sample rate, in Hz
     */
    public float getSampleRate();
    
    /**
     * Get the number of channels in the samples this source provides.
     * @return the number of channels (2 for stereo)
     */
    public int getNumChannels();

    /**
     * Mark the sample stream at the current point so it can be reset to that
     * point.
     */
    public void mark();
    
    /**
     * Reset the sample stream to the last point marked.
     * @throws IOException if something went wrong
     */
    public void reset() throws IOException;
    
    /**
     * Read samples into a 2D array of float. The array is (N x channels).
     *
     * @param samples array to fill
     * @param offset offset into samples
     * @param length number of samples to read
     * @return number of samples read
     * @throws IOException if there's a problem reading
     */
    public int readSamples(float[][] samples, int offset, int length) throws IOException;
}
