package extrackt.ui;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Properties;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.AudioInputStream;

import javax.swing.event.DocumentListener;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;

import java.awt.Toolkit;

import extrackt.AudioRangeListener;
import extrackt.RandomAccessAudioFile;
import extrackt.Player;
import extrackt.Silence;
import extrackt.SilenceWatcher;
import extrackt.SampleWatcher;
import extrackt.SamplesFromPCMData;
import extrackt.PCMDataWatcher;
import extrackt.PCMDataSource;
import extrackt.Sink;
import extrackt.Track;
import extrackt.FFTWatcher;
import extrackt.SampleSource;
import extrackt.NormalisationFilter;

import java.awt.Color;

/**
 *
 * @author crawford
 */
public class MainFrame extends JFrame implements TrackListUI.ChangeListener {

    // Property names
    static final String TRACK_WINDOW_WIDTH = "TrackWindowWidth";
    static final String CLIP_LENGTH = "ClipLength";
    static final String DEFAULT_DIR = "DefaultDir";
    static final String MIN_SILENCE = "MinSilence";
    static final String SILENCE_THRESHOLD = "SilenceThreshold";
    static final String RECENT_FILE = "RecentFile";

    private String fileRoot; // File name root
    private String fileRootDir; // Directory root
    private String fileRootPath; // fileRootDir + fileRoot
    private TrackListUI trackList;
    private SilencesUI silences;

    private class SilenceFoundListener implements AudioRangeListener {

        // Implement AudioRangeListener
        @Override
        public boolean rangeEvent(float start, float end, Object data) {
            int[] d = (int[]) data;
            silences.addSilence(start, end, d[0], d[1]);
            return true;
        }
    };

    private RandomAccessAudioFile audio; // currently open file
    private final Player player;
    private final Sink sink;
    private WaveformDisplay waveformDisplay;
    private PowerDisplay powerDisplay;
    private final TrackListDisplay[] trackDisplays;
    private TrackListWindow trackListWindow;
    private TrackListDisplay trackListOverview;
    private FFTWatcher fftSource;
    private final ArrayList<TrackFieldListener> trackFieldListeners = new ArrayList<>();
    private LinkedList<Runnable> threadQueue = new LinkedList<Runnable>();
    Thread threadQueueRunner = new Thread() {
        @Override
        public void run() {
            while (true) {
                Runnable r = threadQueue.pollFirst();
                if (r != null) {
                    r.run();
                }
                try {
                    sleep(200);
                } catch (InterruptedException ie) {
                }
            }
        }
    };

    private final Properties properties;
    private final JLabel[] zoomLabels = new JLabel[5];
    private final Runnable exitDespiteDataLoss = new Runnable() {
        @Override
        public void run() {
            System.exit(1);
        }
    };
    private Runnable dataLossConfirmationAction = exitDespiteDataLoss;

