package extrackt;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import static java.lang.Thread.yield;

/**
 * Endpoint sink; sits on the end of a chain of input streams, sucking
 * up data and throwing it away.
 * @author crawford
 */
public class Sink extends Thread {
    public static final int EXTERNAL_BUFFER_SIZE = 1048576; // 2^20, 1Mb
    
    protected AudioInputStream in;
    protected boolean stopped;
    private Runnable runOnStop;
    protected byte[] buffer;
    
    public Sink() {
        in = null;
        stopped = true;
        runOnStop = null;
        buffer = new byte[EXTERNAL_BUFFER_SIZE];
    }
    
    /**
     * @param ai Stream to play
     * @param runOnStop Runnable to execute when the stream terminates
     * @throws LineUnavailableException 
     */
    public void play(PCMDataSource ai, Runnable runOnStop) throws LineUnavailableException {
        // Disconnect from currently open stream
        if (in != null && ai != in) {
            try {
                in.close();
            } catch (IOException e) {
            }
        }
        in = ai;
        stopped = false;
        this.runOnStop = runOnStop;
        // Start the thread, if necessary. It will run until interrupted.
        if (!isAlive()) {
            start();
        }
    }

    /**
     * Stop processing the currently playing stream. This is *not* the
     * same as interrupt(), which interrupts the thread. This just stops the
     * current play.
     */
    public void stopPlaying() {
        stopped = true;
        // Disconnect from currently open stream
        if (in != null) {
/*            try {
                in.close();
            } catch (IOException e) {
            }*/
        }
        if (runOnStop != null) {
            runOnStop.run();
            runOnStop = null;
        }
    }

    /**
     * Suck up to max bytes from the input stream, and store them in the buffer.
     * @param max Maximum number of bytes to read
     * @return number of bytes read
     */
    protected int swallow(int max) {
        if (max <= 0 || max > EXTERNAL_BUFFER_SIZE)
            max = EXTERNAL_BUFFER_SIZE;
        int canReceive = -1;
        while (!stopped) {
             yield(); // in case another process is preparing the data
             canReceive = -1;
             try {
                 canReceive = in.available();
             } catch (IOException ioe) {
                 System.out.println("IOE on available "+ioe);
                 stopPlaying();
             }

             if (canReceive >= 0)
                 break;
        }
        if (canReceive > max)
            canReceive = max;
        
        if (canReceive <= 0) {
            interrupt();
            return -1;
        }
        
        int nBytesRead = -1;
        try {
            nBytesRead = in.read(buffer, 0, canReceive);
        } catch (IOException ioe) {
             System.out.println("IOE on read "+ioe);
             interrupt();
        }
        return nBytesRead;
    }
    
    /**
     * Run the thread.
     */
    @Override
    public void run() {
        int got = -1;
        while (!interrupted()) {
            if (in == null || stopped)
                continue;
            got = swallow(0);
            if (got < 0) {
                break;
            }
        }
        stopPlaying();
    }
}
