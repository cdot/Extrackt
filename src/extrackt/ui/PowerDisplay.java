package extrackt.ui;

import java.awt.Graphics;
import java.awt.Dimension;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.event.MouseEvent;
import extrackt.SampleWatcher;
import extrackt.dsp.WindowFrames;

/**
 * A WaveformDisplay is a panel that displays a streamed waveform, and
 * decorations on the waveform such as the current mark. It supports additional
 * listeners (called Painters) which allow you to add extra decoration, such as
 * silences, as the waveform is drawn. Because the resolution of a sample stream
 * is a lot higher than we want to display, samples are collected into
 * "buckets". Each bucket corresponds to a single x-ordinate on the output
 * display.
 *
 * @author Crawford Currie
 */
public class PowerDisplay extends JPanel implements SampleWatcher.Watcher {

    private JLabel startLabel, endLabel, minLabel, maxLabel, formantLabel;
    private float[] data;
    private float maxp;
    private float sampleRate;
    private float fundamentalFrequency;

    /**
     * Creates a new instance of PowerDisplay
     */
    public PowerDisplay() {
        maxp = 120;
        sampleRate = 44100;
    }

    public void setLabels(JLabel start, JLabel end, JLabel min, JLabel max, JLabel fmnt) {
        startLabel = start;
        endLabel = end;
        minLabel = min;
        maxLabel = max;
        formantLabel = fmnt;
        fundamentalFrequency = 0;
    }

    /**
     * @param sr sampleRate
     */
    public void reset(float sr) {
        this.sampleRate = sr;
    }

    private void relabel() {
        if (startLabel != null) {
            startLabel.setText("0");
        }
        if (endLabel != null) {
            endLabel.setText(Float.toString(sampleRate));
        }
        if (minLabel != null) {
            minLabel.setText("0");
        }
        if (maxLabel != null) {
            maxLabel.setText(Float.toString(maxp));
        }
    }

    /**
     * Add FFT data. They are assume to come from a FFT and comprise a single
     * real part.
     *
     * @param fft samples
     */
    @Override
    public void addSamples(float[][] fft) {
        data = new float[fft.length];
        for (int i = 0; i < data.length; i++) {
            data[i] = fft[i][0];
        }
        WindowFrames.Function hps = new WindowFrames.HarmonicProductSpectrum(sampleRate);
        fundamentalFrequency = hps.window(data, data.length);
        formantLabel.setText(Float.toString(fundamentalFrequency));
        repaint();
    }

    public void onMouseMoved(MouseEvent evt) {
        Dimension size = getSize();
        if (data != null) {
            int j = evt.getX() * data.length / size.width;
            float hz = j * sampleRate / data.length;
            setToolTipText(Float.toString(hz) + "Hz " + data[j]);
        }
    }

    @Override
    public void paintComponent(final Graphics g) {
        relabel();

        Dimension size = getSize();
        g.setColor(Colors.BACKGROUND);
        g.fillRect(0, 0, size.width, size.height);
        if (data != null) {
            int h = size.height;
            int w = size.width;
            WindowFrames.Function norm = new WindowFrames.Normalise();
            maxp = norm.window(data, data.length);
            // Each bucket is sample_freq/Nsamples wide
            g.setColor(Colors.POWER_SPECTRUM);
            for (int j = 0; j < data.length; j++) {
                int x = (int) (j * w / data.length);
                float sample = data[j];
                int y = (int) (sample * h / maxp);
                g.drawLine(x, h, x, h - y);
            }
            g.setColor(Colors.POWER_GRID);
            for (int i = 5000; i < sampleRate; i += 5000) {
                int x = (int) (i * w / sampleRate);
                g.drawLine(x, 0, x, (int) maxp);
            }

            g.setColor(Colors.CURRENT_MARK);
            int x = (int) (fundamentalFrequency * w / sampleRate);
            g.drawLine(x, 0, x, h);
        }
    }
}