    /**
     * Creates new form MainFrame
     */
    public MainFrame() {
        initComponents();
        setIconImage(Toolkit.getDefaultToolkit().createImage(ClassLoader.getSystemResource("extrackt/resources/rewind.png")));
        setTitle("Extrackt");
        trackDisplays = new TrackListDisplay[2];
        trackDisplays[0] = trackListOverview;
        trackDisplays[1] = trackListWindow;
        properties = new Properties();
        try {
            String home = System.getProperty("user.home");
            File propFile = new File(home, ".extracktor");
            properties.load(new FileInputStream(propFile));
        } catch (FileNotFoundException fnfe) {
        } catch (IOException ioe) {
        }
        int i = 1;
        String p;
        while ((p = properties.getProperty(RECENT_FILE + i)) != null) {
            javax.swing.JMenuItem openFile = new javax.swing.JMenuItem(p);
            openRecent.add(openFile);
            openFile.addActionListener(new java.awt.event.ActionListener() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent evt) {
                    final File f = new File(evt.getActionCommand());
                    confirmDataLoss(new Runnable() {
                        @Override
                        public void run() {
                            openFile(f);
                        }
                    });
                }
            });
            i++;
        }
        trackList = null;
        player = new Player();
        sink = new Sink();
        importProperty(TRACK_WINDOW_WIDTH, zoomWidthTextField.getDocument());
        importProperty(CLIP_LENGTH, clipLengthTextField.getDocument());
        threadQueueRunner.start();
    }

    private void importProperty(String name, Document doc) {
        String p = properties.getProperty(name);
        if (p != null) {
            try {
                doc.remove(0, doc.getLength());
                doc.insertString(0, p, null);
            } catch (BadLocationException screwyou) {
            }
        }
    }

    private class TextFieldListener implements DocumentListener {

        @Override
        public void insertUpdate(DocumentEvent e) {
            try {
                String text = e.getDocument().getText(0, e.getDocument().getLength());
                onUpdate(text);
            } catch (BadLocationException ble) {
            }
        }

        public void onUpdate(String newText) {
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            insertUpdate(e);
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            // Plain text components don't fire these events
        }
    }

    class PropertyListener extends TextFieldListener {

        String name;

        PropertyListener(String name) {
            this.name = name;
        }

        @Override
        public void onUpdate(String text) {
            String cur = properties.getProperty(name);
            if (!text.equals(cur)) {
                properties.setProperty(name, text);
                saveProperties();
            }
        }
    }

    /**
     * Listener for changes to track fields e.g. Composer, Track Name etc. Just
     * copies the change into the track record.
     */
    private class TrackFieldListener extends TextFieldListener {

        String field;
        boolean disabled;

        TrackFieldListener(String f) {
            field = f;
            disabled = false;
        }

        public void disabled(boolean dis) {
            disabled = dis;
        }

        @Override
        public void onUpdate(String text) {
            if (!disabled) {
                Track t = trackList.getTrack(trackList.getCurrentTrackNumber());
                if (t != null && !text.equals(t.get(field))) {
                    t.setField(field, text);
                    enableControls(true);
                }
            }
        }
    }

    /**
     * * Find the next silence. Scan samples starting at the current mark, and
     * stop when we encounter a silence (as defined by the thresholds currently
     * set). Set the mark to the start of the silence found. The list of
     * silences is *not* modified.
     *
     * @param stopWhenFound
     */
    private void scanForSilence() {

    }

    private void confirmDataLoss(Runnable action) {
        if (trackList == null) {
            action.run();
            return;
        }
        boolean bTracks = trackList.getTrackListModified();
        boolean bSilences = silences.getModified();
        boolean bAudio = trackList.getTracksModified(Track.AUDIO);
        boolean bScript = trackList.getTracksModified(Track.SCRIPT);
        if (bTracks || bSilences || bAudio || bScript) {
            String message = "";
            if (bTracks) {
                message += "tracks ";
            }
            if (bSilences) {
                message += "silences ";
            }
            if (bAudio) {
                message += "audio ";
            }
            if (bScript) {
                message += "lame ";
            }
            whatsChangedLabel.setText(message);
            dataLossConfirmationAction = action;
            dataLossConfirmDialog.pack();
            dataLossConfirmDialog.setVisible(true);
        } else {
            action.run();
        }
    }

    private void openDespiteDataLoss() {
        openFileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public String getDescription() {
                return "WAV file";
            }

            @Override
            public boolean accept(File f) {
                return (f.getName().endsWith(".wav") || f.isDirectory());
            }
        });
        String defDir = properties.getProperty(DEFAULT_DIR);
        if (defDir != null) {
            openFileChooser.setCurrentDirectory(new File(defDir));
        }
        if (openFileChooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = openFileChooser.getSelectedFile();
        if (f != null) {
            openFile(f);
            defDir = f.getParentFile().toString();
            properties.setProperty(DEFAULT_DIR, defDir);
            saveProperties();
        }
    }

    private void alert(String mess) {
        alertText.setText(mess);
        alertDialog.pack();
        alertDialog.setVisible(true);
    }

    private void openFile(File f) {
        fileRoot = f.getName();

        int dot = fileRoot.indexOf('.');
        if (dot >= 0) {
            fileRoot = fileRoot.substring(0, dot);
        }
        this.setTitle(fileRoot);

        fileRootDir = f.getParent() + '/';
        fileRootPath = fileRootDir + fileRoot;

        File file = new File(fileRootPath + ".wav");
        if (!file.exists()) {
            throw new Error(fileRootPath + ".wav not found");
        }
        try {
            audio = new RandomAccessAudioFile(file);
        } catch (UnsupportedAudioFileException | IOException e) {
            throw new Error(e.getMessage());
        }

        trackList = new TrackListUI();
        file = new File(fileRootPath + ".tracks");
        if (file.exists()) {
            try {
                FileReader fr = new FileReader(file);
                trackList.load(fr, fileRootPath);
            } catch (IOException ioe) {
                alert(ioe.getMessage());
            }
        } else {
            Track activeTrack = new Track(fileRootPath);
            activeTrack.setStart(0);
            activeTrack.setEnd(audio.getLength());
            trackList.add(activeTrack);
        }
        trackList.setTotalDuration(audio.getLength());
        trackList.addListener(this);

        silences = new SilencesUI();
        thresholdsList.setModel(silences);

        file = new File(fileRootPath + ".silences");
        if (file.exists()) {
            try {
                FileReader reader = new FileReader(file);
                silences.load(reader);
            } catch (IOException ioe) {
                alert(ioe.getMessage());
            }
        }

        trackListOverview.setTrackList(trackList);
        trackListOverview.addPainter(trackList);
        trackListOverview.addPainter(silences);

        trackListWindow.setTrackList(trackList);
        trackListWindow.addPainter(trackList);
        trackListWindow.addPainter(silences);

        waveformDisplay.setTrackList(trackList);
        waveformDisplay.addPainter(trackList);
        waveformDisplay.addPainter(silences);

        trackList.addListener(trackListOverview);
        trackList.addListener(trackListWindow);
        trackList.addListener(waveformDisplay);

        ArrayList<String> rfl;
        rfl = new ArrayList<>();
        int i;
        for (i = 9; i > 0; i--) {
            String p = properties.getProperty(RECENT_FILE + i, fileRootPath);
            if (p != null && !p.equals(fileRootPath)) {
                rfl.add(p);
            }
        }
        rfl.add(fileRootPath);
        Iterator<String> it = rfl.iterator();
        i = rfl.size();
        while (it.hasNext()) {
            properties.setProperty(RECENT_FILE + i--, it.next());
        }
        properties.setProperty(RECENT_FILE + "1", fileRootPath);
        saveProperties();
        trackListOverview.repaint();
        trackListWindow.repaint();
        trackListWindow.markChanged(0, 0);
        trackChanged(0, 0);

        trackList.clearTracksModified(Track.ALL);
        trackList.setTrackListModified(false);
        enableControls(true);
    }

    private void saveProperties() {
        try {
            String home = System.getProperty("user.home");
            File propFile = new File(home, ".extracktor");
            properties.store(new FileOutputStream(propFile),
                    "Properties for Extracktor track finder");
        } catch (FileNotFoundException fnfe) {
        } catch (IOException ioe) {
        }
    }

    private float getClipLength() {
        String t = clipLengthTextField.getText();
        return Float.parseFloat(t);
    }

    private void enableTrackControls(boolean state) {
        Track t = trackList.getCurrentTrack();
        if (t == null) {
            state = false;
        }
        deleteTrackButton.setEnabled(state);
        addTrackButton.setEnabled(state);
        if (t != null && t.isDeleted()) {
            state = false;
        }
        markStartOfTrackButton.setEnabled(state);
        markEndOfTrackButton.setEnabled(state);
        fadeInButton.setEnabled(state);
        fadeOutButton.setEnabled(state);
        titleTextField.setEnabled(state);
        composerTextField.setEnabled(state);
        albumTextField.setEnabled(state);
        artistTextField.setEnabled(state);
        commentTextField.setEnabled(state);
    }

    private void enableControls(boolean state) {
        interruptButton.setEnabled(!state);
        playBeforeButton.setEnabled(state);
        backButton.setEnabled(state);
        markTextField.setEnabled(state);
        forwardButton.setEnabled(state);
        playAfterButton.setEnabled(state);
        scanForSilence.setEnabled(state);
        jumpToNextSilence.setEnabled(state);
        waveformDisplay.setEnabled(state);
        powerDisplay.setEnabled(state);
        clipLengthTextField.setEnabled(state);
        lockLaterTracksCheckbox.setEnabled(state);
        trackListOverview.setEnabled(state);
        trackListWindow.setEnabled(state);
        zoomWidthTextField.setEnabled(state);
        trackStartButton.setEnabled(state);
        trackNumberSpinner.setEnabled(state);
        trackEndButton.setEnabled(state);
        fillInButton.setEnabled(state);
        writeButton.setEnabled(state);
        enableTrackControls(state);
    }

    private void play(AudioInputStream in, float start) {
        enableControls(false);

        final PCMDataWatcher source = new PCMDataWatcher(in);
        final Thread findSilences = new Thread() {
            @Override
            public void run() {
                SampleSource pcms = new SamplesFromPCMData(source);
                SilenceWatcher sw = new SilenceWatcher(
                        new SamplesFromPCMData(source),
                        new SilenceFoundListener(),
                        silences.getThresholds());
                try {
                    pcms.reset();
                    sw.suckDry();
                } catch (IOException ioe) {
                }
                waveformDisplay.repaint();
            }
        };

        source.mark();
        waveformDisplay.reset(start, in.getFormat().getSampleRate());
        source.addWatcher(waveformDisplay);
        waveformDisplay.decorate(false);
        try {
            player.play(source, new Runnable() {
                @Override
                public void run() {
                    waveformDisplay.decorate(true);
                    source.removeWatcher(waveformDisplay);
                    enableControls(true);
                    findSilences.start();
                }
            });
        } catch (LineUnavailableException e) {
            throw new Error(e.getMessage());
        }
    }

    private void addFieldListener(JTextField component, String field) {
        TrackFieldListener tfl = new TrackFieldListener(field);
        trackFieldListeners.add(tfl);
        component.getDocument().addDocumentListener(tfl);
    }

    public void trackChanged() {
        int ct = trackList.getCurrentTrackNumber();
        trackChanged(ct, ct);
    }

    @Override
    public void trackChanged(int old, int ct) {
        int curv = ((Integer) trackNumberSpinner.getValue()).intValue();
        if (ct != curv) {
            trackNumberSpinner.setValue(new Integer(ct));
        }
        Track t = trackList.getTrack(ct);
        if (t != null) {
            // How do we stop these updates triggering onUpdate?
            Iterator<TrackFieldListener> i = trackFieldListeners.iterator();
            while (i.hasNext()) {
                i.next().disabled(true);
            }
            String s = (String) t.get("TALB");
            if (s == null) {
                s = "";
            }
            albumTextField.setText(s);

            s = (String) t.get("TCOM");
            if (s == null) {
                s = "";
            }
            composerTextField.setText(s);

            s = (String) t.get("TPE1");
            if (s == null) {
                s = "";
            }
            artistTextField.setText(s);

            s = (String) t.get("TPE3");
            if (s == null) {
                s = "";
            }
            conductorTextField.setText(s);

            s = (String) t.get("TIT2");
            if (s == null) {
                s = "";
            }
            titleTextField.setText(s);

            s = (String) t.get("COMM");
            if (s == null) {
                s = "";
            }
            commentTextField.setText(s);

            int pv = t.getPeakLevel();
            if (pv == 0) {
                peakLevelLabel.setText("");
            } else {
                peakLevelLabel.setText("" + pv);
            }

            int tv = t.getTargetLevel();
            if (tv == 0) {
                tv = pv;
            }
            if (tv == 0) {
                targetLevelTextField.setText("");
            } else {
                targetLevelTextField.setText("" + tv);
            }

            i = trackFieldListeners.iterator();
            while (i.hasNext()) {
                i.next().disabled(false);
            }
        }
        enableControls(true);
        trackListOverview.repaint();
        trackListWindow.repaint();
    }

    @Override
    public void markChanged(float oldMark, float oldSpan) {
        String s = "" + trackList.getCurrentMark();
        if (!s.equals(markTextField.getText())) {
            markTextField.setText(s);
        }

        // TODO: make this configurable?
        float spectrumSampleLength = 10.0f / 1000; // 10ms
        FFT(trackList.getCurrentMark() - spectrumSampleLength, spectrumSampleLength);
    }

    // Perform an FFT on a selected region
    // start and span are in seconds
    private void FFT(float start, float span) {
        if (fftSource != null) {
            // We only run one FFTWatcher at a time, otherwise results of later
            // FFTs overwrite the pointless earlier ones
            fftSource.interrupt();
            fftSource = null;
        }
        if (span < 0) {
            start += span;
            span = -span;
        }
        AudioInputStream pcmd = audio.getAudioInputStream(start, span);
        SamplesFromPCMData pcms = new SamplesFromPCMData(new PCMDataSource(pcmd));
        final SampleWatcher sw = new SampleWatcher(pcms);
        powerDisplay.reset(pcms.getSampleRate());
        final int fftSamples = (int) (span * pcms.getSampleRate());
        final FFTWatcher fftw = new FFTWatcher(fftSamples, (int) pcms.getSampleRate());
        sw.addWatcher(fftw);
        new Thread() {
            @Override
            public void run() {
                try {
                    sw.suckDry();
                    // Wait for the FFTs to complete, then tell the powerDisplay
                    // This will compute the HPS
                    fftw.wait(powerDisplay);
                } catch (IOException ie) {
                }
            }
        }.start();
    }

    @Override
    public void spanChanged(float oldSpan) {
        if (trackList.getCurrentSpan() > 0) {
            FFT(trackList.getCurrentMark(), trackList.getCurrentSpan());
        } else {
            markChanged(trackList.getCurrentMark(), trackList.getCurrentSpan());
        }
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        Runnable thread = new Runnable() {
            MainFrame frame;

            @Override
            public void run() {
                frame = new MainFrame();
                frame.setVisible(true);
            }
        };
        java.awt.EventQueue.invokeLater(thread);
    }

    private void writeScript(final Track tr) {

        if (!tr.isDeleted()) {
            try {
                File f = new File(fileRootDir + tr.getName() + ".lame");
                System.out.println("Writing " + f);
                PrintWriter out = new PrintWriter(new FileWriter(f));
                tr.writeScript(out);
                tr.clearModified(Track.SCRIPT);
            } catch (IOException ioe) {
                System.out.println(ioe);
            }
        }
    }

    private void writeAudio(final Track tr) {
        if (!tr.isDeleted()) {
            tr.setModified(Track.WRITING);
            threadQueue.add(new Runnable() {
                @Override
                public void run() {
                    try {
                        File f = new File(fileRootDir + tr.getName() + ".wav");
                        System.out.println("Writing " + f);
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie) {
                        }
                        tr.writeAudio(audio, new FileOutputStream(f));
                        System.out.println("Wrote " + f);
                        tr.clearModified(Track.AUDIO | Track.WRITING);
                    } catch (IOException ioe) {
                        System.out.println(ioe);
                    }
                    repaint();
                }
            });
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        openFileChooser = new javax.swing.JFileChooser();
        dataLossConfirmDialog = new javax.swing.JDialog();
        javax.swing.JLabel closeConfirmLabel = new javax.swing.JLabel();
        whatsChangedLabel = new javax.swing.JLabel();
        javax.swing.JButton closeConfirmNoButton = new javax.swing.JButton();
        javax.swing.JButton closeConfirmYesButton = new javax.swing.JButton();
        alertDialog = new javax.swing.JDialog();
        alertText = new javax.swing.JLabel();
        javax.swing.JButton alertOKButton = new javax.swing.JButton();
        javax.swing.JLabel alertHeading = new javax.swing.JLabel();
        saveDialog = new javax.swing.JDialog();
        javax.swing.JLabel chooseWhatToSaveLabel = new javax.swing.JLabel();
        javax.swing.JSeparator allTracksSeparator = new javax.swing.JSeparator();
        javax.swing.JLabel allTracksLabel = new javax.swing.JLabel();
        saveTracksCheckBox = new javax.swing.JCheckBox();
        saveSilencesCheckBox = new javax.swing.JCheckBox();
        javax.swing.JSeparator eachTrackSeparator = new javax.swing.JSeparator();
        javax.swing.JLabel eachTrackLabel = new javax.swing.JLabel();
        allTracksRadioButton = new javax.swing.JRadioButton();
        modifiedTracksRadioButton = new javax.swing.JRadioButton();
        currentTrackRadioButton = new javax.swing.JRadioButton();
        saveScriptsCheckBox = new javax.swing.JCheckBox();
        saveAudioCheckBox = new javax.swing.JCheckBox();
        javax.swing.JButton saveOKButton = new javax.swing.JButton();
        javax.swing.JButton saveCancelButton = new javax.swing.JButton();
        javax.swing.ButtonGroup eachTrackButtonGroup = new javax.swing.ButtonGroup();
        fillInDialog = new javax.swing.JDialog();
        javax.swing.JLabel fillInGapsLabel = new javax.swing.JLabel();
        javax.swing.JLabel trackBaseNameLabel = new javax.swing.JLabel();
        trackBaseNameTextField = new javax.swing.JTextField();
        javax.swing.JButton fillInOKButton = new javax.swing.JButton();
        javax.swing.JButton fillInCancelButton = new javax.swing.JButton();
        silenceParamsDialog = new javax.swing.JDialog();
        setUpSilences = new javax.swing.JLabel();
        javax.swing.JLabel thresholdLabel = new javax.swing.JLabel();
        newThresholdPanel = new javax.swing.JLayeredPane();
        thresholdEditLabel = new javax.swing.JLabel();
        thresholdLevelLabel = new javax.swing.JLabel();
        thresholdLevelTextField = new javax.swing.JTextField();
        thresholdDurationLabel = new javax.swing.JLabel();
        thresholdDurationTextField = new javax.swing.JTextField();
        thresholdAddButton = new javax.swing.JButton();
        thresholdRemoveButton = new javax.swing.JButton();
        thresholdsScrollPane = new javax.swing.JScrollPane();
        thresholdsList = new javax.swing.JList();
        silencesDialogDone = new javax.swing.JButton();
        markToolBar = new javax.swing.JToolBar();
        javax.swing.JLabel markLabel = new javax.swing.JLabel();
        markTextField = new javax.swing.JTextField();
        javax.swing.JLabel clipLengthLabel = new javax.swing.JLabel();
        clipLengthTextField = new javax.swing.JTextField();
        backButton = new javax.swing.JButton();
        playBeforeButton = new javax.swing.JButton();
        interruptButton = new javax.swing.JButton();
        playAfterButton = new javax.swing.JButton();
        forwardButton = new javax.swing.JButton();
        javax.swing.JToolBar trackToolBar = new javax.swing.JToolBar();
        javax.swing.JLabel trackLabel = new javax.swing.JLabel();
        trackNumberSpinner = new javax.swing.JSpinner();
        trackStartButton = new javax.swing.JButton();
        trackEndButton = new javax.swing.JButton();
        writeButton = new javax.swing.JButton();
        fillInButton = new javax.swing.JButton();
        javax.swing.JToolBar trackModifyToolbar = new javax.swing.JToolBar();
        javax.swing.JLabel modifyTrackLabel = new javax.swing.JLabel();
        fadeInButton = new javax.swing.JButton();
        markStartOfTrackButton = new javax.swing.JButton();
        lockLaterTracksCheckbox = new javax.swing.JCheckBox();
        markEndOfTrackButton = new javax.swing.JButton();
        fadeOutButton = new javax.swing.JButton();
        deleteTrackButton = new javax.swing.JButton();
        addTrackButton = new javax.swing.JButton();
        javax.swing.JToolBar silenceToolbar = new javax.swing.JToolBar();
        javax.swing.JLabel silenceLabel = new javax.swing.JLabel();
        jumpToLastSilence = new javax.swing.JButton();
        jumpToNextSilence = new javax.swing.JButton();
        scanForSilence = new javax.swing.JButton();
        silenceParams = new javax.swing.JButton();
        javax.swing.JPanel trackPanel = new javax.swing.JPanel();
        zoomLabel = new javax.swing.JLabel();
        trackListWindowPanel = new javax.swing.JPanel();
        /* Recreate it as a private type, which is known to be
        * a JPanel */
        trackListWindow = new TrackListWindow();
        trackListWindowPanel = trackListWindow;
        zoomWidthTextField = new javax.swing.JTextField();
        javax.swing.JLabel secondsLabel = new javax.swing.JLabel();
        javax.swing.JPanel zoomLabelPanel = new javax.swing.JPanel();
        javax.swing.JLabel zoom0 = new javax.swing.JLabel();
        zoomLabels[0] = zoom0;
        javax.swing.JLabel zoom1 = new javax.swing.JLabel();
        zoomLabels[1] = zoom1;
        javax.swing.JLabel zoom2 = new javax.swing.JLabel();
        zoomLabels[2] = zoom2;
        javax.swing.JLabel zoom3 = new javax.swing.JLabel();
        zoomLabels[3] = zoom3;
        javax.swing.JLabel zoom4 = new javax.swing.JLabel();
        zoomLabels[4] = zoom4;
        javax.swing.JPanel trackListDisplayPanel = new javax.swing.JPanel();
        /* The UI designer insists that trackListPanel has
        * to be a JPanel. Recreate it as our own type */
        trackListOverview = new TrackListDisplay();
        trackListDisplayPanel = trackListOverview;
        javax.swing.JPanel trackInfoPanel = new javax.swing.JPanel();
        javax.swing.JLabel titleLabel = new javax.swing.JLabel();
        javax.swing.JLabel albumLabel = new javax.swing.JLabel();
        titleTextField = new javax.swing.JTextField();
        javax.swing.JLabel composerLabel = new javax.swing.JLabel();
        composerTextField = new javax.swing.JTextField();
        albumTextField = new javax.swing.JTextField();
        javax.swing.JLabel artistLabel = new javax.swing.JLabel();
        artistTextField = new javax.swing.JTextField();
        javax.swing.JLabel conductorLabel = new javax.swing.JLabel();
        conductorTextField = new javax.swing.JTextField();
        javax.swing.JLabel commentLabel = new javax.swing.JLabel();
        commentTextField = new javax.swing.JTextField();
        trackNameLabel = new javax.swing.JLabel();
        peakLevelLabel = new javax.swing.JLabel();
        computePeakLevelButton = new javax.swing.JButton();
        targetLevelTextField = new javax.swing.JTextField();
        javax.swing.JLabel boostLabel = new javax.swing.JLabel();
        waveformDisplayPanel = new javax.swing.JPanel();
        javax.swing.JPanel waveformDisplayCanvas = new javax.swing.JPanel();
        /* Recreate it as a private type */
        waveformDisplay = new WaveformDisplay();
        waveformDisplayCanvas = waveformDisplay;
        javax.swing.JLabel waveMaxLabel = new javax.swing.JLabel();
        javax.swing.JLabel waveStartLabel = new javax.swing.JLabel();
        javax.swing.JLabel waveEndLabel = new javax.swing.JLabel();
        javax.swing.JLabel waveMinLabel = new javax.swing.JLabel();
        powerDIsplayPanel = new javax.swing.JPanel();
        javax.swing.JPanel powerDisplayCanvas = new javax.swing.JPanel();
        /* Recreate it as a private type */
        powerDisplay = new PowerDisplay();
        powerDisplayCanvas = powerDisplay;
        javax.swing.JLabel powerMaxLabel = new javax.swing.JLabel();
        javax.swing.JLabel powerMinLabel = new javax.swing.JLabel();
        javax.swing.JLabel powerStartLabel = new javax.swing.JLabel();
        javax.swing.JLabel powerEndLabel = new javax.swing.JLabel();
        formantLabel = new javax.swing.JLabel();
        topMenu = new javax.swing.JMenuBar();
        fileMenu = new javax.swing.JMenu();
        open = new javax.swing.JMenuItem();
        openRecent = new javax.swing.JMenu();

        dataLossConfirmDialog.setTitle("Confirm data loss");
        dataLossConfirmDialog.setAlwaysOnTop(true);
        dataLossConfirmDialog.setLocationByPlatform(true);
        dataLossConfirmDialog.setModal(true);

        closeConfirmLabel.setForeground(new java.awt.Color(50, 50, 50));
        closeConfirmLabel.setText("You have unsaved changes. Are you sure you want to continue?");

        closeConfirmNoButton.setForeground(new java.awt.Color(100, 200, 100));
        closeConfirmNoButton.setText("No");
        closeConfirmNoButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                closeConfirmNoButtonActionPerformed(evt);
            }
        });

        closeConfirmYesButton.setForeground(new java.awt.Color(200, 100, 100));
        closeConfirmYesButton.setText("Yes, discard unsaved changes");
        closeConfirmYesButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                closeConfirmYesButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout dataLossConfirmDialogLayout = new javax.swing.GroupLayout(dataLossConfirmDialog.getContentPane());
        dataLossConfirmDialog.getContentPane().setLayout(dataLossConfirmDialogLayout);
        dataLossConfirmDialogLayout.setHorizontalGroup(
            dataLossConfirmDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(dataLossConfirmDialogLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(dataLossConfirmDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(dataLossConfirmDialogLayout.createSequentialGroup()
                        .addComponent(closeConfirmNoButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(closeConfirmYesButton))
                    .addComponent(closeConfirmLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(dataLossConfirmDialogLayout.createSequentialGroup()
                        .addGap(458, 458, 458)
                        .addComponent(whatsChangedLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                .addContainerGap())
        );
        dataLossConfirmDialogLayout.setVerticalGroup(
            dataLossConfirmDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(dataLossConfirmDialogLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(closeConfirmLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(whatsChangedLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 49, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(dataLossConfirmDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(closeConfirmYesButton)
                    .addComponent(closeConfirmNoButton))
                .addContainerGap())
        );

        alertDialog.setTitle("Alert");
        alertDialog.setAlwaysOnTop(true);
        alertDialog.setLocationByPlatform(true);
        alertDialog.setType(java.awt.Window.Type.POPUP);

        alertText.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
        alertText.setText("jLabel6");
        alertText.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        alertOKButton.setText("OK");
        alertOKButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                alertOKButtonActionPerformed(evt);
            }
        });

        alertHeading.setFont(new java.awt.Font("Dialog", 1, 14)); // NOI18N
        alertHeading.setText("Alert!");

        javax.swing.GroupLayout alertDialogLayout = new javax.swing.GroupLayout(alertDialog.getContentPane());
        alertDialog.getContentPane().setLayout(alertDialogLayout);
        alertDialogLayout.setHorizontalGroup(
            alertDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(alertDialogLayout.createSequentialGroup()
                .addGroup(alertDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(alertDialogLayout.createSequentialGroup()
                        .addGap(171, 171, 171)
                        .addComponent(alertOKButton))
                    .addGroup(alertDialogLayout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(alertText, javax.swing.GroupLayout.DEFAULT_SIZE, 376, Short.MAX_VALUE))
                    .addGroup(alertDialogLayout.createSequentialGroup()
                        .addGap(158, 158, 158)
                        .addComponent(alertHeading)))
                .addContainerGap())
        );
        alertDialogLayout.setVerticalGroup(
            alertDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(alertDialogLayout.createSequentialGroup()
                .addGap(26, 26, 26)
                .addComponent(alertHeading)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(alertText, javax.swing.GroupLayout.PREFERRED_SIZE, 125, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(alertOKButton)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        saveDialog.setTitle("Save");
        saveDialog.setAlwaysOnTop(true);
        saveDialog.setLocationByPlatform(true);
        saveDialog.setMinimumSize(new java.awt.Dimension(200, 220));
        saveDialog.setModal(true);

        chooseWhatToSaveLabel.setText("Choose what to save:");

        allTracksLabel.setText("All tracks");
        allTracksLabel.setToolTipText("Save these files for all tracks");

        saveTracksCheckBox.setText(".tracks");
        saveTracksCheckBox.setToolTipText("Track meta-data");

        saveSilencesCheckBox.setText(".silences");
        saveSilencesCheckBox.setToolTipText("List of detected silences");

        eachTrackSeparator.setName(""); // NOI18N

        eachTrackLabel.setText("Each track");
        eachTrackLabel.setToolTipText("Files for each track");

        eachTrackButtonGroup.add(allTracksRadioButton);
        allTracksRadioButton.setText("All tracks");
        allTracksRadioButton.setToolTipText("Save all tracks");

        eachTrackButtonGroup.add(modifiedTracksRadioButton);
        modifiedTracksRadioButton.setSelected(true);
        modifiedTracksRadioButton.setText("Modified tracks");
        modifiedTracksRadioButton.setToolTipText("Save all modified tracks");

        eachTrackButtonGroup.add(currentTrackRadioButton);
        currentTrackRadioButton.setText("Current track");
        currentTrackRadioButton.setToolTipText("Save the current track only");

        saveScriptsCheckBox.setText(".lame");
        saveScriptsCheckBox.setToolTipText(".wav to .mp3 comversion script");

        saveAudioCheckBox.setText(".wav");
        saveAudioCheckBox.setToolTipText("Audio file");

        saveOKButton.setText("Save");
        saveOKButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveOKButtonActionPerformed(evt);
            }
        });

        saveCancelButton.setText("Cancel");
        saveCancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveCancelButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout saveDialogLayout = new javax.swing.GroupLayout(saveDialog.getContentPane());
        saveDialog.getContentPane().setLayout(saveDialogLayout);
        saveDialogLayout.setHorizontalGroup(
            saveDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(saveDialogLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(saveDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(chooseWhatToSaveLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 393, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(allTracksLabel)
                    .addGroup(saveDialogLayout.createSequentialGroup()
                        .addGap(14, 14, 14)
                        .addComponent(saveTracksCheckBox)
                        .addGap(18, 18, 18)
                        .addComponent(saveSilencesCheckBox))
                    .addComponent(eachTrackLabel)
                    .addGroup(saveDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                        .addComponent(allTracksSeparator, javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(eachTrackSeparator, javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(javax.swing.GroupLayout.Alignment.LEADING, saveDialogLayout.createSequentialGroup()
                            .addComponent(saveOKButton)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(saveCancelButton))
                        .addGroup(javax.swing.GroupLayout.Alignment.LEADING, saveDialogLayout.createSequentialGroup()
                            .addGap(12, 12, 12)
                            .addGroup(saveDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                .addGroup(saveDialogLayout.createSequentialGroup()
                                    .addComponent(allTracksRadioButton)
                                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                    .addComponent(modifiedTracksRadioButton)
                                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                    .addComponent(currentTrackRadioButton))
                                .addGroup(saveDialogLayout.createSequentialGroup()
                                    .addComponent(saveScriptsCheckBox)
                                    .addGap(31, 31, 31)
                                    .addComponent(saveAudioCheckBox))))))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        saveDialogLayout.setVerticalGroup(
            saveDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, saveDialogLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(chooseWhatToSaveLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(allTracksSeparator, javax.swing.GroupLayout.PREFERRED_SIZE, 10, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(8, 8, 8)
                .addComponent(allTracksLabel)
                .addGap(12, 12, 12)
                .addGroup(saveDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(saveTracksCheckBox)
                    .addComponent(saveSilencesCheckBox))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(eachTrackSeparator, javax.swing.GroupLayout.PREFERRED_SIZE, 9, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(eachTrackLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(saveDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(allTracksRadioButton)
                    .addComponent(modifiedTracksRadioButton)
                    .addComponent(currentTrackRadioButton))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(saveDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(saveScriptsCheckBox)
                    .addComponent(saveAudioCheckBox))
                .addGap(18, 18, 18)
                .addGroup(saveDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(saveOKButton)
                    .addComponent(saveCancelButton))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        fillInDialog.setTitle("Fill In Gaps");
        fillInDialog.setAlwaysOnTop(true);

        fillInGapsLabel.setFont(new java.awt.Font("Dialog", 1, 14)); // NOI18N
        fillInGapsLabel.setText("Fill in gaps between tracks:");

        trackBaseNameLabel.setText("New track base name");

        trackBaseNameTextField.setToolTipText("Base name for generated tracks");

        fillInOKButton.setText("OK");
        fillInOKButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                fillInOKButtonActionPerformed(evt);
            }
        });

        fillInCancelButton.setText("Cancel");
        fillInCancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                fillInCancelButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout fillInDialogLayout = new javax.swing.GroupLayout(fillInDialog.getContentPane());
        fillInDialog.getContentPane().setLayout(fillInDialogLayout);
        fillInDialogLayout.setHorizontalGroup(
            fillInDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(fillInDialogLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(fillInDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(fillInDialogLayout.createSequentialGroup()
                        .addComponent(fillInOKButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(fillInCancelButton))
                    .addComponent(fillInGapsLabel))
                .addContainerGap())
            .addGroup(fillInDialogLayout.createSequentialGroup()
                .addGap(20, 20, 20)
                .addGroup(fillInDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(trackBaseNameTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 300, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(trackBaseNameLabel))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        fillInDialogLayout.setVerticalGroup(
            fillInDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(fillInDialogLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(fillInGapsLabel)
                .addGap(18, 18, 18)
                .addComponent(trackBaseNameLabel)
                .addGap(12, 12, 12)
                .addComponent(trackBaseNameTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(fillInDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(fillInCancelButton)
                    .addComponent(fillInOKButton))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        setUpSilences.setFont(new java.awt.Font("Dialog", 1, 14)); // NOI18N
        setUpSilences.setText("Set up silence scanner");

        thresholdLabel.setText("Thresholds");
        thresholdLabel.setFocusable(false);

        thresholdEditLabel.setText("Add/Remove Threshold");

        thresholdLevelLabel.setText("Level");

        thresholdLevelTextField.setText("1000");

        thresholdDurationLabel.setText("Duration");

        thresholdDurationTextField.setText("10.00");

        thresholdAddButton.setText("Add");
        thresholdAddButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                thresholdAddButtonActionPerformed(evt);
            }
        });

        thresholdRemoveButton.setText("Remove");
        thresholdRemoveButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                thresholdRemoveButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout newThresholdPanelLayout = new javax.swing.GroupLayout(newThresholdPanel);
        newThresholdPanel.setLayout(newThresholdPanelLayout);
        newThresholdPanelLayout.setHorizontalGroup(
            newThresholdPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(newThresholdPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(thresholdLevelLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(thresholdLevelTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(thresholdDurationLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(thresholdDurationTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(thresholdAddButton)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(thresholdRemoveButton)
                .addContainerGap())
            .addGroup(newThresholdPanelLayout.createSequentialGroup()
                .addComponent(thresholdEditLabel)
                .addGap(0, 0, Short.MAX_VALUE))
        );
        newThresholdPanelLayout.setVerticalGroup(
            newThresholdPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, newThresholdPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(thresholdEditLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(newThresholdPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(thresholdLevelLabel)
                    .addComponent(thresholdLevelTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(thresholdAddButton)
                    .addComponent(thresholdDurationLabel)
                    .addComponent(thresholdDurationTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(thresholdRemoveButton))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        newThresholdPanel.setLayer(thresholdEditLabel, javax.swing.JLayeredPane.DEFAULT_LAYER);
        newThresholdPanel.setLayer(thresholdLevelLabel, javax.swing.JLayeredPane.DEFAULT_LAYER);
        newThresholdPanel.setLayer(thresholdLevelTextField, javax.swing.JLayeredPane.DEFAULT_LAYER);
        newThresholdPanel.setLayer(thresholdDurationLabel, javax.swing.JLayeredPane.DEFAULT_LAYER);
        newThresholdPanel.setLayer(thresholdDurationTextField, javax.swing.JLayeredPane.DEFAULT_LAYER);
        newThresholdPanel.setLayer(thresholdAddButton, javax.swing.JLayeredPane.DEFAULT_LAYER);
        newThresholdPanel.setLayer(thresholdRemoveButton, javax.swing.JLayeredPane.DEFAULT_LAYER);

        thresholdsScrollPane.setViewportView(thresholdsList);

        silencesDialogDone.setActionCommand("silencesDialogDone");
        silencesDialogDone.setLabel("Done");
        silencesDialogDone.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                silencesDialogDoneActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout silenceParamsDialogLayout = new javax.swing.GroupLayout(silenceParamsDialog.getContentPane());
        silenceParamsDialog.getContentPane().setLayout(silenceParamsDialogLayout);
        silenceParamsDialogLayout.setHorizontalGroup(
            silenceParamsDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(silenceParamsDialogLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(silenceParamsDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(newThresholdPanel)
                    .addComponent(silencesDialogDone)
                    .addComponent(setUpSilences)
                    .addComponent(thresholdLabel)
                    .addComponent(thresholdsScrollPane))
                .addContainerGap(17, Short.MAX_VALUE))
        );
        silenceParamsDialogLayout.setVerticalGroup(
            silenceParamsDialogLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, silenceParamsDialogLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(setUpSilences)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(thresholdLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(thresholdsScrollPane, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(20, 20, 20)
                .addComponent(newThresholdPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(silencesDialogDone)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
        });

        markToolBar.setRollover(true);

        markTextField.setEnabled(false);
        markLabel.setFont(markLabel.getFont());
        markLabel.setLabelFor(markToolBar);
        markLabel.setText("Mark:");
        markLabel.setFocusable(false);
        markToolBar.add(markLabel);

        markTextField.setColumns(10);
        markTextField.setText("0");
        markTextField.setToolTipText("Current mark, in seconds");
        markTextField.setEnabled(false);
        markTextField.setMaximumSize(new java.awt.Dimension(100, 2147483647));
        markTextField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                markTextFieldActionPerformed(evt);
            }
        });
        markToolBar.add(markTextField);

        clipLengthLabel.setFont(clipLengthLabel.getFont());
        clipLengthLabel.setLabelFor(clipLengthTextField);
        clipLengthLabel.setText("Clip length");
        clipLengthLabel.setFocusable(false);
        markToolBar.add(clipLengthLabel);

        clipLengthTextField.setText("2.0");
        clipLengthTextField.setToolTipText("Set the clip length for playing, jogging and fading");
        clipLengthTextField.setEnabled(false);
        clipLengthTextField.setMaximumSize(new java.awt.Dimension(100, 2147483647));
        clipLengthTextField.getDocument().addDocumentListener(
            new PropertyListener(CLIP_LENGTH));
        markToolBar.add(clipLengthTextField);

        backButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/rewind.png"))); // NOI18N
        backButton.setToolTipText("Jog the mark back");
        backButton.setEnabled(false);
        backButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                backButtonActionPerformed(evt);
            }
        });
        markToolBar.add(backButton);

        playBeforeButton.setEnabled(false);
        playBeforeButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/playbefore.png"))); // NOI18N
        playBeforeButton.setToolTipText("Play a clip before the mark");
        playBeforeButton.setEnabled(false);
        playBeforeButton.setFocusTraversalPolicy(getFocusTraversalPolicy());
        playBeforeButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                playBeforeButtonActionPerformed(evt);
            }
        });
        markToolBar.add(playBeforeButton);

        interruptButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/stop.png"))); // NOI18N
        interruptButton.setToolTipText("Stop processing");
        interruptButton.setEnabled(false);
        interruptButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
        interruptButton.setMaximumSize(new java.awt.Dimension(24, 24));
        interruptButton.setMinimumSize(new java.awt.Dimension(24, 24));
        interruptButton.setPreferredSize(new java.awt.Dimension(24, 24));
        interruptButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        interruptButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                interruptButtonActionPerformed(evt);
            }
        });
        markToolBar.add(interruptButton);

        playAfterButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/playafter.png"))); // NOI18N
        playAfterButton.setToolTipText("Play a clip after the mark");
        playAfterButton.setEnabled(false);
        playAfterButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                playAfterButtonActionPerformed(evt);
            }
        });
        markToolBar.add(playAfterButton);

        forwardButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/ff.png"))); // NOI18N
        forwardButton.setToolTipText("Jog the mark forwards");
        forwardButton.setEnabled(false);
        forwardButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                forwardButtonActionPerformed(evt);
            }
        });
        markToolBar.add(forwardButton);

        trackLabel.setFont(trackLabel.getFont());
        trackLabel.setText("Track:");
        trackToolBar.add(trackLabel);

        trackNumberSpinner.setToolTipText("Current track number");
        trackNumberSpinner.setEnabled(false);
        trackNumberSpinner.setMaximumSize(new java.awt.Dimension(100, 32767));
        trackNumberSpinner.setValue(new Integer(1));
        trackNumberSpinner.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                trackNumberSpinnerStateChanged(evt);
            }
        });
        trackToolBar.add(trackNumberSpinner);

        trackStartButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/prev.png"))); // NOI18N
        trackStartButton.setToolTipText("Move the mark to the start of this track");
        trackStartButton.setEnabled(false);
        trackStartButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                trackStartButtonActionPerformed(evt);
            }
        });
        trackToolBar.add(trackStartButton);

        trackEndButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/next.png"))); // NOI18N
        trackEndButton.setToolTipText("Move the mark to the end of this track");
        trackEndButton.setEnabled(false);
        trackEndButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                trackEndButtonActionPerformed(evt);
            }
        });
        trackToolBar.add(trackEndButton);

        writeButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/saveall.png"))); // NOI18N
        writeButton.setToolTipText("Write");
        writeButton.setEnabled(false);
        writeButton.setFocusable(false);
        writeButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        writeButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        writeButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                writeButtonActionPerformed(evt);
            }
        });
        trackToolBar.add(writeButton);

        fillInButton.setText("Fill in");
        fillInButton.setToolTipText("Create tracks for spaces");
        fillInButton.setEnabled(false);
        fillInButton.setFocusable(false);
        fillInButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        fillInButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        fillInButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                fillInButtonActionPerformed(evt);
            }
        });
        trackToolBar.add(fillInButton);

        modifyTrackLabel.setFont(modifyTrackLabel.getFont());
        modifyTrackLabel.setLabelFor(trackModifyToolbar);
        modifyTrackLabel.setText("Modify track:");
        trackModifyToolbar.add(modifyTrackLabel);

        fadeInButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/fadein.png"))); // NOI18N
        fadeInButton.setToolTipText("Fade this track in");
        fadeInButton.setEnabled(false);
        fadeInButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                fadeInButtonActionPerformed(evt);
            }
        });
        trackModifyToolbar.add(fadeInButton);

        markStartOfTrackButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/trackstart.png"))); // NOI18N
        markStartOfTrackButton.setToolTipText("Set the start of this track to be the mark");
        markStartOfTrackButton.setEnabled(false);
        markStartOfTrackButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                markStartOfTrackButtonActionPerformed(evt);
            }
        });
        trackModifyToolbar.add(markStartOfTrackButton);

        lockLaterTracksCheckbox.setFont(lockLaterTracksCheckbox.getFont());
        lockLaterTracksCheckbox.setSelected(true);
        lockLaterTracksCheckbox.setText("Lock later");
        lockLaterTracksCheckbox.setToolTipText("Lock the relative position of the start and end of later tracks");
        lockLaterTracksCheckbox.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        lockLaterTracksCheckbox.setEnabled(false);
        lockLaterTracksCheckbox.setMargin(new java.awt.Insets(0, 0, 0, 0));
        trackModifyToolbar.add(lockLaterTracksCheckbox);

        markEndOfTrackButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/trackend.png"))); // NOI18N
        markEndOfTrackButton.setToolTipText("Set the end of this track to be the mark");
        markEndOfTrackButton.setEnabled(false);
        markEndOfTrackButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                markEndOfTrackButtonActionPerformed(evt);
            }
        });
        trackModifyToolbar.add(markEndOfTrackButton);

        fadeOutButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/fadeout.png"))); // NOI18N
        fadeOutButton.setToolTipText("Fade this track out");
        fadeOutButton.setEnabled(false);
        fadeOutButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                fadeOutButtonActionPerformed(evt);
            }
        });
        trackModifyToolbar.add(fadeOutButton);

        deleteTrackButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/delete.png"))); // NOI18N
        deleteTrackButton.setToolTipText("(Un)delete this track");
        deleteTrackButton.setEnabled(false);
        deleteTrackButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                deleteTrackButtonActionPerformed(evt);
            }
        });
        trackModifyToolbar.add(deleteTrackButton);

        addTrackButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/extrackt/resources/add.png"))); // NOI18N
        addTrackButton.setToolTipText("Add a new track");
        addTrackButton.setEnabled(false);
        addTrackButton.setFocusable(false);
        addTrackButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        addTrackButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        addTrackButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addTrackButtonActionPerformed(evt);
            }
        });
        trackModifyToolbar.add(addTrackButton);

        silenceLabel.setLabelFor(silenceToolbar);
        silenceLabel.setText("Silences:");
        silenceToolbar.add(silenceLabel);

        jumpToLastSilence.setText("Last");
        jumpToLastSilence.setToolTipText("Jump to the end of the last marked silence before the current position");
        jumpToLastSilence.setFocusable(false);
        jumpToLastSilence.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        jumpToLastSilence.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        jumpToLastSilence.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jumpToLastSilenceActionPerformed(evt);
            }
        });
        silenceToolbar.add(jumpToLastSilence);

        jumpToNextSilence.setText("Next");
        jumpToNextSilence.setToolTipText("Jump to the start of the next marked silence after the current position");
        jumpToNextSilence.setEnabled(false);
        jumpToNextSilence.setFocusable(false);
        jumpToNextSilence.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        jumpToNextSilence.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        jumpToNextSilence.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jumpToNextSilenceActionPerformed(evt);
            }
        });
        silenceToolbar.add(jumpToNextSilence);

        scanForSilence.setText("Scan for next");
        scanForSilence.setToolTipText("Scan for the next silence using the current silence parameters");
        scanForSilence.setEnabled(false);
        scanForSilence.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                scanForSilenceActionPerformed(evt);
            }
        });
        silenceToolbar.add(scanForSilence);

        silenceParams.setToolTipText("Open silence parameters dialog");
        silenceParams.setActionCommand("silencesDlg");
        silenceParams.setFocusable(false);
        silenceParams.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        silenceParams.setLabel("Parameters");
        silenceParams.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        silenceParams.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                silenceParamsActionPerformed(evt);
            }
        });
        silenceToolbar.add(silenceParams);

        zoomLabel.setLabelFor(zoomWidthTextField);
        zoomLabel.setText("Zoom");

        trackListWindowPanel.setBackground(new java.awt.Color(0, 0, 0));
        trackListWindowPanel.setToolTipText("Zoom window");
        trackListWindow.setLabels(zoomLabels);
        /* Can't use NetBeans to set this, because we
        * recreate the canvas */
        trackListWindow.setEnabled(false);
        trackListWindowPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                trackListWindowPanelMouseClicked(evt);
            }
        });
        trackListWindowPanel.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                trackListWindowPanelMouseMoved(evt);
            }
        });

        javax.swing.GroupLayout trackListWindowPanelLayout = new javax.swing.GroupLayout(trackListWindowPanel);
        trackListWindowPanel.setLayout(trackListWindowPanelLayout);
        trackListWindowPanelLayout.setHorizontalGroup(
            trackListWindowPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
        );
        trackListWindowPanelLayout.setVerticalGroup(
            trackListWindowPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 20, Short.MAX_VALUE)
        );

        zoomWidthTextField.setText("60");
        zoomWidthTextField.setToolTipText("Width of the zoom window");
        zoomWidthTextField.setEnabled(false);
        zoomWidthTextField.getDocument().addDocumentListener(
            new PropertyListener(TRACK_WINDOW_WIDTH) {
                public void onUpdate(String text) {
                    try {
                        float v = Float.parseFloat(text);
                        trackListWindow.setWindow(v);
                    } catch (NumberFormatException nfe) {
                    }
                    super.onUpdate(text);
                }
            });
            zoomWidthTextField.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent evt) {
                    zoomWidthTextFieldActionPerformed(evt);
                }
            });

            secondsLabel.setFont(secondsLabel.getFont());
            secondsLabel.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
            secondsLabel.setText("s");

            zoomLabelPanel.setLayout(new java.awt.GridLayout(1, 5));

            zoom0.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            zoom0.setText("0");
            zoomLabelPanel.add(zoom0);

            zoom1.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            zoom1.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
            zoom1.setText("0");
            zoomLabelPanel.add(zoom1);

            zoom2.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            zoom2.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            zoom2.setText("0");
            zoom2.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
            zoomLabelPanel.add(zoom2);

            zoom3.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            zoom3.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
            zoom3.setText("0");
            zoom3.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
            zoomLabelPanel.add(zoom3);

            zoom4.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            zoom4.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
            zoom4.setText("0");
            zoomLabelPanel.add(zoom4);

            javax.swing.GroupLayout trackPanelLayout = new javax.swing.GroupLayout(trackPanel);
            trackPanel.setLayout(trackPanelLayout);
            trackPanelLayout.setHorizontalGroup(
                trackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(trackPanelLayout.createSequentialGroup()
                    .addContainerGap()
                    .addGroup(trackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                        .addGroup(trackPanelLayout.createSequentialGroup()
                            .addComponent(zoomLabel)
                            .addGap(34, 34, 34))
                        .addGroup(trackPanelLayout.createSequentialGroup()
                            .addComponent(zoomWidthTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 44, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(secondsLabel)
                            .addGap(27, 27, 27)))
                    .addGroup(trackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(trackPanelLayout.createSequentialGroup()
                            .addComponent(zoomLabelPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addContainerGap())
                        .addComponent(trackListWindowPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
            );
            trackPanelLayout.setVerticalGroup(
                trackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(trackPanelLayout.createSequentialGroup()
                    .addContainerGap()
                    .addGroup(trackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                        .addGroup(trackPanelLayout.createSequentialGroup()
                            .addComponent(zoomLabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addGroup(trackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                .addComponent(zoomWidthTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addComponent(secondsLabel)))
                        .addGroup(trackPanelLayout.createSequentialGroup()
                            .addComponent(trackListWindowPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(zoomLabelPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            );

            trackListDisplayPanel.setBackground(new java.awt.Color(0, 0, 0));
            /* Can't use NetBeans to set this, because we
            * recreate it as our own private type */
            trackListOverview.setEnabled(false);
            trackListDisplayPanel.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseClicked(java.awt.event.MouseEvent evt) {
                    trackListDisplayPanelMouseClicked(evt);
                }
            });
            trackListDisplayPanel.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                public void mouseMoved(java.awt.event.MouseEvent evt) {
                    trackListDisplayPanelMouseMoved(evt);
                }
            });

            javax.swing.GroupLayout trackListDisplayPanelLayout = new javax.swing.GroupLayout(trackListDisplayPanel);
            trackListDisplayPanel.setLayout(trackListDisplayPanelLayout);
            trackListDisplayPanelLayout.setHorizontalGroup(
                trackListDisplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGap(0, 0, Short.MAX_VALUE)
            );
            trackListDisplayPanelLayout.setVerticalGroup(
                trackListDisplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGap(0, 20, Short.MAX_VALUE)
            );

            titleLabel.setFont(titleLabel.getFont());
            titleLabel.setLabelFor(titleTextField);
            titleLabel.setText("Title:");
            titleLabel.setFocusable(false);

            albumLabel.setFont(albumLabel.getFont());
            albumLabel.setLabelFor(albumTextField);
            albumLabel.setText("Album:");
            albumLabel.setFocusable(false);

            titleTextField.setToolTipText("Name of the track (TIT2)");
            titleTextField.setEnabled(false);
            addFieldListener(titleTextField, "TIT2");

            composerLabel.setFont(composerLabel.getFont());
            composerLabel.setLabelFor(composerTextField);
            composerLabel.setText("Composer:");
            composerLabel.setFocusable(false);

            composerTextField.setToolTipText("Composer of the track (TCOM)");
            composerTextField.setEnabled(false);
            addFieldListener(composerTextField, "TCOM");

            albumTextField.setToolTipText("Name of the album (TALB)");
            albumTextField.setEnabled(false);
            addFieldListener(albumTextField, "TALB");

            artistLabel.setFont(artistLabel.getFont());
            artistLabel.setLabelFor(artistTextField);
            artistLabel.setText("Artist:");
            artistLabel.setFocusable(false);

            artistTextField.setToolTipText("Artist (TPE1)");
            artistTextField.setEnabled(false);
            addFieldListener(artistTextField, "TPE1");

            conductorLabel.setFont(conductorLabel.getFont());
            conductorLabel.setLabelFor(commentTextField);
            conductorLabel.setText("Conductor:");
            conductorLabel.setFocusable(false);

            conductorTextField.setToolTipText("Set the conductor (TPE3)");
            conductorTextField.setEnabled(false);
            addFieldListener(commentTextField, "COMM");

            commentLabel.setFont(commentLabel.getFont());
            commentLabel.setLabelFor(commentTextField);
            commentLabel.setText("Comment:");
            commentLabel.setFocusable(false);

            commentTextField.setToolTipText("Comments (COMM)");
            commentTextField.setEnabled(false);
            addFieldListener(commentTextField, "COMM");

            peakLevelLabel.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            peakLevelLabel.setText("000000");

            computePeakLevelButton.setText("Peak Level");
            computePeakLevelButton.setToolTipText("Compute the peak level of the current track");
            computePeakLevelButton.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent evt) {
                    computePeakLevelButtonActionPerformed(evt);
                }
            });

            targetLevelTextField.setText("00000");
            targetLevelTextField.setToolTipText("Shows the computed peak level, and used to enter the peak level for normalisation");
            targetLevelTextField.setMaximumSize(new java.awt.Dimension(50, 19));
            targetLevelTextField.setMinimumSize(new java.awt.Dimension(50, 19));
            targetLevelTextField.setPreferredSize(new java.awt.Dimension(50, 19));
            targetLevelTextField.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent evt) {
                    targetLevelTextFieldActionPerformed(evt);
                }
            });

            boostLabel.setText("Boost to");

            javax.swing.GroupLayout trackInfoPanelLayout = new javax.swing.GroupLayout(trackInfoPanel);
            trackInfoPanel.setLayout(trackInfoPanelLayout);
            trackInfoPanelLayout.setHorizontalGroup(
                trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(trackInfoPanelLayout.createSequentialGroup()
                    .addContainerGap()
                    .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(trackInfoPanelLayout.createSequentialGroup()
                            .addComponent(computePeakLevelButton)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(peakLevelLabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(boostLabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(targetLevelTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 51, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGap(408, 408, 408)
                            .addComponent(trackNameLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 519, Short.MAX_VALUE))
                        .addGroup(trackInfoPanelLayout.createSequentialGroup()
                            .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                .addComponent(conductorLabel)
                                .addComponent(titleLabel)
                                .addComponent(composerLabel)
                                .addComponent(albumLabel)
                                .addComponent(artistLabel)
                                .addComponent(commentLabel))
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                .addComponent(commentTextField)
                                .addComponent(conductorTextField)
                                .addComponent(artistTextField)
                                .addComponent(albumTextField)
                                .addComponent(composerTextField)
                                .addComponent(titleTextField))))
                    .addContainerGap())
            );
            trackInfoPanelLayout.setVerticalGroup(
                trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(trackInfoPanelLayout.createSequentialGroup()
                    .addContainerGap()
                    .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(titleLabel)
                        .addComponent(titleTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(composerLabel)
                        .addComponent(composerTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(albumLabel)
                        .addComponent(albumTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(artistLabel)
                        .addComponent(artistTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(conductorLabel)
                        .addComponent(conductorTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(commentTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(commentLabel))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                        .addComponent(trackNameLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 20, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGroup(trackInfoPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(peakLevelLabel)
                            .addComponent(computePeakLevelButton)
                            .addComponent(targetLevelTextField, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(boostLabel)))
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            );

            waveformDisplayCanvas.setBackground(new java.awt.Color(0, 0, 0));
            waveformDisplayCanvas.setToolTipText("Waveform window");
            /* Attach labels for min, max */
            waveformDisplay.setMinMaxLabels(waveStartLabel, waveEndLabel, waveMinLabel, waveMaxLabel);
            waveformDisplayCanvas.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mousePressed(java.awt.event.MouseEvent evt) {
                    waveformDisplayCanvasMousePressed(evt);
                }
            });
            waveformDisplayCanvas.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                public void mouseMoved(java.awt.event.MouseEvent evt) {
                    waveformDisplayCanvasMouseMoved(evt);
                }
                public void mouseDragged(java.awt.event.MouseEvent evt) {
                    waveformDisplayCanvasMouseDragged(evt);
                }
            });

            javax.swing.GroupLayout waveformDisplayCanvasLayout = new javax.swing.GroupLayout(waveformDisplayCanvas);
            waveformDisplayCanvas.setLayout(waveformDisplayCanvasLayout);
            waveformDisplayCanvasLayout.setHorizontalGroup(
                waveformDisplayCanvasLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGap(0, 0, Short.MAX_VALUE)
            );
            waveformDisplayCanvasLayout.setVerticalGroup(
                waveformDisplayCanvasLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGap(0, 134, Short.MAX_VALUE)
            );

            waveMaxLabel.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            waveMaxLabel.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
            waveMaxLabel.setText("000000");
            waveMaxLabel.setToolTipText("Max amplitude");
            waveMaxLabel.setMaximumSize(new java.awt.Dimension(50, 15));
            waveMaxLabel.setMinimumSize(new java.awt.Dimension(50, 15));
            waveMaxLabel.setPreferredSize(new java.awt.Dimension(50, 15));

            waveStartLabel.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            waveStartLabel.setText("0000.00");
            waveStartLabel.setToolTipText("Start of clip (s)");

            waveEndLabel.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            waveEndLabel.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
            waveEndLabel.setText("0000.00");
            waveEndLabel.setToolTipText("End of clip(s)");

            waveMinLabel.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            waveMinLabel.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
            waveMinLabel.setText("000000");
            waveMinLabel.setToolTipText("Min amplitude");
            waveMinLabel.setMaximumSize(new java.awt.Dimension(50, 15));
            waveMinLabel.setMinimumSize(new java.awt.Dimension(50, 15));
            waveMinLabel.setPreferredSize(new java.awt.Dimension(50, 15));

            javax.swing.GroupLayout waveformDisplayPanelLayout = new javax.swing.GroupLayout(waveformDisplayPanel);
            waveformDisplayPanel.setLayout(waveformDisplayPanelLayout);
            waveformDisplayPanelLayout.setHorizontalGroup(
                waveformDisplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, waveformDisplayPanelLayout.createSequentialGroup()
                    .addGap(74, 74, 74)
                    .addComponent(waveStartLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 515, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(waveEndLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addContainerGap())
                .addGroup(waveformDisplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(waveformDisplayPanelLayout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(waveformDisplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(waveMaxLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(waveMinLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(waveformDisplayCanvas, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addContainerGap()))
            );
            waveformDisplayPanelLayout.setVerticalGroup(
                waveformDisplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(waveformDisplayPanelLayout.createSequentialGroup()
                    .addContainerGap()
                    .addGroup(waveformDisplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(waveEndLabel)
                        .addComponent(waveStartLabel))
                    .addContainerGap(152, Short.MAX_VALUE))
                .addGroup(waveformDisplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(waveformDisplayPanelLayout.createSequentialGroup()
                        .addGap(33, 33, 33)
                        .addGroup(waveformDisplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(waveformDisplayCanvas, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGroup(waveformDisplayPanelLayout.createSequentialGroup()
                                .addComponent(waveMaxLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(waveMinLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
            );

            powerDisplayCanvas.setBackground(new java.awt.Color(0, 0, 0));
            powerDisplayCanvas.setToolTipText("Frequency spectrum");
            /* Attach labels */
            powerDisplay.setLabels(powerStartLabel, powerEndLabel, powerMinLabel, powerMaxLabel, formantLabel);
            powerDisplayCanvas.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                public void mouseMoved(java.awt.event.MouseEvent evt) {
                    powerDisplayCanvasMouseMoved(evt);
                }
            });

            javax.swing.GroupLayout powerDisplayCanvasLayout = new javax.swing.GroupLayout(powerDisplayCanvas);
            powerDisplayCanvas.setLayout(powerDisplayCanvasLayout);
            powerDisplayCanvasLayout.setHorizontalGroup(
                powerDisplayCanvasLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGap(0, 0, Short.MAX_VALUE)
            );
            powerDisplayCanvasLayout.setVerticalGroup(
                powerDisplayCanvasLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGap(0, 140, Short.MAX_VALUE)
            );

            powerMaxLabel.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            powerMaxLabel.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
            powerMaxLabel.setText("32767");

            powerMinLabel.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            powerMinLabel.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
            powerMinLabel.setText("0");

            powerStartLabel.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            powerStartLabel.setText("0000.00");

            powerEndLabel.setFont(new java.awt.Font("Dialog", 0, 12)); // NOI18N
            powerEndLabel.setHorizontalAlignment(javax.swing.SwingConstants.TRAILING);
            powerEndLabel.setText("0000.00");

            formantLabel.setText("Formant");

            javax.swing.GroupLayout powerDIsplayPanelLayout = new javax.swing.GroupLayout(powerDIsplayPanel);
            powerDIsplayPanel.setLayout(powerDIsplayPanelLayout);
            powerDIsplayPanelLayout.setHorizontalGroup(
                powerDIsplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(powerDIsplayPanelLayout.createSequentialGroup()
                    .addGap(11, 11, 11)
                    .addGroup(powerDIsplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                        .addComponent(powerMaxLabel)
                        .addComponent(powerMinLabel))
                    .addGap(12, 12, 12)
                    .addGroup(powerDIsplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(powerDisplayCanvas, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGroup(powerDIsplayPanelLayout.createSequentialGroup()
                            .addComponent(powerStartLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 430, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(formantLabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(powerEndLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 555, Short.MAX_VALUE))))
            );
            powerDIsplayPanelLayout.setVerticalGroup(
                powerDIsplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(powerDIsplayPanelLayout.createSequentialGroup()
                    .addGroup(powerDIsplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                        .addGroup(powerDIsplayPanelLayout.createSequentialGroup()
                            .addGap(29, 29, 29)
                            .addComponent(powerMaxLabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(powerMinLabel))
                        .addGroup(powerDIsplayPanelLayout.createSequentialGroup()
                            .addContainerGap(18, Short.MAX_VALUE)
                            .addGroup(powerDIsplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                .addComponent(powerEndLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addGroup(powerDIsplayPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                    .addComponent(powerStartLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                    .addComponent(formantLabel)))
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(powerDisplayCanvas, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            );

            fileMenu.setText("File");

            open.setText("Open...");
            open.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent evt) {
                    openActionPerformed(evt);
                }
            });
            fileMenu.add(open);

            openRecent.setText("Open Recent...");
            openRecent.setDoubleBuffered(true);
            fileMenu.add(openRecent);

            topMenu.add(fileMenu);

            setJMenuBar(topMenu);

            javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
            getContentPane().setLayout(layout);
            layout.setHorizontalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(layout.createSequentialGroup()
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addGroup(layout.createSequentialGroup()
                            .addComponent(markToolBar, javax.swing.GroupLayout.PREFERRED_SIZE, 457, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGap(134, 134, 134)
                            .addComponent(trackToolBar, javax.swing.GroupLayout.PREFERRED_SIZE, 256, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                            .addComponent(waveformDisplayPanel, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createSequentialGroup()
                                .addContainerGap()
                                .addComponent(trackListDisplayPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                            .addComponent(trackPanel, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(trackInfoPanel, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createSequentialGroup()
                                .addComponent(trackModifyToolbar, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(8, 8, 8)
                                .addComponent(silenceToolbar, javax.swing.GroupLayout.PREFERRED_SIZE, 539, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addComponent(powerDIsplayPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
            );
            layout.setVerticalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(layout.createSequentialGroup()
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                        .addComponent(markToolBar, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(trackToolBar, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(trackListDisplayPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(trackPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(trackModifyToolbar, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(silenceToolbar, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(trackInfoPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(waveformDisplayPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(powerDIsplayPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
            );

            pack();
        }// </editor-fold>//GEN-END:initComponents

    private void deleteTrackButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_deleteTrackButtonActionPerformed
        Track t = trackList.getTrack(trackList.getCurrentTrackNumber());
        t.setDeleted(!t.isDeleted());
        enableControls(true);
        trackList.setCurrentTrack(trackList.getCurrentTrackNumber());
    }//GEN-LAST:event_deleteTrackButtonActionPerformed

    private void trackListDisplayPanelMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_trackListDisplayPanelMouseMoved
        trackListOverview.onMouseMoved(evt);
}//GEN-LAST:event_trackListDisplayPanelMouseMoved

    private void trackListWindowPanelMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_trackListWindowPanelMouseMoved
        trackListWindow.onMouseMoved(evt);
}//GEN-LAST:event_trackListWindowPanelMouseMoved

    private void zoomWidthTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_zoomWidthTextFieldActionPerformed
// TODO add your handling code here:
    }//GEN-LAST:event_zoomWidthTextFieldActionPerformed

    private void trackListDisplayPanelMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_trackListDisplayPanelMouseClicked
        trackListOverview.onMouseEvent(evt);
}//GEN-LAST:event_trackListDisplayPanelMouseClicked

    private void trackListWindowPanelMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_trackListWindowPanelMouseClicked
        trackListWindow.onMouseEvent(evt);
}//GEN-LAST:event_trackListWindowPanelMouseClicked

    private void alertOKButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_alertOKButtonActionPerformed
        alertDialog.setVisible(false);
    }//GEN-LAST:event_alertOKButtonActionPerformed

    private void closeConfirmNoButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_closeConfirmNoButtonActionPerformed
        dataLossConfirmDialog.setVisible(false);
    }//GEN-LAST:event_closeConfirmNoButtonActionPerformed

    private void closeConfirmYesButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_closeConfirmYesButtonActionPerformed
        dataLossConfirmDialog.setVisible(false);
        dataLossConfirmationAction.run();
    }//GEN-LAST:event_closeConfirmYesButtonActionPerformed

    private void formWindowClosing(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosing
        confirmDataLoss(exitDespiteDataLoss);
    }//GEN-LAST:event_formWindowClosing

    private void interruptButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_interruptButtonActionPerformed
        player.stopPlaying();
        sink.stopPlaying();
        enableControls(true);
    }//GEN-LAST:event_interruptButtonActionPerformed

    private void trackEndButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_trackEndButtonActionPerformed
        Track t = trackList.getTrack(trackList.getCurrentTrackNumber());
        if (t != null) {
            trackList.setCurrentMark(t.getEnd());
            float dur = getClipLength();
            AudioInputStream s = audio.getAudioInputStream(trackList.getCurrentMark() - dur, dur);
            s = t.getLevelFilter(t.getEndFadeFilter(s));
            play(s, trackList.getCurrentMark() - dur);
        }
    }//GEN-LAST:event_trackEndButtonActionPerformed

    private void trackStartButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_trackStartButtonActionPerformed
        Track t = trackList.getTrack(trackList.getCurrentTrackNumber());
        if (t != null) {
            trackList.setCurrentMark(t.getStart());
            float dur = getClipLength();
            AudioInputStream s = audio.getAudioInputStream(trackList.getCurrentMark(), dur);
            s = t.getLevelFilter(t.getStartFadeFilter(s));
            play(s, trackList.getCurrentMark());
        }
    }//GEN-LAST:event_trackStartButtonActionPerformed

    private void trackNumberSpinnerStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_trackNumberSpinnerStateChanged
        int curv = ((Integer) trackNumberSpinner.getValue()).intValue();
        trackList.setCurrentTrack(curv);
        Track tr = trackList.getCurrentTrack();
        if (tr != null) {
            trackNameLabel.setText(tr.getName());
        }
    }//GEN-LAST:event_trackNumberSpinnerStateChanged

    private void openActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openActionPerformed
        confirmDataLoss(new Runnable() {
            public void run() {
                openDespiteDataLoss();
            }
        });
    }//GEN-LAST:event_openActionPerformed

    private void markEndOfTrackButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_markEndOfTrackButtonActionPerformed
        trackList.setCurrentEnd(lockLaterTracksCheckbox.isSelected());
        enableControls(true);
    }//GEN-LAST:event_markEndOfTrackButtonActionPerformed

    private void markStartOfTrackButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_markStartOfTrackButtonActionPerformed
        trackList.setCurrentStart(lockLaterTracksCheckbox.isSelected());
        enableControls(true);
    }//GEN-LAST:event_markStartOfTrackButtonActionPerformed

    private void fadeOutButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_fadeOutButtonActionPerformed
        Track track = trackList.getTrack(trackList.getCurrentTrackNumber());
        if (track != null) {
            track.setFadeOut(getClipLength());
            trackEndButtonActionPerformed(evt);
        }
        enableControls(true);
    }//GEN-LAST:event_fadeOutButtonActionPerformed

    private void fadeInButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_fadeInButtonActionPerformed
        Track track = trackList.getTrack(trackList.getCurrentTrackNumber());
        if (track != null) {
            track.setFadeIn(getClipLength());
            trackStartButtonActionPerformed(evt);
        }
        enableControls(true);
    }//GEN-LAST:event_fadeInButtonActionPerformed

    private void scanForSilenceActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_scanForSilenceActionPerformed
        float cur_mark = trackList.getCurrentMark();
        float dur = audio.getLength() - cur_mark;
        AudioInputStream pcmd = audio.getAudioInputStream(cur_mark, dur);
        SamplesFromPCMData pcms = new SamplesFromPCMData(new PCMDataSource(pcmd));
        SilenceWatcher ear = new SilenceWatcher(
                pcms, new SilenceListener(trackList), silences.getThresholds());
        waveformDisplay.reset(cur_mark, ear.getSampleRate());
        final SampleWatcher s = new SampleWatcher(ear);
        s.addWatcher(waveformDisplay);
        enableControls(false);
        new Thread() {
            @Override
            public void run() {
                try {
                    s.suckDry();
                } catch (IOException ioe) {
                }
                enableControls(true);
            }
        }.start();
    }//GEN-LAST:event_scanForSilenceActionPerformed

    private void playAfterButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_playAfterButtonActionPerformed
        float dur = getClipLength();
        AudioInputStream s = audio.getAudioInputStream(trackList.getCurrentMark(), dur);
        Track active = trackList.getTrackAtTime(trackList.getCurrentMark());
        if (active != null) {
            s = active.getLevelFilter(s);
        }
        play(s, trackList.getCurrentMark());
    }//GEN-LAST:event_playAfterButtonActionPerformed

    private void forwardButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_forwardButtonActionPerformed
        trackList.setCurrentMark(trackList.getCurrentMark() + getClipLength());
    }//GEN-LAST:event_forwardButtonActionPerformed

    private void backButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_backButtonActionPerformed
        trackList.setCurrentMark(trackList.getCurrentMark() - getClipLength());
    }//GEN-LAST:event_backButtonActionPerformed

    private void playBeforeButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_playBeforeButtonActionPerformed
        float dur = getClipLength();
        // find what track the mark starts within and apply level filter
        AudioInputStream s = audio.getAudioInputStream(trackList.getCurrentMark() - dur, dur);
        Track active = trackList.getTrackAtTime(trackList.getCurrentMark());
        if (active != null) {
            s = active.getLevelFilter(s);
        }
        play(s, trackList.getCurrentMark() - dur);
    }//GEN-LAST:event_playBeforeButtonActionPerformed
    
    private void jumpToNextSilenceActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jumpToNextSilenceActionPerformed
        float cur_mark = trackList.getCurrentMark();
        float best = -1;
        Iterator<Silence> si = silences.iterator();
        while (si.hasNext()) {
            Silence s = si.next();
            if (s.getStart() > cur_mark) {
                if (best < 0 || s.getStart() < best)
                    best = s.getStart();
            }
        }
        if (best >= 0) {
            trackList.setCurrentMark(best);
            playAfterButtonActionPerformed(null);
        }
}//GEN-LAST:event_jumpToNextSilenceActionPerformed

    private void addTrackButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addTrackButtonActionPerformed
        trackList.insertTrack();
        enableControls(true);
    }//GEN-LAST:event_addTrackButtonActionPerformed

    private void powerDisplayCanvasMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_powerDisplayCanvasMouseMoved
        powerDisplay.onMouseMoved(evt);
    }//GEN-LAST:event_powerDisplayCanvasMouseMoved

    private void writeButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_writeButtonActionPerformed
        saveTracksCheckBox.setSelected(trackList.getTrackListModified() || trackList.getTracksModified(Track.TRACK));
        saveSilencesCheckBox.setSelected(silences.getModified());
        saveAudioCheckBox.setSelected(trackList.getTracksModified(Track.AUDIO));
        saveScriptsCheckBox.setSelected(trackList.getTracksModified(Track.SCRIPT));
        saveDialog.pack();
        saveDialog.setVisible(true);
    }//GEN-LAST:event_writeButtonActionPerformed

    private void saveOKButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveOKButtonActionPerformed
        enableControls(false);
        PrintWriter out;
        if (saveTracksCheckBox.isSelected()) {
            try {
                out = new PrintWriter(new FileWriter(new File(fileRootPath + ".tracks")));
                trackList.writeTracks(out);
                trackList.setTrackListModified(false);
            } catch (IOException ioe) {
                alert(ioe.getMessage());
            }
        }
        if (saveSilencesCheckBox.isSelected()) {
            try {
                out = new PrintWriter(
                        new FileWriter(new File(fileRootPath + ".silences")));
                silences.write(out);
            } catch (IOException ioe) {
                alert(ioe.getMessage());
            }
        }
        if (saveScriptsCheckBox.isSelected()) {
            if (currentTrackRadioButton.isSelected()) {
                writeScript(trackList.getCurrentTrack());
            } else {
                for (int i = 1; i <= trackList.size(); i++) {
                    Track tr = trackList.getTrack(i);
                    if (tr.getModified() != 0 || allTracksRadioButton.isSelected()) {
                        writeScript(tr);
                    }
                }
            }
        }

        if (saveAudioCheckBox.isSelected()) {
            if (currentTrackRadioButton.isSelected()) {
                writeAudio(trackList.getCurrentTrack());
            } else {
                for (int i = 1; i <= trackList.size(); i++) {
                    Track tr = trackList.getTrack(i);
                    if (tr.getModified() != 0 || allTracksRadioButton.isSelected()) {
                        writeAudio(tr);
                    }
                }
            }
        }
        threadQueue.add(new Runnable() {
            public void run() {
                enableControls(true);
            }
        });
        saveDialog.setVisible(false);
    }//GEN-LAST:event_saveOKButtonActionPerformed

    private void saveCancelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveCancelButtonActionPerformed
        saveDialog.setVisible(false);
    }//GEN-LAST:event_saveCancelButtonActionPerformed

    private void waveformDisplayCanvasMouseDragged(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_waveformDisplayCanvasMouseDragged
        waveformDisplay.onMouseDragged(evt);
    }//GEN-LAST:event_waveformDisplayCanvasMouseDragged

    private void waveformDisplayCanvasMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_waveformDisplayCanvasMouseMoved
        waveformDisplay.onMouseMoved(evt);
    }//GEN-LAST:event_waveformDisplayCanvasMouseMoved

    private void waveformDisplayCanvasMousePressed(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_waveformDisplayCanvasMousePressed
        waveformDisplay.onMouseEvent(evt);
    }//GEN-LAST:event_waveformDisplayCanvasMousePressed

    private void fillInButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_fillInButtonActionPerformed
        if (trackBaseNameTextField.getText().equals("")) {
            trackBaseNameTextField.setText(fileRoot);
        }
        fillInDialog.pack();
        fillInDialog.setVisible(true);
    }//GEN-LAST:event_fillInButtonActionPerformed

    private void fillInOKButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_fillInOKButtonActionPerformed
        String newTrackBaseName = trackBaseNameTextField.getText();
        fillInDialog.setVisible(false);
        trackList.fillInGaps(newTrackBaseName);
    }//GEN-LAST:event_fillInOKButtonActionPerformed

    private void fillInCancelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_fillInCancelButtonActionPerformed
        fillInDialog.setVisible(false);
    }//GEN-LAST:event_fillInCancelButtonActionPerformed

    private void markTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_markTextFieldActionPerformed
        float v = Float.parseFloat(markTextField.getText());
        trackList.setCurrentMark(v);
    }//GEN-LAST:event_markTextFieldActionPerformed

    private void computePeakLevelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_computePeakLevelButtonActionPerformed
        Track track = trackList.getTrack(trackList.getCurrentTrackNumber());
        if (track != null) {
            AudioInputStream s = audio.getAudioInputStream(track.getStart(), track.getEnd() - track.getStart());
            NormalisationFilter nf = new NormalisationFilter(s);
            byte[] buffer = new byte[Sink.EXTERNAL_BUFFER_SIZE];
            try {
                while (nf.read(buffer, 0, Sink.EXTERNAL_BUFFER_SIZE) == Sink.EXTERNAL_BUFFER_SIZE) {
                }
                track.setPeakLevel(nf.getPeak());
                trackChanged();
            } catch (IOException ioe) {
                alert("IO Exception: " + ioe.getMessage());
            }
        }
    }//GEN-LAST:event_computePeakLevelButtonActionPerformed

    private void targetLevelTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_targetLevelTextFieldActionPerformed
        Track track = trackList.getTrack(trackList.getCurrentTrackNumber());
        if (track != null) {
            track.setTargetLevel(Integer.parseInt(targetLevelTextField.getText()));
            trackChanged();
        }
    }//GEN-LAST:event_targetLevelTextFieldActionPerformed

    private void silenceParamsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_silenceParamsActionPerformed
        silenceParamsDialog.pack();
        silenceParamsDialog.setVisible(true);
    }//GEN-LAST:event_silenceParamsActionPerformed

    private void silencesDialogDoneActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_silencesDialogDoneActionPerformed
        silenceParamsDialog.setVisible(false);
    }//GEN-LAST:event_silencesDialogDoneActionPerformed

    private void thresholdAddButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_thresholdAddButtonActionPerformed
        Integer l = Integer.parseInt(thresholdLevelTextField.getText());
        Float d = Float.parseFloat(thresholdDurationTextField.getText());
        silences.addThreshold(l, d);
    }//GEN-LAST:event_thresholdAddButtonActionPerformed

    private void thresholdRemoveButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_thresholdRemoveButtonActionPerformed
        Integer l = Integer.parseInt(thresholdLevelTextField.getText());
        silences.deleteThreshold(l);
    }//GEN-LAST:event_thresholdRemoveButtonActionPerformed

    private void jumpToLastSilenceActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jumpToLastSilenceActionPerformed
        float cur_mark = trackList.getCurrentMark();
        float best = -1;
        Iterator<Silence> si = silences.iterator();
        while (si.hasNext()) {
            Silence s = si.next();
            if (s.getEnd() < cur_mark) {
                if (best < 0 || s.getEnd() > best)
                    best = s.getEnd();
            }
        }
        if (best >= 0) {
            trackList.setCurrentMark(best);
            playAfterButtonActionPerformed(null);
        }
    }//GEN-LAST:event_jumpToLastSilenceActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton addTrackButton;
    private javax.swing.JTextField albumTextField;
    private javax.swing.JDialog alertDialog;
    private javax.swing.JLabel alertText;
    private javax.swing.JRadioButton allTracksRadioButton;
    private javax.swing.JTextField artistTextField;
    private javax.swing.JButton backButton;
    private javax.swing.JTextField clipLengthTextField;
    private javax.swing.JTextField commentTextField;
    private javax.swing.JTextField composerTextField;
    private javax.swing.JButton computePeakLevelButton;
    private javax.swing.JTextField conductorTextField;
    private javax.swing.JRadioButton currentTrackRadioButton;
    private javax.swing.JDialog dataLossConfirmDialog;
    private javax.swing.JButton deleteTrackButton;
    private javax.swing.JButton fadeInButton;
    private javax.swing.JButton fadeOutButton;
    private javax.swing.JMenu fileMenu;
    private javax.swing.JButton fillInButton;
    private javax.swing.JDialog fillInDialog;
    private javax.swing.JLabel formantLabel;
    private javax.swing.JButton forwardButton;
    private javax.swing.JButton interruptButton;
    private javax.swing.JButton jumpToLastSilence;
    private javax.swing.JButton jumpToNextSilence;
    private javax.swing.JCheckBox lockLaterTracksCheckbox;
    private javax.swing.JButton markEndOfTrackButton;
    private javax.swing.JButton markStartOfTrackButton;
    private javax.swing.JTextField markTextField;
    private javax.swing.JToolBar markToolBar;
    private javax.swing.JRadioButton modifiedTracksRadioButton;
    private javax.swing.JLayeredPane newThresholdPanel;
    private javax.swing.JMenuItem open;
    private javax.swing.JFileChooser openFileChooser;
    private javax.swing.JMenu openRecent;
    private javax.swing.JLabel peakLevelLabel;
    private javax.swing.JButton playAfterButton;
    private javax.swing.JButton playBeforeButton;
    private javax.swing.JPanel powerDIsplayPanel;
    private javax.swing.JCheckBox saveAudioCheckBox;
    private javax.swing.JDialog saveDialog;
    private javax.swing.JCheckBox saveScriptsCheckBox;
    private javax.swing.JCheckBox saveSilencesCheckBox;
    private javax.swing.JCheckBox saveTracksCheckBox;
    private javax.swing.JButton scanForSilence;
    private javax.swing.JLabel setUpSilences;
    private javax.swing.JButton silenceParams;
    private javax.swing.JDialog silenceParamsDialog;
    private javax.swing.JButton silencesDialogDone;
    private javax.swing.JTextField targetLevelTextField;
    private javax.swing.JButton thresholdAddButton;
    private javax.swing.JLabel thresholdDurationLabel;
    private javax.swing.JTextField thresholdDurationTextField;
    private javax.swing.JLabel thresholdEditLabel;
    private javax.swing.JLabel thresholdLevelLabel;
    private javax.swing.JTextField thresholdLevelTextField;
    private javax.swing.JButton thresholdRemoveButton;
    private javax.swing.JList thresholdsList;
    private javax.swing.JScrollPane thresholdsScrollPane;
    private javax.swing.JTextField titleTextField;
    private javax.swing.JMenuBar topMenu;
    private javax.swing.JTextField trackBaseNameTextField;
    private javax.swing.JButton trackEndButton;
    private javax.swing.JPanel trackListWindowPanel;
    private javax.swing.JLabel trackNameLabel;
    private javax.swing.JSpinner trackNumberSpinner;
    private javax.swing.JButton trackStartButton;
    private javax.swing.JPanel waveformDisplayPanel;
    private javax.swing.JLabel whatsChangedLabel;
    private javax.swing.JButton writeButton;
    private javax.swing.JLabel zoomLabel;
    private javax.swing.JTextField zoomWidthTextField;
    // End of variables declaration//GEN-END:variables
}
