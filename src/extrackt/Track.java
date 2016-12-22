package extrackt;

import java.util.HashMap;
import java.util.Iterator;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.OutputStream;

/**
 * Meta-data for a track
 *
 * @author crawford
 */
public class Track extends HashMap<String, String> {

    // Start and end of the track, in seconds
    private float start_s, end_s;
    // Fade in and fade out for the track
    private float fadeIn_s, fadeOut_s;
    // Bitmask, showing what needs writing
    private int modified;
    // Deleted?
    private boolean deleted;
    // Default name
    private final String defaultName;
    
    private int peak_level, target_level;

    public int getTargetLevel() {
        return target_level;
    }

    public void setTargetLevel(int target_level) {
        this.target_level = target_level;
    }

    public int getPeakLevel() {
        return peak_level;
    }

    public void setPeakLevel(int peak_level) {
        this.peak_level = peak_level;
    }
    
    /**
     * Aspects
     */
    public static final int SCRIPT = 1; // .lame file
    public static final int AUDIO = 1 << 1; //.wav file
    public static final int TRACK = 1 << 2; // .tracks file entry
    public static final int WRITING = 1 << 3; // track being written
    public static final int ALL = (SCRIPT | AUDIO | TRACK);

    /**
     * Creates a new instance of Track
     * @param defName default name, if nothing better if found in the metadata
     */
    public Track(String defName) {
        start_s = end_s = 0;
        fadeIn_s = fadeOut_s = 0;
        modified = ALL;
        deleted = false;
        defaultName = defName;
        peak_level = 0;
    }

    /**
     * Has the definition been modified since it was last written?
     * @return modification status
     */
    public int getModified() {
        if (deleted)
            return 0;
        return modified;
    }

    /**
     * Set the modification status
     * @param changed bitmask of the aspects that have changed; a combination
     * of SCRIPT, AUDIO and TRACK
     */
    public void setModified(int changed) {
        modified |= changed;
    }

    /**
     * Clear the modification status
     * @param changed bitmask of aspects to clear
     */
    public void clearModified(int changed) {
        modified &= ~changed;
    }

    /**
     * Get a filter that applies the level normalisation
     *
     * @param in the audio stream to fade
     * @return an audio stream that will play the snippet
     */
    public AudioInputStream getLevelFilter(AudioInputStream in) {
        if (peak_level > 0 && target_level > 0 && target_level != peak_level)
            return new NormalisationFilter(in, peak_level, target_level);
        else
            return in;
    }
    
    /**
     * Get a filter that applies the fade in and the fade out
     *
     * @param in the audio stream to fade
     * @return an audio stream that will play the snippet
     */
    public AudioInputStream getFadeFilter(AudioInputStream in) {
        if (fadeIn_s > 0 || fadeOut_s > 0)
            return new FadeFilter(in, fadeIn_s, fadeOut_s);
        else
            return in;
    }

    /**
     * Get a filter that just applies the fade in
     *
     * @param in the audio stream to fade
     * @return a stream that modifies the input stream with the fade filter
     */
    public AudioInputStream getStartFadeFilter(AudioInputStream in) {
        return new FadeFilter(in, getFadeIn(), 0);
    }

    /**
     * Get a filter that just applies the fade out
     *
     * @param in the audio stream to fade
     * @return a stream that modifies the input stream with the fade filter
     */
    public AudioInputStream getEndFadeFilter(AudioInputStream in) {
        return new FadeFilter(in, 0, getFadeOut());
    }

    /**
     * Set the start of the track
     *
     * @param s track start offset, in seconds
     */
    public void setStart(float s) {
        if (start_s != s) {
            start_s = s;
            setModified(AUDIO | TRACK);
        }
    }

    /**
     * Has the track been marked as deleted?
     *
     * @return true if track has been deleted
     */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Set the deleted status of the track
     *
     * @param status true or false
     */
    public void setDeleted(boolean status) {
        if (deleted != status) {
            setModified(ALL);
        }
        deleted = status;
    }

    /**
     * Get the start of the track
     *
     * @return start of the track, in seconds offset
     */
    public float getStart() {
        return start_s;
    }

    /**
     * Set the end of the track
     *
     * @param e track end offset, in seconds
     */
    public void setEnd(float e) {
        if (end_s != e) {
            end_s = e;
            setModified(AUDIO | TRACK);
        }
    }

    /**
     * Get the end of the track
     *
     * @return end of the track, in seconds offset
     */
    public float getEnd() {
        return end_s;
    }

    /**
     * Set the fade in time
     *
     * @param d fade time, in seconds
     */
    public void setFadeIn(float d) {
        if (fadeIn_s != d) {
            fadeIn_s = d;
            setModified(AUDIO | TRACK);
        }
    }

