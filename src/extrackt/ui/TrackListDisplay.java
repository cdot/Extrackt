package extrackt.ui;

import extrackt.Track;
import javax.swing.JPanel;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.Graphics;
import java.awt.Dimension;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.ArrayList;

/**
 * Canvas for the tracks display.
 * Canvas for display of full track tracklist. Designed to be subclassed for
 * tracklist windows. This is a Panel, rather than a canvas, for reasons I don't
 * recall.
 * @author Crawford Currie
 */
public class TrackListDisplay extends JPanel implements TrackListUI.ChangeListener, Painter.Transformer {
    protected TrackListUI tracklist;
    private final ArrayList<Painter> painters;
    private boolean debug = false;

    public TrackListDisplay() {
        tracklist = null;
        painters = new ArrayList<>();
    }
    
    public void addPainter(Painter p) {
        painters.add(p);
    }
    
    /**
     * Set the track list we are displaying
     * @param tl the track list we are displaying
     **/
    public void setTrackList(TrackListUI tl) {
        tracklist = tl;
    }

    @Override
    public float getMaxY() {
        return 1;
    }
   
    @Override
    public int l2p_x(float s) {
        if (tracklist == null) {
            return 0;
        }
        return (int) (s * getSize().width / tracklist.getTotalDuration());
    }

    @Override
    public void debug(boolean b) {
        debug = b;
    }
    
    @Override
    public boolean debug() {
        return debug;
    }
    
    public float p2l_x(int x) {
        if (tracklist == null) {
            return 0;
        }
        return x * tracklist.getTotalDuration() / getSize().width;
    }
    
    /**
     * Invoked when the currently selected track is changed
     * @param old old track
     * @param gnew new track
     */
    @Override
    public void trackChanged(int old, int gnew) {
        int h = getSize().height;
        Track t = tracklist.getTrack(old);
        if (t != null) {
            int l = l2p_x(t.getStart());
            int r = l2p_x(t.getEnd());
            repaint(l, 0, r - l, h);
        }
        t = tracklist.getTrack(gnew);
        if (t != null) {
            int l = l2p_x(t.getStart());
            int r = l2p_x(t.getEnd());
            repaint(l, 0, r - l, h);
        }
    }

    /**
     * Invoked when the current mark is changed
     * @param oldMark old mark
     * @param oldSpan new span
     */
    @Override
    public void markChanged(float oldMark, float oldSpan) {
        int h = getSize().height;
        int cm = l2p_x(oldMark);
        if (cm > 1)
            cm--;
        repaint(cm, 0, 3, h);
        cm = l2p_x(tracklist.getCurrentMark());
        if (cm > 1)
            cm--;
        repaint(cm, 0, 3, h);
    }
    
    @Override
    public void spanChanged(float oldSpan) {
        // NOP
    }
    
    @Override
    public void paintComponent(Graphics g) {
        Dimension size = getSize();
        g.setColor(Colors.BACKGROUND);
        g.fillRect(0, 0, size.width, size.height);
        if (tracklist == null) {
            return;
        }
        Iterator<Painter> i = painters.iterator();
        while (i.hasNext()) {
            Painter p = i.next();
            p.paintTracklist(this, g);
        }
    }
    
    public void onMouseEvent(MouseEvent evt) {
        if (tracklist == null)
            return;
        Dimension size = getSize();
        tracklist.setCurrentMark(p2l_x(evt.getX()));
    }
    
    public void onMouseWheeled(MouseWheelEvent evt, float dist) {
        if (tracklist == null)
            return;
        tracklist.setCurrentMark(tracklist.getCurrentMark() + evt.getWheelRotation() * dist);
    }
    
    public void onMouseMoved(MouseEvent evt) {
        Dimension size = getSize();
        float pos = p2l_x(evt.getX());
        int mins = (int)(pos / 60);
        int hours = mins / 60;
        mins = mins - (hours * 60);
        float secs = pos - (60 * (mins + 60 * hours));
        StringWriter sw = new StringWriter();
        PrintWriter psw = new PrintWriter(sw);
        if (hours > 0)
            psw.format("%d:%02d:%02.02f", hours, mins, secs);
        else if (mins > 0)
            psw.format("%02d:%02.02f",  mins, secs);
        else
            psw.format("%02.02f", secs);
        setToolTipText("" + sw.getBuffer());
    }
}
