package ammeter;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Runs emulators in a separate process, like a real device, and sends their output to a log file. */
public final class EmulatorProcess implements AutoCloseable {
    private final List<AmmeterSpec> ammeters;
    private final Path logPath;
    private final Run.FaultOptions faults;
    private final Long seed;
    private Process process;
    private Thread shutdownHook;

    public EmulatorProcess(List<AmmeterSpec> ammeters, Path logPath, Run.FaultOptions faults, Long seed) {
        this.ammeters = List.copyOf(ammeters);
        this.logPath = logPath;
        this.faults = faults;
        this.seed = seed;
    }

    public EmulatorProcess(List<AmmeterSpec> ammeters, Path logPath) {
        this(ammeters, logPath, null, null);
    }

    public static boolean portIsOpen(String host, int port, double timeoutS) {
        try (Socket ignored = Sockets.connect(host, port, (int) Math.max(1, timeoutS * 1000))) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean portIsOpen(String host, int port) {
        return portIsOpen(host, port, 0.2);
    }

    public static boolean waitForPort(String host, int port, double timeoutS) throws InterruptedException {
        long deadline = System.nanoTime() + (long) (timeoutS * 1e9);
        while (System.nanoTime() < deadline) {
            if (portIsOpen(host, port)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    public Process process() {
        return process;
    }

    public synchronized EmulatorProcess start() {
        Map<String, Object> ammeterArgs = new LinkedHashMap<>();
        for (AmmeterSpec a : ammeters) {
            ammeterArgs.put(a.name(), Map.of("emulator", a.emulator(), "port", a.port()));
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ammeters", ammeterArgs);
        args.put("faults", faults);
        args.put("seed", seed);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // UTF-8 output, otherwise Greenlee's 'Ω' turns into '?' when output goes to a file on Windows
        List<String> command = List.of(java.toString(), "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                "ammeter.emulators.Emulators");
        try {
            Files.createDirectories(logPath.toAbsolutePath().getParent());
            process = new ProcessBuilder(command)
                    .directory(Settings.PROJECT_ROOT.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()))
                    .start();
            // the options go through stdin: Windows mangles quotes in command-line arguments
            try (var stdin = process.getOutputStream()) {
                stdin.write(Json.compact(args).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            stop();
            throw new EmulatorException("could not start the emulator process: " + e.getMessage());
        }
        // An emulator must never outlive this program
        shutdownHook = new Thread(this::stop, "emulator-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        waitUntilReady(10.0);
        return this;
    }

    public synchronized void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // the program is already shutting down
            }
        }
        process = null;
        shutdownHook = null;
    }

    @Override
    public void close() {
        stop();
    }

    private void waitUntilReady(double timeoutS) {
        List<AmmeterSpec> pending = new ArrayList<>(ammeters);
        long deadline = System.nanoTime() + (long) (timeoutS * 1e9);
        try {
            while (!pending.isEmpty() && System.nanoTime() < deadline && process.isAlive()) {
                pending.removeIf(a -> portIsOpen(a.host(), a.port()));
                if (!pending.isEmpty()) {
                    Thread.sleep(50);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!pending.isEmpty()) {
            stop();
            String names = pending.stream().map(a -> a.name() + " (port " + a.port() + ")").collect(Collectors.joining(", "));
            throw new EmulatorException("emulator did not start: " + names + ". Last lines of " + logPath + ":\n"
                    + String.join("\n", logTail()));
        }
    }

    private List<String> logTail() {
        try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
            long start = Math.max(0, file.length() - 4000);
            byte[] bytes = new byte[(int) (file.length() - start)];
            file.seek(start);
            file.readFully(bytes);
            List<String> lines = new String(bytes, StandardCharsets.UTF_8).strip().lines().toList();
            return lines.subList(Math.max(0, lines.size() - 5), lines.size());
        } catch (IOException e) {
            return List.of();
        }
    }
}
