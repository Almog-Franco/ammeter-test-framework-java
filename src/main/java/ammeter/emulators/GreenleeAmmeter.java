package ammeter.emulators;

import ammeter.Fmt;

import java.nio.charset.StandardCharsets;

public class GreenleeAmmeter extends AmmeterEmulator {
    public GreenleeAmmeter(int port) {
        super(port);
    }

    @Override
    public byte[] getCurrentCommand() {
        // Define the command to get the current from Greenlee
        return "MEASURE_GREENLEE -get_measurement".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public double measureCurrent() {
        double voltage = Utils.generateRandomFloat(1.0, 10.0); // Random voltage (1V - 10V)
        double resistance = Utils.generateRandomFloat(0.1, 100.0); // Random resistance (0.1Ω - 100Ω)
        double current = voltage / resistance;
        System.out.println("Greenlee Ammeter - Voltage: " + Fmt.repr(voltage) + "V, Resistance: " + Fmt.repr(resistance)
                + "Ω, Current: " + Fmt.repr(current) + "A");
        return current;
    }
}
