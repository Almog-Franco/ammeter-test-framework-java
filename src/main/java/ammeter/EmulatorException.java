package ammeter;

/** The ammeter emulators could not be started. */
public class EmulatorException extends RuntimeException {
    public EmulatorException(String message) {
        super(message);
    }
}
