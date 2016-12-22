package extrackt;

import java.io.IOException;


/**
 * A source that listens to a PCMDataSource stream and converts it to a
 * stream of samples.
 *
 * @author Crawford Currie
 */
public class SamplesFromPCMData implements SampleSource {

    private byte[] bytebuffer; // optional buffer for reading bytes
    private final PCMDataSource source;
    private final int numChannels;
    private final float sampleRate;
    private final int frameSize;
    
    /**
     * Creates a new instance
     * @param in input stream
     */
    public SamplesFromPCMData(PCMDataSource in) {
        source = in;
        if (!in.markSupported()) {
            throw new Error("Input stream does not support mark");
        }
        bytebuffer = null; // allocated as and when required
        numChannels = source.numChannels;
        sampleRate = source.sampleRate;
        frameSize = source.getFormat().getFrameSize();
    }
    
    @Override
    public float getSampleRate() {
        return sampleRate;
    }
    
    @Override
    public int getNumChannels() {
        return numChannels;
    }
    
    @Override
    public void mark() {
        source.mark();
    }
    
    @Override
    public void reset() throws IOException {
        source.reset();
    }
    
    /**
     * Read samples into a 2D array of float. The array is (N x channels).
     *
     * @param samples array to fill
     * @param offset offset into samples
     * @param length number of samples to read
     * @return number of samples read
     * @throws IOException if there's a problem reading
     */
    @Override
    public int readSamples(float[][] samples, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        int bytes = length * frameSize;
        if (bytebuffer == null || bytes > bytebuffer.length) {
            bytebuffer = new byte[bytes];
        }
        int actual = source.read(bytebuffer, 0, bytes) / frameSize;
        for (int i = 0; i < actual; i++) {
            for (int c = 0; c < numChannels; c++) {
                samples[offset + i][c] = source.GETCHANNEL(bytebuffer, 0, i, c);
            }
        }
        return actual;
    }
}
