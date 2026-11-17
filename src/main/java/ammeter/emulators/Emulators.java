package ammeter.emulators;

import ammeter.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** The emulators by name, and the entry point of the process that serves them. */
public final class Emulators {
    public static final Map<String, IntFunction<AmmeterEmulator>> EMULATORS = Map.of(
            "greenlee", GreenleeAmmeter::new,
            "entes", EntesAmmeter::new,
            "circutor", CircutorAmmeter::new);

    private Emulators() {
    }

    public static AmmeterEmulator create(String name, int port) {
        IntFunction<AmmeterEmulator> factory = name == null ? null : EMULATORS.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("unknown emulator '" + name + "'");
        }
        return factory.apply(port);
    }

    /**
     * Starts one server thread per ammeter and returns them.
     *
     * @param ammeters name to {@code {emulator, port}}
     * @param faults   null, or {@code {rates, seed, delay_s}} to wrap every emulator in a {@link FaultyAmmeter}
     * @param seed     null, or the seed of the shared random generator
     */
    public static List<Thread> serve(JsonNode ammeters, JsonNode faults, Long seed) {
        Map<String, AmmeterEmulator> emulators = new LinkedHashMap<>();
        ammeters.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            AmmeterEmulator emulator = create(entry.getValue().path("emulator").asText(null), entry.getValue().path("port").asInt());
            if (faults != null && !faults.isNull() && !faults.isEmpty()) {
                JsonNode faultSeed = faults.path("seed");
                Object seedText = faultSeed.isMissingNode() || faultSeed.isNull() ? null : faultSeed.asText() + ":" + name;
                Map<String, Double> rates = new LinkedHashMap<>();
                faults.path("rates").fields().forEachRemaining(rate -> rates.put(rate.getKey(), rate.getValue().asDouble()));
                emulator = new FaultyAmmeter(emulator, rates, seedText, faults.path("delay_s").asDouble(3.0));
            }
            emulators.put(name, emulator);
        });
        if (seed != null) {
            Utils.seed(seed);
        }
        List<Thread> threads = new ArrayList<>();
        emulators.forEach((name, emulator) -> {
            Thread thread = new Thread(emulator::startServer, name);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        });
        return threads;
    }

    /**
     * Run by {@code EmulatorProcess}: {@code java ammeter.emulators.Emulators '{"ammeters": {...}, "faults": ..., "seed": ...}'}.
     * Without an argument the options are read from stdin.
     */
    public static void main(String[] args) throws Exception {
        // UTF-8 output, otherwise Greenlee's 'Ω' turns into '?' when output goes to a file on Windows
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        // An emulator must never outlive the program that started it, even one that was killed
        ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> System.exit(0)));
        try {
            String text = args.length > 0 ? args[0] : new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode options = Json.MAPPER.readTree(text);
            JsonNode seed = options.path("seed");
            serve(options.path("ammeters"), options.get("faults"), seed.isIntegralNumber() ? seed.asLong() : null);
        } catch (Exception e) {
            e.printStackTrace();
            // Last line of the log, so the error EmulatorProcess shows names the cause, not a stack frame
            System.err.println(e);
            System.exit(1);
        }
        Thread.currentThread().join();
    }
}
