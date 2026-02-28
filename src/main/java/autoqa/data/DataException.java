package autoqa.data;

/**
 * Thrown when a {@link DataTable} cannot be loaded or the data is malformed.
 */
public class DataException extends RuntimeException {

    public DataException(String message) {
        super(message);
    }

    public DataException(String message, Throwable cause) {
        super(message, cause);
    }
}
