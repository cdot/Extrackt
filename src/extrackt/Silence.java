package extrackt;

import java.io.PrintWriter;
import java.util.regex.Pattern;

/**
 * Object representing a single period of silence
 *
 * @author crawford
 */
public class Silence {
    public static final Pattern RE = Pattern.compile(
            "^S +([-\\d.E]+) +([-\\d+.E]+) +(\\d+) +(\\d+)$");

    /** Start and end of the silence, seconds offset from the start pf the stream */
    private float start, end;
    /** Threshold for the silence, and maximum detected level, sum of all channels */
    private int threshold, level;

    public void setStart(float s) {
        start = s;
    }
    
    public float getStart() {
        return start;
    }

    public void setEnd(float e) {
        end = e;
    }

    public float getEnd() {
        return end;
    }

    public void setThreshold(int th) {
        threshold = th;
    }
    
    public int getThreshold() {
        return threshold;
    }

    public void setLevel(int l) {
        level = l;
    }
    
    public int getLevel() {
        return level;
    }

    public Silence(float s, float e, int t, int m) {
        start = s;
        end = e;
        threshold = t;
        level = m;
    }

    public String toString() {
        String s = start + ".." + end + ", " + level + " <= " + threshold;
        return s;
    }

    public void write(PrintWriter out) {
        out.println("S " + start + " " + end + " " + threshold + " " + level);
    }
}
