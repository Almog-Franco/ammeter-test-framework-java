package ammeter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Writes one test's log file; errors also go to the console. */
public final class TestLogger implements AutoCloseable {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

    private final Path logFile;
    private Writer writer;

    public TestLogger(String testName, Path logDir) {
        try {
            Files.createDirectories(logDir);
            logFile = logDir.resolve(LocalDateTime.now().format(FILE_TIME) + "_" + testName + ".log");
            writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public TestLogger(String testName) {
        this(testName, Path.of("results", "logs"));
    }

    public Path logFile() {
        return logFile;
    }

    public void info(String message) {
        write("INFO", message);
    }

    public void error(String message) {
        write("ERROR", message);
    }

    public void debug(String message) {
        write("DEBUG", message);
    }

    public void warning(String message) {
        write("WARNING", message);
    }

    private synchronized void write(String level, String message) {
        String line = LocalDateTime.now().format(LINE_TIME) + " " + String.format("%-7s", level) + " " + message;
        if (level.equals("ERROR")) {
            System.err.println(line);
        }
        if (writer == null) {
            return;
        }
        try {
            writer.write(line + System.lineSeparator());
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                writer = null;
            }
        }
    }
}
