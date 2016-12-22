package extrackt;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;

/**
 * Filter that can be used to both measure, and normalise, the amplitude
 * of a stream.
 * @author crawford
 */
public class NormalisationFilter extends PCMDataSource {
    private int peak, target;
   
    /**
     * If 
     * @param in stream to process
     * @param peak previously measured signal peak. If 0, we just want to measure.
     * @param target peak level to normalise to. If 0, then we just want to measure.
     */
    public NormalisationFilter(AudioInputStream in, int peak, int target) {
        super(in);
        this.peak = peak;
        this.target = target;
    }
    
    /**
     * Initialise the stream for reading the peak level
     * @param in stream to process
     */
    public NormalisationFilter(AudioInputStream in) {
        this(in, 0, 0);
    }
    
    /**
     * Get the measured (or set) peak value
     * @return 
     */
    public int getPeak() {
        return peak;
    }

    @Override
    public int read(byte[] b, int s, int e) throws IOException {
        int read = super.read(b, s, e);
        if (read <= 0)
            return read;
        
        AudioFormat af = getFormat();
        int fs = af.getFrameSize();
        int samples = read / fs;
        for (int i = 0; i < samples; i++) {
            for (int channel = 0; channel < af.getChannels(); channel++) {
                int sample = GETCHANNEL(b, s, i, channel);
                if (target > 0)
                    SETCHANNEL(b, s, i, channel, (int) ((float)sample * target / peak));
                else if (sample > peak)
                    peak = sample;
            }
        }
        return read;
    }
}

