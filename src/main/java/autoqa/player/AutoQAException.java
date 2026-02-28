package autoqa.player;

/**
 * Unchecked exception thrown by all player components when a step cannot
 * be completed — timeout, element not found, unexpected alert, etc.
 */
public class AutoQAException extends RuntimeException {

    public AutoQAException(String msg) {
        super(msg);
    }

    public AutoQAException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
