package ammeter.emulators;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The supplied minimal client: one request, one answer. */
public final class SimpleClient {
    private SimpleClient() {
    }

    public static Double requestCurrentFromAmmeter(int port, byte[] command, double timeout) throws IOException {
        try (Socket s = new Socket()) {
            int timeoutMs = (int) Math.max(1, timeout * 1000);
            s.setSoTimeout(timeoutMs);
            s.connect(new InetSocketAddress("localhost", port), timeoutMs);
            s.getOutputStream().write(command);
            s.getOutputStream().flush();
            byte[] data = readOnce(s.getInputStream());
            if (data.length > 0) {
                String text = new String(data, StandardCharsets.UTF_8);
                System.out.println("Received current measurement from port " + port + ": " + text + " A");
                return Double.parseDouble(text.strip());
            }
            System.out.println("No data received.");
            return null;
        }
    }

    public static Double requestCurrentFromAmmeter(int port, byte[] command) throws IOException {
        return requestCurrentFromAmmeter(port, command, 5.0);
    }

    private static byte[] readOnce(InputStream in) throws IOException {
        byte[] buffer = new byte[1024];
        int n = in.read(buffer);
        return n <= 0 ? new byte[0] : Arrays.copyOf(buffer, n);
    }
}
