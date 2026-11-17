package ammeter.emulators;

import ammeter.Fmt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CircutorAmmeter extends AmmeterEmulator {
    public CircutorAmmeter(int port) {
        super(port);
    }

    @Override
    public byte[] getCurrentCommand() {
        // Define the command to get the current from CIRCUTOR
        return "MEASURE_CIRCUTOR -get_measurement -current".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public double measureCurrent() {
        int numSamples = 10;
        double timeStep = Utils.generateRandomFloat(0.001, 0.01); // Time step (0.001s - 0.01s)
        List<Double> voltages = new ArrayList<>();
        for (int i = 0; i < numSamples; i++) {
            voltages.add(Utils.generateRandomFloat(0.1, 1.0)); // Voltage values
        }
        System.out.println("CIRCUTOR Ammeter - Voltages: " + Fmt.repr(voltages) + ", Time Step: " + Fmt.repr(timeStep) + "s");
        double current = 0;
        for (double v : voltages) {
            current += v * timeStep;
        }
        System.out.println("Current: " + Fmt.repr(current) + "A");
        return current;
    }
}
