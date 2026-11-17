package ammeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Same interface for every ammeter type: one TCP connection per reading, which is how the emulators work.
 *
 * <p>{@code timeoutS} limits the whole reading (connect, send, the complete answer and any retries), not each network
 * call, so an ammeter that dribbles its answer byte by byte, or keeps dropping the connection, can't keep the client
 * waiting.
 */
public class AmmeterClient {
    /** A reading is a short number; anything longer is not a valid answer. */
    public static final int MAX_RESPONSE_BYTES = 1024;
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private final String host;
    private final int port;
    private final byte[] command;
    private final double timeoutS;
    private final int retries;

    public AmmeterClient(String host, int port, byte[] command, double timeoutS, int retries) {
        this.host = host;
        this.port = port;
        this.command = command.clone();
        this.timeoutS = timeoutS;
        this.retries = retries;
    }

    public AmmeterClient(String host, int port, byte[] command) {
        this(host, port, command, 2.0, 1);
    }

    public static AmmeterClient fromSpec(AmmeterSpec spec, double timeoutS, int retries) {
        return new AmmeterClient(spec.host(), spec.port(), spec.command().getBytes(StandardCharsets.UTF_8), timeoutS, retries);
    }

    public static AmmeterClient fromSpec(AmmeterSpec spec) {
        return fromSpec(spec, 2.0, 1);
    }

    public static double parseReading(byte[] data) throws AmmeterResponseException {
        String text = new String(data, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            throw new AmmeterResponseException("empty response (unknown command, or the ammeter closed the connection)");
        }
        if (!NUMBER.matcher(text).matches()) {
            String word = text.toLowerCase().replaceFirst("^[+-]+", "");
            if (word.equals("nan") || word.equals("inf") || word.equals("infinity")) {
                throw new AmmeterResponseException("not a finite number: " + Fmt.repr(text));
            }
            int end = text.offsetByCodePoints(0, Math.min(40, text.codePointCount(0, text.length())));
            throw new AmmeterResponseException("not a number: " + Fmt.repr(text.substring(0, end)));
        }
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value)) {
            throw new AmmeterResponseException("not a finite number: " + Fmt.repr(text));
        }
        return value;
    }

    public double measure() throws AmmeterException, InterruptedException {
        long deadline = System.nanoTime() + (long) (timeoutS * 1e9); // retries share it, so a reading never takes longer
        for (int attempt = 0; ; attempt++) {
            try {
                return request(deadline);
            } catch (AmmeterConnectionException e) {
                if (attempt >= retries || System.nanoTime() + 50_000_000L >= deadline) {
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    private double request(long deadline) throws AmmeterException {
        String address = host + ":" + port;
        Socket conn;
        try {
            conn = Sockets.connect(host, port, (int) Math.max(Math.ceil((deadline - System.nanoTime()) / 1e6), 10));
        } catch (IOException e) { // a timeout, too: on Windows a refused connection only fails after about 2 s
            throw new AmmeterConnectionException("cannot reach " + address + ": " + reason(e));
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (conn) {
            conn.setSoTimeout(remainingMs(deadline));
            conn.getOutputStream().write(command);
            conn.getOutputStream().flush();
            InputStream in = conn.getInputStream();
            byte[] chunk = new byte[1024];
            while (true) {
                conn.setSoTimeout(remainingMs(deadline));
                int n = in.read(chunk);
                if (n < 0) {
                    break;
                }
                data.write(chunk, 0, n);
                if (data.size() > MAX_RESPONSE_BYTES) {
                    throw new AmmeterResponseException("answer from " + address + " is longer than " + MAX_RESPONSE_BYTES + " bytes");
                }
            }
        } catch (SocketTimeoutException e) {
            throw new AmmeterTimeoutException("no complete answer from " + address + " within " + Fmt.g(timeoutS) + " s");
        } catch (IOException e) {
            throw new AmmeterConnectionException("cannot reach " + address + ": " + reason(e));
        }
        return parseReading(data.toByteArray());
    }

    /** Socket timeouts are whole milliseconds and 0 means "wait forever", so a passed deadline is a timeout now. */
    private static int remainingMs(long deadline) throws SocketTimeoutException {
        long ms = (long) Math.ceil((deadline - System.nanoTime()) / 1e6);
        if (ms <= 0) {
            throw new SocketTimeoutException("timed out");
        }
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }

    private static String reason(IOException e) {
        if (e instanceof UnknownHostException) {
            return "unknown host " + e.getMessage();
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
