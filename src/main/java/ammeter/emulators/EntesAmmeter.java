package ammeter.emulators;

import ammeter.Fmt;

import java.nio.charset.StandardCharsets;

public class EntesAmmeter extends AmmeterEmulator {
    public EntesAmmeter(int port) {
        super(port);
    }

    @Override
    public byte[] getCurrentCommand() {
        // Define the command to get the current from ENTES
        return "MEASURE_ENTES -get_data".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public double measureCurrent() {
        double magneticField = Utils.generateRandomFloat(0.01, 0.1); // Magnetic field strength (0.01T - 0.1T)
        double calibrationFactor = Utils.generateRandomFloat(500, 2000); // Calibration factor (500 - 2000)
        double current = magneticField * calibrationFactor;
        System.out.println("ENTES Ammeter - Magnetic Field: " + Fmt.repr(magneticField) + "T, Calibration Factor: "
                + Fmt.repr(calibrationFactor) + ", Current: " + Fmt.repr(current) + "A");
        return current;
    }
}
