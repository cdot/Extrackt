package extrackt.ui;

import javax.swing.JLabel;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * A limited window on a track list.
 *
 * @author crawford
 */
public class TrackListWindow extends TrackListDisplay {

    float window; // if > 0, width of a window centred on the mark
    JLabel[] labels;
    float mark;

    /**
     * Creates a new instance of TrackListUIWindow
     */
    public TrackListWindow() {
        window = 0;
        mark = 0;
        labels = null;
    }

    /**
     * Set the size of the window, in seconds
     *
     * @param s width of the window, in seconds
     */
    public void setWindow(float s) {
        window = s;
        repaint();
    }

    /**
     * Set the list of labels along the bottom of the window
     *
     */
    public void setLabels(JLabel[] l) {
        labels = l;
    }

    public void trackChanged(int old) {
        repaint();
    }

    @Override
    public void markChanged(float oldMark, float oldSpan) {
        mark = tracklist.getCurrentMark();
        float left = (mark - window / 2);
        if (labels != null) {
            float gap = window / (labels.length - 1);
            float x = left;
            for (int j = 0; j < labels.length; j++) {
                labels[j].setText(Float.toString(x - mark));
                x += gap;
            }
        }
        repaint(); // window centred on mark
    }

    @Override
    public int l2p_x(float s) {
        if (tracklist == null) {
            return 0;
        }
        float left = tracklist.getCurrentMark() - window / 2;
        return (int) ((s - left) * getSize().width / window);
    }

    @Override
    public void onMouseEvent(MouseEvent evt) {
        if (tracklist == null) {
            return;
        }
        Dimension size = getSize();
        float left = tracklist.getCurrentMark() - window / 2;
        float newMark = left + evt.getX() * window / size.width;
        if (newMark < 0) {
            newMark = 0;
        }

        tracklist.setCurrentMark(newMark);
    }

    @Override
    public void onMouseWheeled(MouseWheelEvent evt, float dist) {
        if (tracklist == null) {
            return;
        }
        Dimension size = getSize();
        tracklist.setCurrentMark(tracklist.getCurrentMark() + evt.getWheelRotation() * window / size.width);
    }

    @Override
    public void onMouseMoved(MouseEvent evt) {
        if (tracklist == null) {
            return;
        }
        Dimension size = getSize();
        float pos;
        float left = tracklist.getCurrentMark() - window / 2;
        pos = left + evt.getX() * window / size.width;
        setToolTipText(Float.toString(pos - tracklist.getCurrentMark())
                + "=" + Float.toString(pos));
    }
}
