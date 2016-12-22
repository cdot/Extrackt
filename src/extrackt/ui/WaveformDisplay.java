package extrackt.ui;

import java.awt.Graphics;
import java.awt.Image;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.Iterator;
import extrackt.SampleWatcher;

/**
 * A WaveformDisplay is a panel that displays a streamed waveform, and
 * decorations on the waveform such as the current mark. It supports additional
 * listeners (called Painters) which allow you to add extra decoration, such as
 * silences, as the waveform is drawn. Because the resolution of a sample stream
 * is a lot higher than we want to display, samples are collected into
 * "buckets". Each bucket corresponds to a single x-ordinate on the output
 * display.
 *
 * @author Crawford Currie
 */
public class WaveformDisplay extends JPanel implements TrackListUI.ChangeListener, Painter.Transformer, SampleWatcher.Watcher {

    private TrackListUI trackList;

    private float[] mins;
    private float[] maxs;
    private static final int refreshRate = 10; // refresh every 10 buckets
    private int height;
    private int rightBucket, numBuckets;
    private float sampleRate;
    private int sampleCount, bucketCount, pending, bucketSize;
    private float max;
    private int origin;
    private JLabel startLabel, endLabel, minLabel, maxLabel;
    private final ArrayList<Painter> painters;
    private boolean debug = false;

    // The waveform is drawn onto a RAM buffer that is then blatted to the
    // display
    private Image bufferImage;
    private Graphics bufferGraphics;

    static final int NOWAVE = 20;

    private boolean decorate;
    
    /**
     * Creates a new instance of WaveformDisplay
     */
    public WaveformDisplay() {
        mins = null;
        maxs = null;
        painters = new ArrayList<>();
        decorate = true;
    }

    public void setTrackList(TrackListUI trackList) {
        this.trackList = trackList;
    }

    public void addPainter(Painter p) {
        painters.add(p);
    }

    public void decorate(boolean state) {
        decorate = state;
        if (decorate)
            repaint();
    }
    
    /**
     * Convert X ordinate to bucket number
     */
    private int x2bucket(int x) {
        return rightBucket + x;
    }

    /**
     * Convert bucket number to x ordinate
     */
    private int bucket2x(int bucket) {
        return bucket - rightBucket;
    }

    /**
     * Convert bucket number to sample number
     */
    private int bucket2sample(int bucket) {
        return origin + bucket * bucketSize;
    }

    /**
     * Find the buckets for a given sample offset
     */
    private int sample2bucket(int sample) {
        if (bucketSize == 0) {
            return 0;
        }
        return (sample - origin) / bucketSize;
    }

    /**
     * Convert a sample number to a time in seconds
     */
    private float sample2s(int sample) {
        if (sampleRate == 0) {
            return 0;
        }
        return sample / sampleRate;
    }

    /**
     * Convert a time in seconds to a sample number
     */
    private int s2sample(float s) {
        return (int) (s * sampleRate);
    }

    @Override
    public void debug(boolean b) {
        debug = b;
    }

    @Override
    public boolean debug() {
        return debug;
    }

    /**
     * Convert a time in seconds to an x-ordinate - implements
     * Painter.Transformer
     */
    @Override
    public int l2p_x(float s) {
        int sam = s2sample(s);
        int buck = sample2bucket(sam);
        int x = bucket2x(buck);
        //if (debug) System.out.println(s+":"+sam+":"+buck+"("+origin+")"+":"+x);
        return x;
    }

    /**
     * Convert an x-ordinate to a time in seconds
     */
    private float p2l_s(int x) {
        return sample2s(bucket2sample(x2bucket(x)));
    }

    public void setMinMaxLabels(JLabel start, JLabel end, JLabel min, JLabel max) {
        startLabel = start;
        endLabel = end;
        minLabel = min;
        maxLabel = max;
    }

    /**
     * Reset
     *
     * @param o display origin (in seconds)
     * @param r sample rate
     */
    public void reset(float o, float r) {
        origin = s2sample(o);
        sampleRate = r;
        sampleCount = 0;
        bucketCount = 0;
        rightBucket = 0;
        max = 1;

        // Each bucket is 1/500th of a second. We do this to ensure that a
        // second of waveform is a consistent width, irrespective of the size
        // of the display.
        bucketSize = (int) (sampleRate / 500);
        recomputeSize();
        refresh();
    }

    private void relabel() {
        int borg = sample2bucket(origin) + rightBucket;
        if (startLabel != null) {
            startLabel.setText(Float.toString(sample2s(bucket2sample(borg))));
        }
        if (endLabel != null) {
            endLabel.setText(Float.toString(sample2s(bucket2sample(borg + numBuckets))));
        }
    }

