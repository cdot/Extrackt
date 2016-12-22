package extrackt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

/**
 * Simple watcher that scans for low amplitude periods in a sample stream and
 * wakens a listener each time one is seen. The listener can terminate the
 * listening process, otherwise data is passed on to the consumer.
 *
 * A silence is defined as a period during which the amplitude of the signal
 * on all channels does not exceed a threshold. A period has a minimum length.
 *
 * The input stream is passed on to the consumer unmodified.
 *
 * @author Crawford Currie
 */
public class SilenceWatcher extends SampleCopier {

    private final ArrayList<Suspect> thresholds;
    private int mark;
    private int samplesRead;
    private final AudioRangeListener listener;

    /**
     * Class of thresholds. We gather silences for each different threshold
     */
    private class Suspect {

        private final Silences.Threshold threshold;
        private final int minSamples; // samples
        public int silenceStart;
        public int silenceLength; // samples
        public int max;
        
        public Suspect(Silences.Threshold t) {
            threshold = t;
            // precompute this
            minSamples = (int) (t.duration * source.getSampleRate());
            silenceStart = -1;
            silenceLength = 0;
            max = 0;
        }
        
        // Called for each sample
        public boolean sample(float[] samples, int nChannels, int samplesRead) {
            boolean extend = false;
            for (int i = 0; i < nChannels; i++) {
                if (samples[i] <= threshold.level) {
                    if (samples[i] > max)
                        max = (int)samples[i]; // samples should be integer anyway
                    extend = true;
                }
            }
            if (extend) {
                // Sample is below the threshold - extend the silence if
                // appropriate
                if (silenceStart >= 0) {
                    silenceLength++;
                } else {
                    silenceStart = samplesRead;
                    silenceLength = 1;
                }
            } else {
                // Sample is over the threshold - terminate any open silence
                if (silenceStart >= 0 && silenceLength > minSamples) {
                    // We've had a silence; notify our listener
                    if (!endSilence()) {
                        return false;
                    }
                }
                silenceStart = -1;
                max = 0;
            }
            return true;
        }
        
        // Called at the end
        public boolean endScan() {
            if (silenceStart >= 0 && silenceLength > minSamples) {
                // At the end of the stream, and there's an active silence
                // Notify our listener
                if (!endSilence()) {
                    return false;
                }
            }
            return true;
        }
        
        private boolean endSilence() {
            float start = (mark + silenceStart) / source.getSampleRate();
            float end = (mark + silenceStart + silenceLength) / source.getSampleRate();
            int[] data = new int[2];
            data[0] = threshold.level; data[1] = max;
            return listener.rangeEvent(start, end, data);
        }
    }
    
    /**
     * Creates a new instanumChannelse of SilenceFilter
     *
     * @param in input stream
     * @param l listener to call when a silence is detected. The
     * maximum level seen in the silence is passed in the data.
     * @param ts list of thresholds to detect
     */
    public SilenceWatcher(SampleSource in, AudioRangeListener l, List<Silences.Threshold> ts) {
        super(in);
        Iterator<Silences.Threshold> tit = ts.iterator();
        thresholds = new ArrayList<>();
        while (tit.hasNext()) {
            thresholds.add(new Suspect(tit.next()));
        }
        samplesRead = 0;
        listener = l;
    }

    @Override
    public int readSamples(float[][] buff, int offset, int length) throws IOException {
        int read = source.readSamples(buff, offset, length);
        Iterator<Suspect> tit;
       
        for (int i = 0; i < read; i++) {
            samplesRead++;

            tit = thresholds.iterator();
            while (tit.hasNext()) {
                Suspect threshold = tit.next();
                if (!threshold.sample(buff[i], source.getNumChannels(), samplesRead))
                    return -1;
            }
        }

        if (read < length) {
            tit = thresholds.iterator();
        
            while (tit.hasNext()) {
                Suspect threshold = tit.next();
                if (!threshold.endScan())
                    return -1;
            }
        }

        return read;
    }
}
