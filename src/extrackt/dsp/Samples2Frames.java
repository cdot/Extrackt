package extrackt.dsp;

import extrackt.SampleSource;
import java.io.IOException;

/**
 * Generate a stream of frames from a stream of samples.
 * The channels in the source sample stream are summed to a single sample value.
 */
public class Samples2Frames implements FrameSource {

    private SampleSource source;
    private int N;
    
    /**
     * Construct given a sample stream and a number of samples per frame
     * @param src sample stream
     * @param samplesPerFrame number of samples per frame
     */
    public Samples2Frames(SampleSource src, int samplesPerFrame) {
        source = src;
        N = samplesPerFrame;
    }

    @Override
    public int getFrameSize() {
        return N;
    }
    
    @Override
    public int readFrames(float[][] frames) throws IOException {
        int n = frames.length;
        float[][] frame = new float[N][source.getNumChannels()];
        for (int i = 0; i < n; i++) {
            int read = source.readSamples(frame, 0, N);
            // Zero-pad the frame
            while (read < N) {
                for (int c = 0; c < source.getNumChannels(); c++)
                    frame[read][c] = 0;
                read++;
            }
            // Sum the channels
            for (int j = 0; j < N; j++) {
                float s = 0;
                for (int c = 0; c < source.getNumChannels(); c++)
                    s += frame[j][c];
                frames[i][j] = s;
            }
        }
        return n;
    }
}
