package ammeter;

/** The configuration file is missing or invalid. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
