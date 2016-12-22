package extrackt.ui;

import java.awt.Dimension;
import java.awt.Graphics;

/**
 * A painter used to decorate displays with meta-data
 * @author crawford
 */
public interface Painter {
    public interface Transformer {

        /**
         * Transform a logical X (usually seconds) to a display X (pixels)
         *
         * @param s logical X to transform
         * @return display X
         */
        public int l2p_x(float s);

        /**
         * Get the display size (pixels)
         *
         * @return the display size
         */
        public Dimension getSize();

        /**
         * Get the max Y for the logical area
         * @return the top of the logical display area.
         */
        public float getMaxY();
        
        /**
         * (en|dis)able debug
         * @param b (en|dis)able
         */
        public void debug(boolean b);
        
        /**
         * (en|dis)able debug
         * @return (en|dis)able
         */
        public boolean debug();
    }

    /**
     * Paint display information pertinent to a waveform display
     * @param t
     * @param g 
     */
    public void paintWaveform(Transformer t, Graphics g);

    /**
     * Paint display information pertinent to a track list display
     * @param t
     * @param g 
     */
    public void paintTracklist(Transformer t, Graphics g);
}
