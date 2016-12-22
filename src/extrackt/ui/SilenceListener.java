package extrackt.ui;

import extrackt.AudioRangeListener;
import extrackt.Silences;
import javax.swing.SwingUtilities;

/**
 * Listener that's triggered when a silence is found. Updates the mark to the
 * start of the next silence, assuming the scan started at the current mark.
 *
 * @author crawford
 */
public class SilenceListener implements AudioRangeListener {

    TrackListUI trackList;

    /**
     * Move mark to next silence
     *
     * @param tl track list to set the mark in
     */
    public SilenceListener(TrackListUI tl) {
        trackList = tl;
    }

    /**
     * Mark the silence in the UI thread by moving the mark, and adding the
     * silence to the trackList.
     */
    private class UpdateMark implements Runnable {

        float start, end;
        int threshold, level;

        UpdateMark(float s, float e, Object data) {
            start = s;
            end = e;
            int[] d = (int[]) data;
            threshold = d[0];
            level = d[1];
        }

        public void run() {
            trackList.setCurrentMark(trackList.getCurrentMark() + start);
        }
    }

    public boolean rangeEvent(float start, float end, Object data) {
        // Mark next silence
        SwingUtilities.invokeLater(new UpdateMark(start, end, data));
        return trackList == null;
    }
}
