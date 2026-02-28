package autoqa.recorder;

import autoqa.model.RecordedEvent;
import java.util.function.Consumer;

/**
 * Abstraction over OS-level input capture backends.
 * Implementations register global hooks and forward captured events
 * to the provided callback for enrichment and storage.
 */
public interface InputCaptureAdapter {
    /**
     * Starts capturing OS input events and delivering them to {@code eventCallback}.
     *
     * @param eventCallback consumer that receives each raw {@link RecordedEvent}
     */
    void start(Consumer<RecordedEvent> eventCallback);

    /**
     * Stops capturing and releases any native hooks or resources.
     */
    void stop();
}
