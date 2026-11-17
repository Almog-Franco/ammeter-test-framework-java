package ammeter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Shared helpers for the tests: free ports, the shared emulators, config copies and scripted TCP servers. */
public final class TestSupport {
    public static final AmmeterSpec ENTES = new AmmeterSpec("entes", 5002, "MEASURE_ENTES -get_data", "127.0.0.1",
            null, 5.0, 200.0, 68.75, 55.51, 0.7012);

    private static Map<String, AmmeterSpec> sharedAmmeters;

    private TestSupport() {
    }

    /** Readings drawn the way the ENTES emulator draws them: B * K with uniform inputs. */
    public static List<Double> entesReadings(int n, long seed) {
        Random rng = new Random(seed);
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double b = 0.01 + 0.09 * rng.nextDouble();
            double k = 500 + 1500 * rng.nextDouble();
            values.add(b * k);
        }
        return values;
    }

    public static List<Double> entesReadings(int n) {
        return entesReadings(n, 1);
    }

    /** A port that nothing listens on. */
    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path tempDir() {
        try {
            return Files.createTempDirectory("ammeter-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The ammeters from config.yaml, running on free ports until the test JVM exits. */
    public static synchronized Map<String, AmmeterSpec> ammeters() {
        if (sharedAmmeters == null) {
            Map<String, AmmeterSpec> specs = new LinkedHashMap<>();
            Settings.load().ammeters().forEach((name, spec) -> specs.put(name, spec.withPort(freePort())));
            new EmulatorProcess(List.copyOf(specs.values()), tempDir().resolve("emulators.log")).start();
            sharedAmmeters = Map.copyOf(specs);
        }
        return sharedAmmeters;
    }

    /** Refused connections behave as on Windows, which only reports the refusal after about 2 s. Close to undo. */
    public static AutoCloseable windowsLoopback() {
        Sockets.setConnector((host, port, timeoutMs) -> {
            try {
                return Sockets.DIRECT.connect(host, port, timeoutMs);
            } catch (ConnectException e) {
                try {
                    if (timeoutMs > 0 && timeoutMs < 2000) {
                        Thread.sleep(timeoutMs);
                        throw new SocketTimeoutException("timed out");
                    }
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw e;
            }
        });
        return () -> Sockets.setConnector(Sockets.DIRECT);
    }

    /** config.yaml as a mutable map, storing results in {@code tmp/results}. Edit it, then {@link #writeConfig}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> config(Path tmp) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> raw;
        try (InputStream in = Files.newInputStream(Settings.PROJECT_ROOT.resolve(Settings.DEFAULT_CONFIG))) {
            raw = yaml.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ((Map<String, Object>) raw.get("result_management")).put("results_dir", tmp.resolve("results").toString());
        return raw;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> section(Map<String, Object> raw, String... keys) {
        Map<String, Object> node = raw;
        for (String key : keys) {
            node = (Map<String, Object>) node.get(key);
        }
        return node;
    }

    public static Path writeConfig(Path tmp, Map<String, Object> raw) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Path path = tmp.resolve("config.yaml");
        try {
            Files.writeString(path, new Yaml(options).dump(raw), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    @FunctionalInterface
    public interface Handler {
        void handle(Socket conn) throws IOException, InterruptedException;
    }

    /** A local TCP server that handles connection i with handlers[i]; the last handler repeats. */
    public static final class TcpServer implements AutoCloseable {
        private final ServerSocket server;

        private TcpServer(Handler... handlers) throws IOException {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Thread thread = new Thread(() -> serve(handlers), "tcp-server");
            thread.setDaemon(true);
            thread.start();
        }

        public static TcpServer start(Handler... handlers) {
            try {
                return new TcpServer(handlers);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public int port() {
            return server.getLocalPort();
        }

        private void serve(Handler[] handlers) {
            int count = 0;
            while (true) {
                Socket conn;
                try {
                    conn = server.accept();
                } catch (IOException e) {
                    return;
                }
                Handler handler = handlers[Math.min(count, handlers.length - 1)];
                count++;
                try (conn) {
                    handler.handle(conn);
                } catch (IOException | InterruptedException ignored) {
                    // the client went away; serve the next connection
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static void readRequest(Socket conn) throws IOException {
        conn.getInputStream().read(new byte[1024]);
    }

    public static Handler answer(byte[] data, double delayS) {
        return conn -> {
            readRequest(conn);
            Thread.sleep((long) (delayS * 1000));
            conn.getOutputStream().write(data);
        };
    }

    public static Handler answer(byte[] data) {
        return answer(data, 0);
    }

    public static Handler answer(String text) {
        return answer(text.getBytes(StandardCharsets.UTF_8), 0);
    }

    /** Sends the answer one byte at a time. */
    public static Handler dribble(byte[] data, double everyS) {
        return conn -> {
            readRequest(conn);
            for (byte b : data) {
                conn.getOutputStream().write(b);
                conn.getOutputStream().flush();
                Thread.sleep((long) (everyS * 1000));
            }
        };
    }

    /** Drops a connection with a TCP reset (RST) instead of a normal close. */
    public static void reset(Socket conn) throws IOException {
        conn.setSoLinger(true, 0);
    }
}
