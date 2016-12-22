package extrackt;

import java.io.BufferedReader;
import java.io.Reader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A linked list of tracks.
 *
 * @author crawford
 */
public class TrackList extends LinkedList<Track> {

    private float maxTime;

    private static final Pattern trackRE = Pattern.compile(
            //       ( 1  )       ( 2      )          ( 3      )                         ( 4      )         ( 5      ) 
            "^\\s*(?:(\\d+):\\s*)?([\\d.-]+)\\s*(?:\\(([\\d.-]+)\\)\\s*)?\\.\\.\\s*(?:\\(([\\d.-]+)\\)\\s*)?([\\d.-]+)\\s*$");
    private static final Pattern fieldRE = Pattern.compile(
            //    ( 1       )     ( 2 )
            "^\\s*([A-Z0-9]+):\\s*(.*?)\\s*$");
    private boolean modified;

    public TrackList() {
        maxTime = 0;
        modified = false;
    }

    /**
     * Has the definition been modified since it was last written?
     *
     * @return modification status
     */
    public boolean getTrackListModified() {
        return modified;
    }

    /**
     * Have any of the tracks been modified since they were last written?
     *
     * @param trackMod mask of Track modification bits
     * @return modification status
     */
    public boolean getTracksModified(int trackMod) {
        Iterator<Track> i = iterator();
        while (i.hasNext()) {
            Track t = i.next();
            if ((t.getModified() & trackMod) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clear modification status on tracks in the tracklist
     * @param opt 
     */
    public void clearTracksModified(int opt) {
        Iterator<Track> i = iterator();
        while (i.hasNext()) {
            Track t = i.next();
            t.clearModified(opt);
        }
        modified = false;
    }

    /**
     * Set the modification status
     */
    public void setTrackListModified(boolean changed) {
        modified = changed;
    }

    /**
     * Load the track list from a reader.
     *
     * @param rt Reader to get the track information from
     * @param defaultNameRoot root for default track names
     * @throws IOException
     */
    public void load(Reader rt, String defaultNameRoot) throws IOException {
        int err;

        Track activeTrack = null;

        BufferedReader r = new BufferedReader(rt);
        String line;
        float bigend = 0;
        int number = 0;

        while ((line = r.readLine()) != null) {
            Matcher match = trackRE.matcher(line);
            if (match.find()) {
                String number_s = match.group(1);
                String start_s = match.group(2);
                String fadei_s = match.group(3);
                String fadeo_s = match.group(4);
                String end_s = match.group(5);

                if (number_s != null) {
                    number = Integer.parseInt(number_s);
                } else {
                    number++;
                }
                activeTrack = new Track(defaultNameRoot + "_" + number);
                float start = Float.parseFloat(start_s);
                activeTrack.setStart(start);
                if (fadei_s != null) {
                    float fin = Float.parseFloat(fadei_s);
                    activeTrack.setFadeIn(fin);
                }
                float end = Float.parseFloat(end_s);
                activeTrack.setEnd(end);
                if (fadeo_s != null) {
                    float fout = Float.parseFloat(fadeo_s);
                    activeTrack.setFadeOut(fout);
                }
                add(activeTrack);
                bigend = end;
                continue;
            }

            match = fieldRE.matcher(line);
            if (match.find()) {
                if (activeTrack == null) {
                    activeTrack = new Track(defaultNameRoot + "_" + number);
                    add(activeTrack);
                }
                String field_s = match.group(1);
                String value_s = match.group(2);
                if (field_s.equals("LENGTH")) {
                    activeTrack.setStart(bigend);
                    bigend += Float.parseFloat(value_s);
                    activeTrack.setEnd(bigend);
                } else {
                    activeTrack.put(field_s, value_s);
                }
                continue;
            }

            // Not matched, clear the active track
            activeTrack = null;
        }
    }

    /**
     * Set the total time. This isn't the same as the sum of the individual
     * track lengths; rather it is the length of the audio stream the tracks are
     * embedded in.
     *
     * @param t
     */
    public void setTotalDuration(float t) {
        maxTime = t;
    }

    /**
     * The total duration of the program
     */
    public float getTotalDuration() {
        return maxTime;
    }

    /**
     * Write the track list. Tracks that are marked as deleted are not output.
     *
     * @param out
     * @throws IOException
     */
    public void writeTracks(PrintWriter out) throws IOException {
        Iterator<Track> i = iterator();
        int n = 1;
        while (i.hasNext()) {
            Track t = i.next();
            if (!t.isDeleted()) {
                out.print(n + ": ");
                t.writeTrack(out);
                n++;
            }
        }
        out.close();
    }

    /**
     * Get a specific track, by index. The track will be returned even if it has
     * been deleted.
     *
     * @param n track to retrieve
     * @return the track object
     */
    public Track getTrack(int n) {
        if (n <= 0 || n > (int) size()) {
            return null;
        }
        return get(n - 1);
    }

    /**
     * Get the first track that spans the given time
     * @param s
     * @return the track object
     */
    public Track getTrackAtTime(float s) {
        for (int i = 0; i < size(); i++) {
            Track t = get(i);
            if (t.getStart() <= s && t.getEnd() >= s)
                return t;
        }        
        return null;
    }
    
    /**
     * Remove the given track. Once removed, a track cannot be restored.
     *
     * @param track
     */
    public void removeTrack(int track) {
        Track t = getTrack(track);
        for (int i = track + 1; i <= size(); i++) {
            getTrack(i);
        }
        t.setDeleted(true);
        this.remove(t);
        modified = true;
    }
}
