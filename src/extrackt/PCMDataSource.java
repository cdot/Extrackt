package extrackt;

import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFormat.Encoding;

/**
 * A source of PCM data. PCM data can come from different sources - files,
 * microphones etc. This class provides a normalised interface to these sources,
 * and provides protected methods to subclasses that need to process the stream
 * sample-by-sample.
 * 
 * The primary interface used by consumers of the stream are the read() methods
 * provided by all stream for reading the bytes that comprise the data.
 *
 * @author Crawford Currie
 */
public class PCMDataSource extends AudioInputStream {

    private final int channelFrameSize;
    private final boolean isBigEndian;
    protected final InputStream source;
    private final boolean signed;
    /**
     * Number of channels in each sample. Sounds may have different numbers
     * of audio channels: one for mono, two for stereo, four for surround etc.
     */
    public int numChannels;
    
    /**
     * Sample rate, in samples per second. For PCM data this is the same as the
     * frame rate. The sample rate measures how many "snapshots" (samples) of
     * the sound pressure are taken per second, per channel. (If the sound is
     * stereo rather than mono, two samples are actually measured at each
     * instant of time: one for the left channel, and another for the right
     * channel; however, the sample rate still measures the number per channel,
     * so the rate is the same regardless of the number of channels. This is the
     * standard use of the term).
     */
    public float sampleRate;

    /**
     * Creates a new instance.
     *
     * @param in source stream. Must support mark().
     */
    public PCMDataSource(AudioInputStream in) {
        super(in, in.getFormat(), in.getFrameLength());
        source = in;
        if (!in.markSupported()) {
            throw new Error("Input stream does not support mark");
        }
        Encoding encoding = format.getEncoding();
        if (encoding.equals(Encoding.PCM_SIGNED)) {
            signed = true;
        } else if (encoding.equals(Encoding.PCM_UNSIGNED)) {
            signed = false;
        } else {
            //case Encoding.ULAW:
            //case Encoding.ALAW:
            throw new Error(encoding + " encoding not implemented");
        }
        numChannels = format.getChannels();
        sampleRate = format.getSampleRate(); // same as frameRate
        channelFrameSize = frameSize / numChannels;
        isBigEndian = format.isBigEndian();
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void mark(int readLimit) {
        source.mark(readLimit);
    }

    /**
     * Mark the input stream for a subsequent reset()
     */
    public void mark() {
        source.mark(Integer.MAX_VALUE);
    }

    @Override
    public void reset() throws IOException {
        source.reset();
    }

    /**
     * Get a single sample from the buffer 'b', given a sample index offset
     * 'sample' from a byte offset 's'. The sample comes from channel 'channel'.
     * The sample size indicates how many bits are used to store each snapshot;
     * 8 and 16 are typical values. For 16-bit samples (or any other sample size
     * larger than a byte), byte order is important; the bytes in each sample
     * are arranged in either the "little-endian" or "big-endian" style.
     * 
     * For encodings like PCM, a frame consists of the set of samples for all
     * channels at a given point in time, and so the size of a frame (in bytes)
     * is always equal to the size of a sample (in bytes) times the number of
     * channels.
     *
     * @param b audio buffer
     * @param s byte offset into buffer where data starts
     * @param sample sample offset we are interested in
     * @param channel channel to get the sample from
     * @return the sample
     */
    protected final int GETCHANNEL(byte[] b, int s, int sample, int channel) {
        int boffset = s + sample * frameSize + channel * channelFrameSize;
        int val = 0;
        if (signed) {
            if (isBigEndian) {
                val = b[boffset]; // sign extend
                for (int i = 1; i < channelFrameSize; i++) {
                    int bv = b[boffset + i];
                    val = (val << 8) | (bv & 0xFF);
                }
            } else {
                val = b[boffset + channelFrameSize - 1]; // sign extend
                for (int i = channelFrameSize - 2; i >= 0; i--) {
                    int bv = b[boffset + i];
                    val = (val << 8) | (bv & 0xFF);
                }
            }
        } else {
            if (isBigEndian) {
                for (int i = 0; i < channelFrameSize; i++) {
                    int bv = b[boffset + i];
                    val = (val << 8) | (bv & 0xFF);
                }
            } else {
                for (int i = channelFrameSize - 1; i >= 0; i--) {
                    int bv = b[boffset + i];
                    val = (val << 8) | (bv & 0xFF);
                }
            }
        }
        return val;
    }

    /**
     * Set a single sample in the buffer 'b', given a sample index offset
     * 'sample' from a byte offset 's'. The sample comes from channel 'channel'.
     *
     * @param b audio buffer
     * @param s byte offset into buffer where data starts
     * @param sample sample offset we are interested in
     * @param channel channel to set the sample value
     */
    protected final void SETCHANNEL(byte[] b, int s, int sample, int channel, int value) {
        int boffset = s + sample * frameSize + channel * channelFrameSize;
        if (isBigEndian) {
            for (int i = 0; i < channelFrameSize; i++) {
                b[boffset + i] = (byte) ((value >> (i * 8)) & 0xFF);
            }
        } else {
            for (int i = channelFrameSize - 1; i >= 0; i--) {
                b[boffset + i] = (byte) ((value >> (i * 8)) & 0xFF);
            }
        }
    }
}
