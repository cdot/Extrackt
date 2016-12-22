package extrackt;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Iterator;
import java.util.Vector;
import java.util.ArrayList;
import java.util.List;
import java.io.BufferedReader;
import java.io.Reader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * A sequence of silences. No ordering is imposed, each silence records start
 * and end times.
 * An object of this type also has a list of thresholds that were used in
 * determining the list of silences.
 *
 * @author crawford
 */
public class Silences extends ArrayList<Silence> {

    private boolean modified;
    private List<Threshold> thresholds;

    private static Pattern THRE = Pattern.compile("^T +(\\d+) +([\\d.]+)$");

    /**
     * A threshold is a level and a duration for which that level must not
     * be exceeded (on any channel) to qualify as a silence.
     */
    public static class Threshold {

        /**
         * Maximum level above which the silence has ended
         */
        public int level;
        /**
         * Minimum duration of silence to trigger this threshold
         */
        public float duration;

        public Threshold(int l, float ml) {
            level = l;
            duration = ml;
        }

        public void write(PrintWriter out) {
            out.println("T " + level + " " + duration);
        }

        public String toString() {
            return "level " + level + " duration " + duration;
        }
    }

    protected Silences() {
        thresholds = new ArrayList<>();
        modified = false;
    }

    public Silences(List<Threshold> t) {

        thresholds = new ArrayList<>();
        Iterator<Threshold> tit = t.iterator();
        while (tit.hasNext()) {
            thresholds.add(tit.next());
        }
        modified = false;
    }

    /** Add threshold */
    public void addThreshold(int level, float ml) {
        thresholds.add(new Threshold(level, ml));
    }

    /** Remove all thresholds at the given level */
    public void deleteThreshold(int level) {
        Iterator<Threshold> ti = thresholds.iterator();
        while (ti.hasNext()) {
            Threshold t = ti.next();
            if (t.level == level) {
                ti.remove();
            }
        }
    }
    
    public void clearThresholds() {
        Iterator<Threshold> ti = thresholds.iterator();
        while (ti.hasNext()) {
            deleteThreshold(ti.next().level);
        }
    }
    
    public List<Threshold> getThresholds() {
        return thresholds;
    }

    /**
     * Has the definition been modified since it was last written?
     *
     * @return modification status
     */
    public boolean getModified() {
        return modified;
    }

    /**
     * Set the modification status
     */
    public void setModified(boolean changed) {
        modified = changed;
    }

    public void load(Reader f) throws IOException {
        BufferedReader r = new BufferedReader(f);
        String line;
        clearThresholds();
        int lino = 0;
        while ((line = r.readLine()) != null) {
            lino++;
            line = line.trim();
            if (line.equals("") || line.charAt(0) == '#') // comment
            {
                continue;
            }
            Matcher match = Silence.RE.matcher(line);
            if (match.find()) {
                float start = Float.parseFloat(match.group(1));
                float end = Float.parseFloat(match.group(2));
                int th = Integer.parseInt(match.group(3));
                int max = Integer.parseInt(match.group(4));
                addSilence(start, end, th, max);
                continue;
            }
            match = THRE.matcher(line);
            if (match.find()) {
                addThreshold(Integer.parseInt(match.group(1)),
                        Float.parseFloat(match.group(2)));
                continue;
            }
            throw new IOException("Unexpected content in silences@" + lino + ": " + line);
        }
        modified = false;
    }

    /**
     * Add a new silence
     *
     * @param start start of the silence
     * @param end end of the silence
     */
    public void addSilence(float start, float end, int th, int max) {
        if (start >= end) {
            return;
        }
        for (int i = 0; i < size(); i++) {
            Silence s = get(i);
            if (start >= s.getStart()) {
                if (start <= s.getEnd()) {
                    if (end > s.getEnd()) {
                        while (i + 1 < size()) {
                            Silence n = get(i + 1);
                            if (n.getStart() > end) {
                                break;
                            }
                            if (n.getEnd() > end) {
                                end = n.getEnd();
                            }
                            remove(i + 1);
                        }
                        s.setEnd(end);
                    }
                    return;
                } // else next silence
            }

            if (end <= s.getEnd()) {
                // start is before the beginning of this silence, but
                // after the end of the previous silence
                s.setStart(start);
                return;
            }
        }
        add(new Silence(start, end, th, max));
        modified = true;
    }

    public void write(PrintWriter out) throws IOException {
        Iterator<Threshold> tit = thresholds.iterator();
        while (tit.hasNext()) {
            tit.next().write(out);
        }
        Iterator<Silence> j = iterator();
        while (j.hasNext()) {
            Silence s = j.next();
            s.write(out);
        }
        modified = false;
    }
}
