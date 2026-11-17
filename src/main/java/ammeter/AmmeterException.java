package ammeter;

/** A measurement could not be read from an ammeter. */
public class AmmeterException extends Exception {
    public AmmeterException(String message) {
        super(message);
    }
}
