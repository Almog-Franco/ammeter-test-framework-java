package ammeter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** How the framework opens TCP connections. Tests replace it to simulate slow networks. */
public final class Sockets {
    @FunctionalInterface
    public interface Connector {
        Socket connect(String host, int port, int timeoutMs) throws IOException;
    }

    public static final Connector DIRECT = (host, port, timeoutMs) -> {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    };

    private static volatile Connector connector = DIRECT;

    private Sockets() {
    }

    public static Socket connect(String host, int port, int timeoutMs) throws IOException {
        return connector.connect(host, port, timeoutMs);
    }

    /** Replaces the connector; pass {@link #DIRECT} to restore it. */
    public static void setConnector(Connector replacement) {
        connector = replacement;
    }
}
