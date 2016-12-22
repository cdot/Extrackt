package extrackt;

/**
 * Listener for audio events that define a range of times
 * @author crawford
 */
public interface AudioRangeListener {
    /**
     * Function called when a range has been detected by a filter e.g.
     * a SilenceFilter.
     * @param start start of event (seconds)
     * @param end end of event (seconds)
     * @param Object data arbitrary data associated with the event
     * @return false if you are not interested in more events
     */
    public boolean rangeEvent(float start, float end, Object data);
}
