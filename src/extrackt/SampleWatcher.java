package extrackt;

import java.util.Iterator;
import java.util.ArrayList;
import java.io.IOException;

/**
 * A SampleCopier that invokes one or more watchers on each sample.
 *
 * @author crawford
 */
public class SampleWatcher extends SampleCopier {

    public interface Watcher {
        public void addSamples(float[][] samples);
    }

    private final ArrayList<Watcher> watchers = new ArrayList<>();

    public SampleWatcher(SampleSource in) {
        super(in);
    }

    /**
     * Add a watcher that will receive each sample read by the stream.
     * @param w the watcher to add
     */
    public void addWatcher(Watcher w) {
        watchers.add(w);
    }

    public void removeWatcher(Watcher w) {
        watchers.remove(w);
    }

    protected void watch(float[][] buffer) {
        Iterator<Watcher> i = watchers.iterator();
        while (i.hasNext()) {
            i.next().addSamples(buffer);
        }
    }
    
    @Override
    public int readSamples(float[][] buffer, int offset, int length) throws IOException {
        int read = source.readSamples(buffer, offset, length);
        watch(buffer);
        return read;
    }
}
