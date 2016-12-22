package extrackt.ui;

import extrackt.Silence;
import extrackt.Silences;
import extrackt.AudioRangeListener;

import javax.swing.ListModel;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListDataEvent;
import javax.swing.JList;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Color;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.ListIterator;

/**
 * Add painters to a silences list. Also acts as a ListModel for the list of
 * thresholds in the parameters dialog (yuck)
 *
 * @author crawford
 */
public class SilencesUI extends Silences implements Painter, ListModel<Silences.Threshold> {

    private ArrayList<ListDataListener> listeners;

    public SilencesUI() {
        super();
        listeners = new ArrayList<>();
    }

    // Implement ListModel
    @Override
    public void addListDataListener(ListDataListener l) {
        listeners.add(l);
    }

    // Implement ListModel
    @Override
    public void removeListDataListener(ListDataListener l) {
        listeners.remove(l);
    }

    // Implement ListModel
    @Override
    public int getSize() {
        return getThresholds().size();
    }

    // Implement ListModel
    @Override
    public Silences.Threshold getElementAt(int i) {
        return getThresholds().get(i);
    }

    // Override Silences
    @Override
    public void addThreshold(int level, float ml) {
        int top = getThresholds().size();
        super.addThreshold(level, ml);
        ListDataEvent e = new ListDataEvent(this, top, top, ListDataEvent.INTERVAL_ADDED);
        Iterator<ListDataListener> li = listeners.iterator();
        while (li.hasNext()) {
            li.next().intervalAdded(e);
        }
    }

    // Override Silences
    @Override
    public void deleteThreshold(int level) {
        int top = getThresholds().size() - 1;
        super.deleteThreshold(level);
        if (top >= 0) {
            ListDataEvent e = new ListDataEvent(this, 0, top, ListDataEvent.INTERVAL_REMOVED);
            Iterator<ListDataListener> li = listeners.iterator();
            while (li.hasNext()) {
                li.next().contentsChanged(e);
            }
        }

    }

    @Override
    public void clearThresholds() {
        int top = getThresholds().size();
        super.clearThresholds();
        if (top >= 0) {
            ListDataEvent e = new ListDataEvent(this, 0, top, ListDataEvent.INTERVAL_REMOVED);
            Iterator<ListDataListener> li = listeners.iterator();
            while (li.hasNext()) {
                li.next().intervalRemoved(e);
            }
        }
    }

    private void paintSilences(Painter.Transformer tx, Graphics g) {
        Dimension size = tx.getSize();
        int maxt = 0;
        ListIterator<Silences.Threshold> ti = getThresholds().listIterator();
        HashMap<Integer,Color> lcm = new HashMap<>();
        while (ti.hasNext()) {
            int lim = ti.next().level;
            if (lim > maxt)
                maxt = lim;
        }
        float h = size.height * 0.25f;
        ListIterator<Silence> i = listIterator();
        while (i.hasNext()) {
            Silence s = i.next();
            int ts = s.getLevel();
            float ratio = ((float)ts) / maxt;
            Color c = lcm.get(ts);
            if (c == null) {
                lcm.put(ts, new Color(ratio, 0, 0));
            }
            g.setColor(c);
            int l = tx.l2p_x(s.getStart());
            if (l < 0) l = 0;
            int r = tx.l2p_x(s.getEnd());
            if (r > size.width) r = size.width;
            if (r > l) {
                g.fillRect(l, size.height - (int)(h * ratio), r - l, (int)(h * ratio));
            }
        }
    }
        
    // Implement Painter
    @Override
    public void paintWaveform(Painter.Transformer tx, Graphics g) {
         /* if (threshold > 0) {
         Dimension size = tx.getSize();
         int midy = (size.height - WaveformDisplay.NOWAVE) / 2;
         float fact = (float) midy / t.getMaxY();
         g.setColor(Colors.SILENCE_LIMIT);
         int yl = (int) (midy - fact * threshold);
         int yh = (int) (midy + fact * threshold);
         int px = 5;
         int x = size.width;
         while (x > 0) {
         g.drawLine(x, yh, x - px, yh);
         g.drawLine(x, yl, x - px, yl);
         x -= 2 * px;
         }
         } */
        paintSilences(tx, g);
     }

    // Implement Painter
    @Override
    public void paintTracklist(Painter.Transformer tx, Graphics g) {
        paintSilences(tx, g);
    }

}