    /**
     * Set the value of a metadata field
     *
     * @param field name of the field e.g. TALB, TPE1 etc
     * @param value value of the field
     */
    public void setField(String field, String value) {
        String curv = get(field);
        if (curv == null || !value.equals(curv)) {
            put(field, value);
            setModified(SCRIPT | TRACK);
        }
    }

    /**
     * Get the fade in
     *
     * @return fade in time, in seconds
     */
    public float getFadeIn() {
        if (fadeIn_s > end_s - start_s) {
            return end_s - start_s;
        }
        return fadeIn_s;
    }

    /**
     * Get the fade in
     *
     * @return fade in time, in seconds
     */
    public float getFadeOut() {
        if (fadeOut_s > end_s - start_s) {
            return end_s - start_s;
        }
        return fadeOut_s;
    }

    /**
     * Set the fade out time
     *
     * @param d fade time, in seconds
     */
    public void setFadeOut(float d) {
        if (fadeOut_s != d) {
            fadeOut_s = d;
            setModified(AUDIO | TRACK);
        }
    }

    private String getFieldForName(String field) {
        String s = get(field);
        if (s == null)
            s = "";
        // characters valid in filenames - sorta
        return s.replaceAll("[\\]\\[\\&`\"'^+*|$.\\\\/\\s(){}]+", "-");
    }
    
    /**
     * Get a meaningful name for the track. The name will be used for the
     * output filename.
     * @return a name
     */
    public String getName() {
        String title = getFieldForName("TIT2");
        String composer = getFieldForName("TCOM");
        String artist = getFieldForName("TPE1");
        String album = getFieldForName("TALB");
        String name;
        if (!composer.equals("") && !title.equals("")) {
            // title - composer is preferred
            name = composer + "_" + title;
        } else if (!artist.equals("") && !title.equals("")) {
            // title - artist is a good second bet
            name = title + "_" + artist;
        } else if (!album.equals("") && !title.equals("")) {
            name =  album + "_" + title;
        } else if (!title.equals("")) {
            // Getting desperate
            name = title;
        } else {
            name = defaultName;
        }
        name = name.replaceAll("-", "_");
        return name;
    }

    private String scriptify(String s) {
        s = s.replaceAll("\\\\", "\\\\");
        s = s.replaceAll("'", "'\"'\"'");
        return s;
    }

    /**
     * Write a script to convert .wav to .mp3
     *
     * @param f where to output the script
     * @throws IOException
     */
    public void writeScript(PrintWriter f) throws IOException {
        if (deleted) {
            return;
        }
        String name = getName();
        f.println("if [ ! -e \"" + name + ".wav\" ]; then");
        f.println("\techo '" + name + ".wav does not exist'");
        f.println("\t exit 0");
        f.println("fi");
        f.println("if [ -e \"" + name + ".mp3\" ]; then");
        f.println("\techo '" + name + ".mp3 already exists'");
        f.println("\texit 0");
        f.println("fi");
        f.println("lame \"" + name + ".wav\" \"" + name + ".mp3\" && rm \"" + name + ".wav\" \"" + name + ".lame\"");
        f.println("if [ -e \"" + name + ".mp3\" ]; then");
        f.println("\tid3v2 \\");
        Iterator<String> iter = keySet().iterator();
        while (iter.hasNext()) {
            String s = iter.next();
            String v = get(s);
            f.println("\t--" + s + " '" + scriptify(v) + "' \\");
        }
        f.println("\t\"" + name + ".mp3\"");
        f.println("fi");
        f.close();
        clearModified(SCRIPT);
    }

    /**
     * Retrieve audio for the track from a file, and write a new file containing
     * that audio.
     *
     * @param audio source to get audio data from
     * @param of file to write with audio data
     * @throws IOException
     */
    public void writeAudio(RandomAccessAudioFile audio, OutputStream of) throws IOException {
        if (deleted) {
            return;
        }
        clearModified(AUDIO);
        setModified(WRITING);
        AudioInputStream s = audio.getAudioInputStream(start_s, end_s - start_s);
        s = getFadeFilter(getLevelFilter(s));
        AudioSystem.write(s, AudioFileFormat.Type.WAVE, of);
        clearModified(WRITING);
    }

    /**
     * Construct and return a string representation of the track.
     * @param out where to write
     * @throws IOException
     */
    public void writeTrack(PrintWriter out) throws IOException {
        out.print(start_s);
        if (fadeIn_s > 0) {
            out.print(" (" + fadeIn_s + ")");
        }
        out.print(" .. ");
        if (fadeOut_s > 0) {
            out.print("(" + fadeOut_s + ") ");
        }
        out.println(end_s);
        Iterator<String> iter = keySet().iterator();
        while (iter.hasNext()) {
            String s = iter.next();
            String v = get(s);
            out.println("\t" + s + ": " + v);
        }
        clearModified(TRACK);
    }
}
