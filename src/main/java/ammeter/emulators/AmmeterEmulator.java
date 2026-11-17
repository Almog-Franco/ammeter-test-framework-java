package ammeter.emulators;

import ammeter.Fmt;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** An ammeter emulator: a TCP server on localhost that answers its measurement command with a reading. */
public abstract class AmmeterEmulator {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    protected final int port;

    protected AmmeterEmulator(int port) {
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    /** The exact bytes a client must send to get a reading. */
    public abstract byte[] getCurrentCommand();

    public abstract double measureCurrent();

    /** The text sent back for one request; nothing is sent when it is empty. */
    protected String reply() {
        return Fmt.repr(measureCurrent());
    }

    /**
     * Starts the server to listen for client requests. The server runs until its thread dies, handling one client
     * request at a time. Only a client that drops the connection is survived; any other error stops the server.
     */
    public void startServer() {
        try (ServerSocket server = new ServerSocket()) {
            if (!WINDOWS) {
                // Without this a quick restart fails with "Address already in use" (Windows doesn't need it)
                server.setReuseAddress(true);
            }
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            System.out.println(getClass().getSimpleName() + " is running on port " + port);
            while (true) {
                Socket conn = server.accept();
                handle(conn);
            }
        } catch (IOException e) {
            throw new IllegalStateException(getClass().getSimpleName() + " on port " + port + " stopped: " + e, e);
        }
    }

    private void handle(Socket conn) {
        try (conn) {
            System.out.println("Connected by ('" + conn.getInetAddress().getHostAddress() + "', " + conn.getPort() + ")");
            byte[] data = readOnce(conn.getInputStream());
            if (Arrays.equals(data, getCurrentCommand())) {
                String current = reply();
                conn.getOutputStream().write(current.getBytes(StandardCharsets.UTF_8));
                conn.getOutputStream().flush();
            }
        } catch (IOException e) {
            // A client that resets or drops the connection must not stop the emulator
            System.out.println("Connection lost: " + e);
        }
    }

    /** One read of up to 1024 bytes, like {@code socket.recv(1024)}. */
    private static byte[] readOnce(InputStream in) throws IOException {
        byte[] buffer = new byte[1024];
        int n = in.read(buffer);
        return n <= 0 ? new byte[0] : Arrays.copyOf(buffer, n);
    }
}
