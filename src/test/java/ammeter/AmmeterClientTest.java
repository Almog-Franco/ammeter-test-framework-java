package ammeter;

import static ammeter.TestSupport.answer;
import static ammeter.TestSupport.dribble;
import static ammeter.TestSupport.freePort;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import ammeter.TestSupport.Handler;
import ammeter.TestSupport.TcpServer;
import ammeter.emulators.SimpleClient;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class AmmeterClientTest {
    private static final byte[] MEASURE = "MEASURE".getBytes(StandardCharsets.UTF_8);
    private static final byte[] M = "M".getBytes(StandardCharsets.UTF_8);

    private final List<TcpServer> servers = new ArrayList<>();

    private int server(Handler... handlers) {
        TcpServer server = TcpServer.start(handlers);
        servers.add(server);
        return server.port();
    }

    @AfterMethod(alwaysRun = true)
    public void closeServers() throws IOException {
        for (TcpServer server : servers) {
            server.close();
        }
        servers.clear();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static AmmeterClient client(int port, byte[] command, double timeoutS, int retries) {
        return new AmmeterClient("127.0.0.1", port, command, timeoutS, retries);
    }

    private static AmmeterClient client(int port, byte[] command) {
        return new AmmeterClient("127.0.0.1", port, command);
    }

    @DataProvider
    public Object[][] validReadings() {
        return new Object[][] {
            {bytes("0.5"), 0.5},
            {bytes("  12.75\n"), 12.75},
            {bytes("1e-3"), 0.001},
            {bytes("-3"), -3.0},
            {bytes("+.5"), 0.5},
            {bytes("0.004912345678912"), 0.004912345678912}, // full resolution
        };
    }

    /** Well-formed replies (spaces, signs, exponents) are read as numbers, at full resolution. */
    @Test(dataProvider = "validReadings")
    public void validReadings(byte[] data, double expected) throws Exception {
        assertEquals(AmmeterClient.parseReading(data), expected, 0.0);
    }

    @DataProvider
    public Object[][] invalidReadings() {
        return new Object[][] {
            {bytes(""), "empty response"},
            {bytes("   "), "empty response"},
            {bytes("ERR#42"), "not a number: 'ERR#42'"},
            {bytes("."), "not a number"},
            {bytes("nan"), "not a finite number"},
            {bytes("inf"), "not a finite number"},
            {new byte[] {(byte) 0xff, (byte) 0xfe}, "not a number"},
            {bytes("1_000"), "not a number"},
            {bytes("0x10"), "not a number"},
            {bytes("12.5 A"), "not a number"},
            {bytes("-Infinity"), "not a finite number"},
            {bytes("1e999"), "not a finite number"},
            {bytes("é"), "é"},
        };
    }

    /** Empty, non-numeric, non-finite or oddly formatted replies raise AmmeterResponseException, quoting the reply. */
    @Test(dataProvider = "invalidReadings")
    public void invalidReadings(byte[] data, String message) {
        AmmeterResponseException error = expectThrows(AmmeterResponseException.class,
                () -> AmmeterClient.parseReading(data));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    /** A long garbage reply is quoted in the error message, but cut short. */
    @Test
    public void longGarbageIsQuotedBriefly() {
        AmmeterResponseException error = expectThrows(AmmeterResponseException.class,
                () -> AmmeterClient.parseReading(bytes("x".repeat(200))));
        assertTrue(error.getMessage().contains("'" + "x".repeat(30)) && error.getMessage().length() < 80,
                error.getMessage());
    }

    /**
     * A normal reply is read, also when it arrives in pieces, by both the new client and the original one, and the
     * original one has a timeout. The repeated reads check that every connection is read and closed normally.
     */
    @Test
    public void readsAValueAndClosesTheSocket() throws Exception {
        int port = server(answer(bytes("1.5")));
        assertEquals(client(port, MEASURE).measure(), 1.5, 0.0);
        int inPieces = server(dribble(bytes("1.5"), 0.05));
        assertEquals(client(inPieces, MEASURE, 1.0, 1).measure(), 1.5, 0.0);
        assertEquals(SimpleClient.requestCurrentFromAmmeter(server(answer(bytes("2.5"))), MEASURE), 2.5, 0.0);
        int slow = server(answer(bytes("2.5"), 2.0));
        expectThrows(SocketTimeoutException.class, () -> SimpleClient.requestCurrentFromAmmeter(slow, MEASURE, 0.3));

        for (int i = 0; i < 3; i++) {
            assertEquals(client(port, M).measure(), 1.5, 0.0);
        }
    }

    @DataProvider
    public Object[][] slowRefusal() {
        return new Object[][] {{false}, {true}}; // refused at once; refused slowly, as on Windows
    }

    /** An ammeter that isn't listening gives AmmeterConnectionException, naming the address and the reason. */
    @Test(dataProvider = "slowRefusal")
    @SuppressWarnings("try") // the resource only swaps the connector while it is open
    public void unreachableAmmeter(boolean slowRefusal) throws Exception {
        try (AutoCloseable windows = slowRefusal ? TestSupport.windowsLoopback() : () -> { }) {
            AmmeterConnectionException error = expectThrows(AmmeterConnectionException.class,
                    () -> client(freePort(), MEASURE, 0.5, 1).measure());
            assertTrue(error.getMessage().contains("cannot reach"), error.getMessage());
            int port = freePort();
            error = expectThrows(AmmeterConnectionException.class, () -> client(port, M, 0.5, 0).measure());
            assertTrue(Pattern.compile("cannot reach 127\\.0\\.0\\.1:" + port + ": \\w+").matcher(error.getMessage())
                    .find(), error.getMessage());
        }
    }

    /** The configured host is really used, also through fromSpec(). Skipped without IPv6. */
    @Test(dataProvider = "slowRefusal")
    @SuppressWarnings("try") // the resource only swaps the connector while it is open
    public void unreachableAmmeterUsesTheConfiguredHost(boolean slowRefusal) throws Exception {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getByName("::1"), 0));
        } catch (IOException e) {
            throw new SkipException("no IPv6 loopback on this machine");
        }
        try (AutoCloseable windows = slowRefusal ? TestSupport.windowsLoopback() : () -> { }) {
            int port = server(answer(bytes("1.5"))); // listens on 127.0.0.1 only, so the configured host must be used
            expectThrows(AmmeterConnectionException.class,
                    () -> new AmmeterClient("::1", port, M, 1.0, 0).measure());
            expectThrows(AmmeterConnectionException.class,
                    () -> AmmeterClient.fromSpec(new AmmeterSpec("x", port, "M").withHost("::1"), 1.0, 0).measure());
        }
    }

    /** The timeout limits the whole request, not each network call. */
    @Test
    public void timeoutCoversTheWholeRequest() {
        int port = server(answer(bytes("1.0"), 1.0));
        AmmeterTimeoutException late = expectThrows(AmmeterTimeoutException.class,
                () -> client(port, MEASURE, 0.2, 1).measure());
        assertTrue(late.getMessage().contains("within 0.2 s"), late.getMessage());

        int byteByByte = server(dribble(bytes("12.5000000"), 0.15)); // each byte in time, the whole answer not
        long start = System.nanoTime();
        AmmeterTimeoutException slow = expectThrows(AmmeterTimeoutException.class,
                () -> client(byteByByte, MEASURE, 0.5, 0).measure());
        assertTrue(slow.getMessage().contains("no complete answer"), slow.getMessage());
        assertTrue(seconds(start) < 0.5 + 0.25, "took " + seconds(start));

        int endless = server(dribble(bytes("1".repeat(100)), 0.1));
        start = System.nanoTime();
        expectThrows(AmmeterTimeoutException.class, () -> client(endless, MEASURE, 1.0, 0).measure());
        double elapsed = seconds(start);
        assertTrue(0.9 <= elapsed && elapsed < 1.3, "took " + elapsed);

        int fast = server(dribble(bytes("1".repeat(1000)), 0.02)); // a byte every 20 ms, never finishing in time
        start = System.nanoTime();
        expectThrows(AmmeterTimeoutException.class, () -> client(fast, MEASURE, 0.3, 0).measure());
        assertTrue(seconds(start) < 1.0, "took " + seconds(start));
    }

    private static Handler endless() {
        return conn -> {
            conn.getInputStream().read(new byte[1024]);
            byte[] chunk = bytes("1".repeat(512));
            while (true) {
                conn.getOutputStream().write(chunk);
                Thread.sleep(10);
            }
        };
    }

    /** Replies over 1024 bytes are rejected, also endless ones; a reply of exactly 1024 bytes is accepted. */
    @Test
    public void oversizedAnswersAreRejected() throws Exception {
        int big = server(answer(bytes("1".repeat(5000))));
        AmmeterResponseException error = expectThrows(AmmeterResponseException.class,
                () -> client(big, MEASURE).measure());
        assertTrue(error.getMessage().contains("longer than 1024 bytes"), error.getMessage());

        int endless = server(endless());
        long start = System.nanoTime();
        error = expectThrows(AmmeterResponseException.class, () -> client(endless, MEASURE, 2.0, 0).measure());
        assertTrue(error.getMessage().contains("longer than 1024 bytes"), error.getMessage());
        assertTrue(seconds(start) < 1.0, "took " + seconds(start));

        String biggest = "12.5" + "0".repeat(1020);
        assertEquals(client(server(answer(bytes(biggest))), MEASURE).measure(), 12.5, 0.0);
        int tooBig = server(answer(bytes(biggest + "0")));
        error = expectThrows(AmmeterResponseException.class, () -> client(tooBig, MEASURE).measure());
        assertTrue(error.getMessage().contains("longer than"), error.getMessage());
    }

    private static Handler counting(Handler handler, AtomicInteger connections) {
        return conn -> {
            connections.incrementAndGet();
            handler.handle(conn);
        };
    }

    private static void halfThenReset(java.net.Socket conn) throws IOException, InterruptedException {
        conn.getInputStream().read(new byte[1024]);
        conn.getOutputStream().write(bytes("12"));
        conn.getOutputStream().flush();
        Thread.sleep(100);
        TestSupport.reset(conn);
    }

    private static Handler resetAfter(double seconds) {
        return conn -> {
            conn.getInputStream().read(new byte[1024]);
            Thread.sleep((long) (seconds * 1000));
            TestSupport.reset(conn);
        };
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    /** Only connection errors are retried, within the one timeout; half an answer is never used. */
    @Test
    public void onlyConnectionErrorsAreRetried() throws Exception {
        assertEquals(client(server(TestSupport::reset, answer(bytes("2.5"))), MEASURE, 2.0, 1).measure(), 2.5, 0.0);
        int resetting = server(TestSupport::reset);
        expectThrows(AmmeterConnectionException.class, () -> client(resetting, MEASURE, 2.0, 0).measure());
        int half = server(AmmeterClientTest::halfThenReset); // half an answer is not used
        expectThrows(AmmeterConnectionException.class, () -> client(half, M, 2.0, 0).measure());

        for (int retries : new int[] {0, 2}) {
            AtomicInteger connections = new AtomicInteger();
            int port = server(counting(TestSupport::reset, connections));
            expectThrows(AmmeterConnectionException.class, () -> client(port, M, 2.0, retries).measure());
            assertEquals(connections.get(), retries + 1);
        }
        int many = server(TestSupport::reset);
        long start = System.nanoTime();
        expectThrows(AmmeterConnectionException.class, () -> client(many, M, 2.0, 5).measure());
        assertTrue(seconds(start) < 1.0, "took " + seconds(start)); // short pauses between attempts

        int late = server(resetAfter(0.3));
        start = System.nanoTime();
        expectThrows(AmmeterException.class, () -> client(late, M, 0.5, 5).measure());
        assertTrue(seconds(start) < 0.5 + 0.25, "took " + seconds(start)); // the retries share the timeout

        int slow = server(answer(bytes("1.0"), 2.0));
        start = System.nanoTime();
        expectThrows(AmmeterTimeoutException.class, () -> client(slow, M, 0.5, 1).measure());
        assertTrue(seconds(start) < 0.85, "took " + seconds(start)); // a timeout is not retried
        int garbage = server(answer(bytes("garbage")), answer(bytes("1.5")));
        expectThrows(AmmeterResponseException.class, () -> client(garbage, M, 2.0, 1).measure()); // nor is a bad answer
    }
}
