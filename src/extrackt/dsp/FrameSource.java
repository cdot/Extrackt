package extrackt.dsp;

import java.io.IOException;

/**
 * A FrameSource is used to convert a sample stream to a series of sample frames.
 */
public interface FrameSource {
    /**
     * Get the size of a frame
     * @return the size of a frame, in samples
     */
    public int getFrameSize();
    
    /**
     * Get the next n frames.
     * @param frames [numberOfFrames][getFrameSize()] array
     * @returns the number of frames read
     */
    public int readFrames(float[][] frames) throws IOException;
}
