package extrackt;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Random access to audio data stored in a file. Acts as a factory for
 * AudioInputStream, allowing streams to be opened to retrieve sections of the
 * file at random.
 *
 * @author Crawford Currie
 */
public class RandomAccessAudioFile {

    private AudioFileFormat fmt;
    private AudioFormat afmt;
    private RandomAccessFile raf;
    int base; // offset of data

    /**
     * An input stream for a segment of a random access file. Provides an input
     * stream that returns the bytes in a subset of the file in sequential
     * order. The stream may be marked and reset.
     *
     * @author crawford
     */
    private class RafInputStream extends InputStream {

        private RandomAccessFile raf;
        private int offset; // offset of start of window
        private int length; // length of the window
        private int read;   // number of bytes read from the window
        private int mark;   // offset of mark from start of window

        /**
         * Create an input stream that returns the bytes between offset and
         * length
         *
         * @param raf the base file we are accessing
         * @param offset the start of the window onto the file
         * @param length the length of the window
         */
        public RafInputStream(RandomAccessFile raf, int offset, int length) {
            this.raf = raf;
            this.offset = offset;
            this.length = length;
            read = 0;
            mark = 0; // a reset() with no preceding mark() will return here
        }

        @Override
        public int read() throws IOException {
            if (length - read <= 0) {
                return -1;
            }
            read++;
            raf.seek(offset + read);
            return raf.read();
        }

        @Override
        public int read(byte b[], int off, int len) throws IOException {
            if (length - read <= 0) {
                return -1;
            }
            raf.seek(offset + read);
            if (length - read - len < 0) {
                len = length - read;
            }
            int r = raf.read(b, off, len);
            read += r;
            return r;
        }

        @Override
        public int available() throws IOException {
            return length - read;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        /**
         * readLimit is ignored (mark is always valid)
         */
        @Override
        public void mark(int readLimit) {
            mark = read;
        }

        /**
         * @@verride
         */
        @Override
        public void reset() throws IOException {
            read = mark;
        }
    }

    /**
     * Creates a new instance of NavigableAudio. f is expected to contain an
     * audio stream that AudioSystem can work with (e.g. .wav. .aif, .au)
     */
    public RandomAccessAudioFile(File f) throws UnsupportedAudioFileException, IOException {
        fmt = AudioSystem.getAudioFileFormat(f);
        afmt = fmt.getFormat();
        raf = new RandomAccessFile(f, "r");
        base = fmt.getByteLength() - fmt.getFrameLength() * afmt.getFrameSize();
    }

    /**
     * Get the total time of the audio stream
     *
     * @return stream length (seconds)
     */
    public float getLength() {
        int l = fmt.getFrameLength(); // length of file, in sample frames
        float r = afmt.getSampleRate(); // number of samples per second
        return l / r;
    }

    /**
     * Convert a sample offset to a byte offset
     *
     * @param samples sample offset
     * @return byte offset
     */
    private int samples2bytes(int samples) {
        return samples * afmt.getFrameSize();
    }

    /**
     * Convert a stream time to a sample offset
     *
     * @param s time in seconds
     * @return sample offset at that time
     */
    private int seconds2samples(float s) {
        return (int) (s * afmt.getFrameRate());
    }

    /**
     * Get a stream that will return samples between time start (s) and last
     * duration (s). Streams may be marked anywhere along their length (there
     * is no read limit) for repeated reads. It is safe to have multiple
     * streams open at once, they will not interfere.
     *
     * @param start start time for samples (seconds)
     * @param duration duration of stream (seconds)
     * @return a new audio stream
     */
    public AudioInputStream getAudioInputStream(float start, float duration) {
        int firstSample = seconds2samples(start);
        int nSamples = seconds2samples(duration);
        // Clip to the stream
        if (firstSample < 0) {
            nSamples += firstSample;
            firstSample = 0;
        }
        if (nSamples < 0)
            throw new Error("Inside-out read");
        if (firstSample + nSamples > fmt.getFrameLength()) {
            nSamples = fmt.getFrameLength() - firstSample;
        }
        int fs = base + samples2bytes(firstSample);
        int ns = samples2bytes(nSamples);
        AudioInputStream stream = new AudioInputStream(
                new RafInputStream(raf, fs, ns), afmt, nSamples);
        return stream;
    }

    /**
     * Length of the audio data contained in the file, expressed in sample
     * frames.
     *
     * @return length of the data, number of sample frames
     */
    public int getSampleLength() {
        return fmt.getFrameLength();
    }

    /**
     * Get the current file position as a sample offset
     *
     * @return current position, in samples
     * @throws IOException
     */
    public long getSamplePosition() throws IOException {
        return (raf.getFilePointer() - base) / afmt.getFrameSize();
    }

    /**
     * Set current file position, offset in samples
     *
     * @param sample file position, sample offset
     * @throws IOException
     */
    public void setSamplePosition(int sample) throws IOException {
        raf.seek(base + samples2bytes(sample));
    }
}
