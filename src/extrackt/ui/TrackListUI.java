package extrackt.ui;

import extrackt.Track;
import extrackt.TrackList;
import java.awt.Dimension;
import java.util.Iterator;
import java.util.ArrayList;
import javax.swing.SwingUtilities;
import java.awt.Graphics;

/**
 * UI for track list manipulation
 *
 * @author crawford
 */
public class TrackListUI extends TrackList implements Painter {

    private int current_track;  // selected track
    private float current_mark; // cursor
    private float current_span; // end of range
    private String error;

    public TrackListUI() {
        current_mark = 0;
        current_track = 0;
        listeners = new ArrayList<>();
    }

    /**
     * Listeners for changes to the mark and track ends
     */
    public interface ChangeListener {

        public void trackChanged(int old, int gnew);

        public void markChanged(float oldMark, float oldSpan);

        public void spanChanged(float oldSpan);
    }
    ArrayList<ChangeListener> listeners;

    public interface WriteListener {

        public void setRange(int l, int r);

        public void setValue(int v, String mess);

        public void finished();
    }

    public String getError() {
        return error;
    }

    public void addListener(ChangeListener l) {
        listeners.add(l);
    }

    public float getCurrentMark() {
        return current_mark;
    }

    public void setCurrentMark(float d) {
        if (d < 0) {
            d = 0;
        } else if (d >= getTotalDuration()) {
            d = getTotalDuration();
        }
        if (d == current_mark) {
            return;
        }
        final float oldMark = current_mark;
        final float oldSpan = current_span;
        current_mark = d;
        current_span = 0;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Iterator<ChangeListener> i = listeners.iterator();
                while (i.hasNext()) {
                    i.next().markChanged(oldMark, oldSpan);
                }
            }
        });
    }

    public float getCurrentSpan() {
        return current_span;
    }

    public void setCurrentSpan(float d) {
        float a = current_mark + d;
        if (a < 0) {
            d = -current_mark;
        } else if (a >= getTotalDuration()) {
            d = getTotalDuration() - current_mark;
        }
        final float oldSpan = current_span;
        current_span = d;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Iterator<ChangeListener> i = listeners.iterator();
                while (i.hasNext()) {
                    i.next().spanChanged(oldSpan);
                }
            }
        });
    }

    public int getCurrentTrackNumber() {
        return current_track;
    }

    public Track getCurrentTrack() {
        return getTrack(current_track);
    }

    public void setCurrentTrack(int t) {
        if (t < 1) {
            t = 1;
        }
        if (t > (int) size()) {
            t = size();
        }
        int oldTrack = current_track;
        current_track = t;
        Iterator<ChangeListener> i = listeners.iterator();
        while (i.hasNext()) {
            ChangeListener l = i.next();
            l.trackChanged(oldTrack, current_track);
        }
    }

    public void setCurrentStart(boolean lock) {
        Track t = getTrack(current_track);
        float oldStart = t.getStart();
        float offset = current_mark - oldStart;
        if (lock) {
            for (int i = current_track; i <= (int) size(); i++) {
                t = getTrack(i);
                t.setStart(t.getStart() + offset);
                t.setEnd(t.getEnd() + offset);
            }
        } else {
            t.setStart(current_mark);
        }
        setCurrentTrack(current_track); // to trigger a display change
    }

    public void setCurrentEnd(boolean lock) {
        Track t = getTrack(current_track);
        float offset = current_mark - t.getEnd();
        t.setEnd(current_mark);
        if (lock) {
            for (int i = current_track + 1; i <= (int) size(); i++) {
                t = getTrack(i);
                t.setStart(t.getStart() + offset);
                t.setEnd(t.getEnd() + offset);
            }
        }
        setCurrentTrack(current_track); // to trigger a display change
    }

    /**
     * Insert a new track after the current mark
     */
    public void insertTrack() {
        //Track after = getTrack(current_track);
        Track t = new Track("New Track " + (size() + 1));
        t.setStart(current_mark);
        t.setEnd(current_mark);
        System.out.println("Add track " + current_track);
        add(current_track, t);
        setCurrentTrack(current_track + 1); // to trigger a display change
    }

    /**
     * Fill in gaps in the track list with new tracks. The tracks are
     * allocated names based on the newNameRoot. the new tracks are all
     * added at the end of the track list.
     * @param newNameRoot name to use as the root for new track names
     */
    public void fillInGaps(String newNameRoot) {
        float beginning = 0;
        int stop = size();
        for (int tn = 0; tn < stop; tn++) {
            Track tr = get(tn);
            float end = tr.getStart();
            if (beginning != end) {
                Track ntr = new Track(newNameRoot + " " + tn);
                ntr.setStart(beginning);
                ntr.setEnd(end);
                add(ntr);
            }
            beginning = tr.getEnd();
        }
        setCurrentTrack(1);
    }

    @Override
    public void paintWaveform(Painter.Transformer tx, Graphics g) {
        //tx.debug(true);
        g.setColor(Colors.CURRENT_MARK);
        int cm = tx.l2p_x(current_mark);
        Dimension size = tx.getSize();
        if (cm >= 0 && cm < size.width) { // size.width was numBuckets
            g.drawLine(cm, 0, cm, size.height);
        }
        if (current_span != 0) {
            int cms = tx.l2p_x(current_mark + current_span);
            // Clip mark to display
            if (cm < 0) {
                cm = 0;
            } else if (cm > size.width) {
                cm = size.width;
            }
            // Clip end of span to display
            if (cms < 0) {
                cms = 0;
            } else if (cms > size.width) {
                cms = size.width;
            }
            //System.out.println("GREEN "+current_mark + " " + current_span + ": " +cm + " " + cms);
            if (cms != cm) {
                g.setColor(Colors.SILENCE);
                g.setXORMode(Colors.CURRENT_MARK);
                if (cms > cm) {
                    g.fillRect(cm, 0, cms - cm, size.height);
                } else {
                    g.fillRect(cms, 0, cm - cms, size.height);
                }
                g.setPaintMode();
            }
        }
        //tx.debug(false);
    }

    @Override
    public void paintTracklist(Painter.Transformer tx, Graphics g) {
        Dimension size = tx.getSize();
        g.setColor(Colors.BACKGROUND);
        g.fillRect(0, 0, size.width, size.height);
        for (int i = 1; i <= size(); i++) {
            Track t = getTrack(i);
            if (i == getCurrentTrackNumber()) {
                g.setColor(Colors.SELECTED_TRACK);
            } else if ((i & 1) == 0) {
                g.setColor(Colors.EVEN_TRACK);
            } else {
                g.setColor(Colors.ODD_TRACK);
            }
            int l = tx.l2p_x(t.getStart());
            if (l < 0) {
                l = 0;
            }
            int r = tx.l2p_x(t.getEnd());
            if (r < 0) {
                r = 0;
            }
            int w = r - l;
            g.fillRect(l, 0, w, size.height);
            if (l != r) {
                int inset = size.height / 3;
                if (t.isDeleted()) {
                    g.setColor(Colors.DELETED);
                    g.fillRect(l + inset, inset, w - 2 * inset, inset);
                } else {
                    if (t.getModified() != 0) {
                        if ((t.getModified() & Track.WRITING) != 0)
                            g.setColor(Colors.WRITING);                        
                        else
                            g.setColor(Colors.MODIFIED);
                        g.fillRect(l + inset, inset, w - 2 * inset, inset);
                    }
                }
            }
        }
        g.setColor(Colors.CURRENT_MARK);
        int cm = tx.l2p_x(getCurrentMark());
        g.drawLine(cm, 0, cm, size.height);
    }
}
