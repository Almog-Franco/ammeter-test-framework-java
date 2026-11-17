package ammeter;

/**
 * One configured ammeter.
 *
 * @param emulator which emulator to start for it; null for a real device
 */
public record AmmeterSpec(String name, int port, String command, String host, String emulator,
                          Double expectedMin, Double expectedMax, Double expectedMean,
                          Double expectedGeoMean, Double expectedLogStd) {

    public AmmeterSpec(String name, int port, String command) {
        this(name, port, command, "127.0.0.1", null, null, null, null, null, null);
    }

    public AmmeterSpec withPort(int newPort) {
        return new AmmeterSpec(name, newPort, command, host, emulator, expectedMin, expectedMax, expectedMean,
                expectedGeoMean, expectedLogStd);
    }

    public AmmeterSpec withHost(String newHost) {
        return new AmmeterSpec(name, port, command, newHost, emulator, expectedMin, expectedMax, expectedMean,
                expectedGeoMean, expectedLogStd);
    }

    public AmmeterSpec withEmulator(String newEmulator) {
        return new AmmeterSpec(name, port, command, host, newEmulator, expectedMin, expectedMax, expectedMean,
                expectedGeoMean, expectedLogStd);
    }
}
