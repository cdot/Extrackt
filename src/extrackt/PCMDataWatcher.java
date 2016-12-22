/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package extrackt;

import javax.sound.sampled.AudioInputStream;

import java.util.ArrayList;
import java.util.Iterator;
import java.io.IOException;

/**
 * A watcher that sits on a PCM data stream and invokes a sample watcher
 * on each sample before passing the data on to the invoker.
 */
public class PCMDataWatcher extends PCMDataSource {
    private final ArrayList<SampleWatcher.Watcher> watchers;
    
    public PCMDataWatcher(AudioInputStream in) {
        super(in);
        watchers = new ArrayList<>();
    }
    
    public void addWatcher(SampleWatcher.Watcher w) {
        watchers.add(w);
    }
    
    public void removeWatcher(SampleWatcher.Watcher w) {
        watchers.remove(w);
    }
    
    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
        int read = source.read(b, offset, length);
        
        Iterator<SampleWatcher.Watcher> it = watchers.iterator();
        float[][] buff = null;
        int ns;
        while (it.hasNext()) {
            if (buff == null) {
                ns = read / frameSize;
                buff = new float[ns][numChannels];
                for (int i = 0; i < ns; i++) {
                    for (int c = 0; c < numChannels; c++) {
                        buff[i][0] = GETCHANNEL(b, offset, i, c);
                    }
                }
            }
            it.next().addSamples(buff);
        }
        return read;
    }
}
