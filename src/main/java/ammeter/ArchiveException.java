package ammeter;

/** A run is missing from the results archive, or its files are damaged. */
public class ArchiveException extends RuntimeException {
    public ArchiveException(String message) {
        super(message);
    }
}
