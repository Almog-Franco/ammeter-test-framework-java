package ammeter;

/** The ammeter answered, but not with a valid reading. */
public class AmmeterResponseException extends AmmeterException {
    public AmmeterResponseException(String message) {
        super(message);
    }
}
