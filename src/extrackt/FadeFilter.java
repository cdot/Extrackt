package extrackt;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;

/**
 * Filter that fades in/out a signal
 * @author crawford
 */
class FadeFilter extends PCMDataSource {
    private float offset, fade_in, fade_out;
    
    public FadeFilter(AudioInputStream in, float fin, float fout) {
        super(in);
        offset = 0;
        fade_in = fin;
        fade_out = fout;
    }
    
    @Override
    public int read(byte[] b, int s, int e) throws IOException {
        int read = super.read(b, s, e);
        if (read <= 0)
            return read;
        
        AudioFormat af = getFormat();
        int inramp = (int)(fade_in * af.getSampleRate());
        int outramp = (int)(fade_out * af.getSampleRate());
        int fs = af.getFrameSize();
        int samples = read / fs;
        if (offset < inramp) {
            // fade in
            for (int i = 0; i < inramp - offset && i < samples; i++) {
                for (int channel = 0; channel < af.getChannels(); channel++) {
                    int sample = GETCHANNEL(b, s, i, channel);
                    if (Math.abs(sample) > (float)(offset + i) / inramp) {
                        SETCHANNEL(b, s, i, channel, (int)(sample * (float)(offset + i) / inramp));                       
                    }
                }
            }
        }
        if (offset > getFrameLength() - outramp) {
            // fade out
            for (int i = 0; i < samples; i++) {
                for (int channel = 0; channel < af.getChannels(); channel++) {
                    int sample = GETCHANNEL(b, s, i, channel);
                    if (Math.abs(sample) > (float)(getFrameLength() - (offset + i)) / outramp) {
                        SETCHANNEL(b, s, i, channel, (int)(sample * (float)(getFrameLength() - (offset + i)) / outramp));                    
                    }
                }
            }
        }
        offset += samples;
        return read;
    }
}

