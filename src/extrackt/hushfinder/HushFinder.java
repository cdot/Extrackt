package extrackt.hushfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import java.io.IOException;
import java.io.File;
import java.io.PrintWriter;
import java.io.FileNotFoundException;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;

import extrackt.AudioRangeListener;
import extrackt.RandomAccessAudioFile;
import extrackt.PCMDataSource;
import extrackt.SampleSource;
import extrackt.SamplesFromPCMData;
import extrackt.SilenceWatcher;
import extrackt.Silences;

/**
 * Finds silences in a WAV file, and outputs a list of those silences to a file.
 *
 * @author crawford
 */
public class HushFinder {

    // Default thereshold
    private static final int DEFAULT_LEVEL = 1000; // one channel
    private static final float DEFAULT_DUR = 1f; // seconds

    private Silences silences;

    private static List<Silences.Threshold> thresholds;

    public static final Pattern OPTION_RE = Pattern.compile(
            "^--?(t(hreshold)?|h(elp)?|o(ut)?)$");

    public HushFinder() {
    }

    class SilenceListener implements AudioRangeListener {

        // Called for every silence
        @Override
        public boolean rangeEvent(float start, float end, Object data) {
            //System.out.println(start + ":" + end);
            int[] d = (int[]) data;
            silences.addSilence(start, end, d[0], d[1]);
            return true;
        }
    }

    private void analyse(String file, PrintWriter pw) {
        RandomAccessAudioFile audio;
        try {
            audio = new RandomAccessAudioFile(new File(file + ".wav"));
        } catch (UnsupportedAudioFileException | IOException ioe) {
            throw new Error("Problem reading " + file + ".wav: " + ioe.getMessage());
        }
        silences = new Silences(thresholds);
        AudioInputStream strm = audio.getAudioInputStream(0, audio.getLength());
        PCMDataSource pcm = new PCMDataSource(strm);
        SampleSource pcms = new SamplesFromPCMData(pcm);

        SilenceWatcher custodian = new SilenceWatcher(pcms, new SilenceListener(), thresholds);
        try {
            custodian.suckDry();
        } catch (IOException ioe) {
            throw new Error(ioe.getMessage());
        }

        try {
            silences.write(new PrintWriter(System.err));
            silences.write(pw);
        } catch (IOException ioe) {
            throw new Error(ioe.getMessage());
        }
    }

    private static final String usage = "Usage: java -cp Extrackt.jar extrackt.hushfinder.HushFinder <wavfile>\n"
            + "Options:\n"
            + "--out <file> - output silences to <file> (default is stdout)\n"
            + "--threshold <level> <duration> - add a threshold, level and minimum duration\n"
            + "--help - print this information\n"
            + "If no --threshold options are given, a default threshold of level "
            + DEFAULT_LEVEL + " and duration " + DEFAULT_DUR + "s will be used\n";

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println(usage);
            System.exit(1);
        }
        thresholds = new ArrayList<>();
        int argi = 0;
        String f = null;
        String of = null;
        while (argi < args.length) {
            //System.err.println("OPTION '"+args[argi]+"'");
            Matcher match = OPTION_RE.matcher(args[argi]);
            if (match.find()) {
                switch (match.group(1).charAt(0)) {
                    case 't':
                        // threshold
                        try {
                            int level = Integer.parseInt(args[++argi]);

                            float dur = Float.parseFloat(args[++argi]);
                            thresholds.add(new Silences.Threshold(level, dur));
                        } catch (Exception e) {
                            System.err.println("Bad threshold " + args[argi] + "\n" + usage);
                            System.exit(1);

                        }
                        break;
                    case 'o':
                        // Output file name (default output to STDOUT)
                        of = args[++argi];
                        break;
                    case 'h':
                        // help
                        System.out.println(usage);
                }
            } else if (args[argi].charAt(0) == '-') {
                System.err.println("Unrecognised option " + args[argi] + "\n" + usage);
                System.exit(1);
            } else {
                // filename to process
                f = args[argi];
                if (f.indexOf(".wav") == f.length() - 4) {
                    f = f.substring(0, f.length() - 4);
                } else if (f.indexOf(".") >= 0) {
                    throw new Error("Only .wav files accepted for input");
                }
            }
            argi++;
        }
        if (f == null) {
            System.err.println("No input file");
            System.err.println(usage);
            System.exit(1);
        }
        if (thresholds.isEmpty()) {
            System.err.println("Using default threshold");
            thresholds.add(new Silences.Threshold(DEFAULT_LEVEL, DEFAULT_DUR));
        }
        Iterator<Silences.Threshold> tit = thresholds.iterator();
        while (tit.hasNext()) {
            System.err.println(tit.next());
        }
        HushFinder hf = new HushFinder();
        PrintWriter pw;
        if (of == null) {
            pw = new PrintWriter(System.out);
        } else {
            try {
                File ff = new File(of);
                System.err.println("Generating output in " + ff);
                pw = new PrintWriter(ff);
            } catch (FileNotFoundException fnf) {
                throw new Error(fnf.getMessage());
            }
        }
        hf.analyse(f, pw);
        //pw.flush();
        pw.close();
        System.err.println("Silences generated");
    }
}
