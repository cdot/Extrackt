package extrackt;

import java.io.IOException;
import static java.lang.Thread.yield;

/**
 * Abstract base class for SampleSources that accept another SampleSource as
 * their input. Subclasses must implement readSamples().
 */
public abstract class SampleCopier implements SampleSource {

    protected SampleSource source;
    
    public SampleCopier(SampleSource in) {
        source = in;
    }

    @Override
    public int getNumChannels() {
        return source.getNumChannels();
    }

    @Override
    public float getSampleRate() {
        return source.getSampleRate();
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
     * Suck samples from the source stream and throw them away. Allows the
     * stream to be used as a sink without having to allocate a buffer. This is
     * useful when the subclass is just listening to the stream.
     * @throws IOException 
     */
    public void suckDry() throws IOException {
        float[][] buffer = new float[Sink.EXTERNAL_BUFFER_SIZE][source.getNumChannels()];
        while (readSamples(buffer, 0, Sink.EXTERNAL_BUFFER_SIZE) == Sink.EXTERNAL_BUFFER_SIZE) {
            yield();
        }
    }

    @Override
    public abstract int readSamples(float[][] samples, int offset, int length) throws IOException;
}
