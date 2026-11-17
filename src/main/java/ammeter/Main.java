package ammeter;

import ammeter.emulators.Emulators;
import ammeter.emulators.SimpleClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Quick check: starts the configured emulators and takes one reading from every ammeter. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : Settings.DEFAULT_CONFIG);
        var ammeters = Settings.load(configPath).ammeters().values();

        // Start each ammeter in a separate thread, on the port and with the command from config.yaml
        for (AmmeterSpec spec : ammeters) {
            if (spec.emulator() != null) {
                Thread thread = new Thread(Emulators.create(spec.emulator(), spec.port())::startServer, spec.name());
                thread.setDaemon(true);
                thread.start();
            }
        }

        for (AmmeterSpec spec : ammeters) {
            if (!EmulatorProcess.waitForPort(spec.host(), spec.port(), 5.0)) {
                System.out.println(spec.name() + ": nothing is listening on port " + spec.port());
                continue;
            }
            SimpleClient.requestCurrentFromAmmeter(spec.port(), spec.command().getBytes(StandardCharsets.UTF_8));
        }
    }
}