    public void recomputeSize() {
        Dimension size = getSize();
        mins = new float[size.width];
        maxs = new float[size.width];
        for (int i = 0; i < size.width; i++) {
            mins[i] = maxs[i] = 0;
        }
        numBuckets = size.width;
        height = size.height - NOWAVE;
        relabel();
        pending = 0;
        bufferImage = createImage(numBuckets, height);
        bufferGraphics = bufferImage.getGraphics();
        bufferGraphics.setColor(Colors.BACKGROUND);
        bufferGraphics.fillRect(0, 0, numBuckets, height);
    }

    @Override
    public void markChanged(float oldMark, float oldSpan) {
        int h = getSize().height;
        int cm = l2p_x(oldMark);
        int cms = l2p_x(oldSpan);
        if (cms > 0) {
            repaint(cm, 0, cms, h);
        } else {
            repaint(cm + cms, 0, -cms, h);
        }
        cm = l2p_x(trackList.getCurrentMark());
        cms = l2p_x(trackList.getCurrentSpan());
        if (cms > 0) {
            repaint(cm, 0, cms, h);
        } else {
            repaint(cm + cms, 0, -cms, h);
        }
    }

    @Override
    public void spanChanged(float oldSpan) {
        markChanged(trackList.getCurrentMark(), oldSpan);
    }

    @Override
    public void trackChanged(int o, int n) {
        // not interested
    }

    /**
     * Implement SampleWatcher.Watcher
     *
     * @param samples being watched
     */
    @Override
    public void addSamples(float[][] samples) {
        for (int i = 0; i < samples.length; i++) {
            addSample(samples[i]);
        }
    }

    /**
     * @param sample sample to add
     */
    public void addSample(float[] sample) {
        sampleCount++;

        int head = bucketCount % numBuckets;
        for (int c = 0; c < sample.length; c++) {
            if (sample[c] < mins[head]) {
                mins[head] = sample[c];
            }
            if (sample[c] > maxs[head]) {
                maxs[head] = sample[c];
            }
        }

        if (sampleCount % bucketSize == 0) {
            bucketCount++;
            pending++;
            if (maxs[head] > max || -mins[head] > max) {
                // Have to rescale the canvas
                max = maxs[head];
                if (-mins[head] > max) {
                    max = -mins[head];
                }
                if (minLabel != null) {
                    minLabel.setText(Float.toString(-max));
                }
                if (maxLabel != null) {
                    maxLabel.setText(Float.toString(max));
                }
                pending = Math.min(bucketCount, numBuckets);
                refresh();
            } else if (bucketCount % refreshRate == 0 && pending > 0) {
                // We batch up calls to repaint to avoid calling it on
                // every sample
                refresh();
            }
        }
    }

    @Override
    public float getMaxY() {
        return max;
    }

    private void refresh() {
        Dimension size = getSize();
        int height = size.height - NOWAVE;
        bufferGraphics.setColor(Colors.BACKGROUND);
        int midy = height / 2;
        float fact = (float) midy / max;
        int paintStart = bucketCount - pending;
        if (bucketCount - rightBucket > numBuckets) {
            // need scroll
            int shift = bucketCount - rightBucket - numBuckets;
            bufferGraphics.copyArea(shift, 0, numBuckets - shift, height, -shift, 0);
            rightBucket += shift;
            relabel();
        }
        bufferGraphics.fillRect(paintStart - rightBucket, 0, pending, height);
        bufferGraphics.setColor(Colors.WAVEFORM_SAMPLES);
        for (int j = paintStart; j < bucketCount; j++) {
            int i = (bucketCount - pending) % numBuckets;
            bufferGraphics.drawLine(j - rightBucket, (int) (midy + fact * mins[i]),
                    j - rightBucket, (int) (midy + fact * maxs[i]));
            pending--;
        }
        pending = 0;
        repaint();
    }

    @Override
    public void paintComponent(Graphics g) {
        Dimension size = getSize();
        if (bufferImage == null) {
            g.setColor(Colors.BACKGROUND);
            g.fillRect(0, 0, size.width, size.height);
        } else {
            g.drawImage(bufferImage, 0, 0, this);
        }
        if (decorate) {
            g.setColor(Colors.BACKGROUND);
            g.fillRect(0, size.height - NOWAVE, size.width, NOWAVE);
            Iterator<Painter> i = painters.iterator();
            while (i.hasNext()) {
                Painter p = (Painter) i.next();
                p.paintWaveform(this, g);
            }
        }
    }

    public void onMouseEvent(MouseEvent evt) {
        if (trackList != null) {
            float newMark = p2l_s(evt.getX());
            trackList.setCurrentMark(newMark);
            repaint();
        }
    }

    public void onMouseMoved(MouseEvent evt) {
        Dimension size = getSize();
        float pos = p2l_s(evt.getX());
        setToolTipText(Float.toString(pos));
    }

    public void onMouseDragged(MouseEvent evt) {
        if (trackList != null) {
            float newSpanEnd = p2l_s(evt.getX());
            float newSpan = newSpanEnd - trackList.getCurrentMark();
            trackList.setCurrentSpan(newSpan);
            repaint();
        }
    }
}
