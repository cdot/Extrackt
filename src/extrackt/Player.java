package extrackt;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Player x = new Player(AudioStream as);
 * x.start();
 * output audio data to audio stream
 * @author crawford
 */
public class Player extends Sink {
    
    private SourceDataLine out;
    
    public Player() {
        out = null;
    }

    /**
     * Play the content of the given stream
     * @param ai Stream to play
     * @param ros Runnable to execute when the stream terminates
     * @throws LineUnavailableException 
     */
    @Override
    public void play(PCMDataSource ai, Runnable ros) throws LineUnavailableException {
        AudioFormat af = ai.getFormat();
        if (out == null || !af.matches(out.getFormat())) {
            if (out != null)
                out.close();
            // Format changed, need a new line
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, af);
            out = (SourceDataLine)AudioSystem.getLine(info);
            out.open(af);
            out.start();
        }
        super.play(ai, ros);
    }
    
    /**
     * Run the thread
     */
    @Override
    public void run() {
        while (true) {
            yield();
            if (in == null || out == null || stopped)
                continue;
            int canSend = 0;
            while (!stopped) {
                yield();
                canSend = out.available();
                if (canSend > 0)
                    break;
            }
            
            if (stopped)
                continue;
            
            int nBytesRead = swallow(canSend);

            if (nBytesRead == -1) {
                stopPlaying();
                continue;
            }
            out.write(buffer, 0, nBytesRead);
        }
    }
}
