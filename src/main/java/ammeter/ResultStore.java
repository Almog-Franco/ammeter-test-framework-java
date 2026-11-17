package ammeter;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Each run goes to runs/&lt;run_id&gt;/, and index.jsonl keeps one line per run for quick listing. */
public class ResultStore {
    static final List<String> SAMPLE_FIELDS = List.of("index", "time_s", "lag_s", "latency_s", "value", "error");
    private static final List<String> INDEX_KEYS =
            List.of("run_id", "ammeter", "status", "started_at", "count", "mean", "std_dev", "cv", "error_rate");
    private static final Pattern RUN_ID = Pattern.compile("[\\w-]+");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** One line of index.jsonl. */
    public record IndexEntry(String runId, String ammeter, Status status, String startedAt, int count, Double mean,
                             Double stdDev, Double cv, double errorRate) {
    }

    private final Path root;
    private final Path indexPath;

    public ResultStore(Path root) {
        this.root = root;
        this.indexPath = root.resolve("index.jsonl");
    }

    public Path root() {
        return root;
    }

    public Path indexPath() {
        return indexPath;
    }

    /** Sorts by time and is unique, e.g. 20261026T120000Z_greenlee_3f9a1c. */
    public static String newId(String name) {
        return newId(name, Instant.now());
    }

    public static String newId(String name, Instant now) {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return STAMP.format(now) + (name == null || name.isEmpty() ? "" : "_" + name) + "_" + hex;
    }

    /** Writes to a temporary file first, so a crash never leaves half a file behind. */
    static void writeAtomic(Path path, String text) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Path runDir(String runId) {
        if (!RUN_ID.matcher(runId).matches()) { // keeps an id like "../x" inside the archive
            throw new ArchiveException("not a run id: " + Fmt.repr(runId));
        }
        return root.resolve("runs").resolve(runId);
    }

    public Path save(Run run, List<Sample> samples) {
        Path folder = runDir(run.runId());
        try {
            Files.createDirectories(folder);
            writeAtomic(folder.resolve("samples.csv"), samplesCsv(samples));
            writeAtomic(folder.resolve("run.json"), Json.pretty(run));

            Statistics stats = run.statistics();
            IndexEntry entry = new IndexEntry(run.runId(), run.ammeter().name(), run.status(), run.startedAt(),
                    stats.count(), stats.mean(), stats.stdDev(), stats.cv(), run.errorRate());
            Files.writeString(indexPath, Json.compact(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return folder;
    }

    public Path writeFile(String runId, String name, String text) {
        Path path = runDir(runId).resolve(name);
        try {
            writeAtomic(path, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    public Path saveComparison(Comparison comparison, String reportText) {
        Path folder = root.resolve("comparisons").resolve(newId(""));
        try {
            Files.createDirectories(folder.getParent());
            Files.createDirectory(folder);
            writeAtomic(folder.resolve("comparison.json"), Json.pretty(comparison));
            writeAtomic(folder.resolve("comparison.md"), reportText);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return folder;
    }

    /** The runs in the index, oldest first; null filters match everything. Broken index lines are skipped. */
    public List<IndexEntry> listRuns(String ammeter, String status) {
        if (!Files.exists(indexPath)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(indexPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<IndexEntry> runs = new ArrayList<>();
        for (String line : lines) {
            IndexEntry entry = parseIndexLine(line);
            if (entry == null
                    || ammeter != null && !ammeter.isEmpty() && !entry.ammeter().equals(ammeter.toLowerCase())
                    || status != null && !status.isEmpty() && !entry.status().name().equals(status.toUpperCase())) {
                continue;
            }
            runs.add(entry);
        }
        return runs;
    }

    public List<IndexEntry> listRuns() {
        return listRuns(null, null);
    }

    /** The newest run of an ammeter, or null. */
    public Run latest(String ammeter) {
        List<IndexEntry> runs = listRuns(ammeter, null);
        return runs.isEmpty() ? null : load(runs.get(runs.size() - 1).runId());
    }

    public Run load(String runId) {
        Path path = runDir(runId).resolve("run.json");
        if (!Files.isRegularFile(path)) {
            throw new ArchiveException("no run " + Fmt.repr(runId) + " in " + root);
        }
        try {
            return Json.MAPPER.readValue(Files.readAllBytes(path), Run.class);
        } catch (IOException e) {
            throw damaged(runId, path, e);
        }
    }

    public List<Sample> loadSamples(String runId) {
        Path path = runDir(runId).resolve("samples.csv");
        if (!Files.isRegularFile(path)) {
            throw new ArchiveException("no samples for run " + Fmt.repr(runId) + " in " + root);
        }
        try {
            return readSamples(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            throw damaged(runId, path, e);
        }
    }

    private static ArchiveException damaged(String runId, Path path, Exception e) {
        String reason = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse("");
        return new ArchiveException("run " + Fmt.repr(runId) + " is damaged: " + path + " can't be read (" + reason + ")");
    }

    private static IndexEntry parseIndexLine(String line) {
        try {
            JsonNode node = Json.MAPPER.readTree(line);
            if (node == null || !node.isObject() || INDEX_KEYS.stream().anyMatch(k -> !node.has(k))) {
                return null;
            }
            return Json.MAPPER.treeToValue(node, IndexEntry.class);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String samplesCsv(List<Sample> samples) {
        StringBuilder out = new StringBuilder(String.join(",", SAMPLE_FIELDS)).append('\n');
        for (Sample s : samples) {
            out.append(s.index()).append(',').append(Fmt.repr(s.timeS())).append(',').append(Fmt.repr(s.lagS()))
                    .append(',').append(Fmt.repr(s.latencyS())).append(',')
                    .append(s.value() == null ? "" : Fmt.repr(s.value())).append(',')
                    .append(s.error() == null ? "" : csvField(s.error())).append('\n');
        }
        return out.toString();
    }

    private static String csvField(String text) {
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private static List<Sample> readSamples(String text) {
        List<List<String>> rows = parseCsv(text);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            columns.putIfAbsent(header.get(i), i);
        }
        for (String field : SAMPLE_FIELDS) {
            if (!columns.containsKey(field)) {
                throw new IllegalArgumentException("no column '" + field + "'");
            }
        }
        List<Sample> samples = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() == 1 && row.get(0).isEmpty()) {
                continue;
            }
            String value = cell(row, columns, "value");
            String error = cell(row, columns, "error");
            samples.add(new Sample(Integer.parseInt(cell(row, columns, "index")),
                    Double.parseDouble(cell(row, columns, "time_s")), Double.parseDouble(cell(row, columns, "lag_s")),
                    Double.parseDouble(cell(row, columns, "latency_s")),
                    value.isEmpty() ? null : Double.parseDouble(value), error.isEmpty() ? null : error));
        }
        return samples;
    }

    private static String cell(List<String> row, Map<String, Integer> columns, String field) {
        int i = columns.get(field);
        return i < row.size() ? row.get(i) : "";
    }

    /** Comma-separated rows; a field in double quotes may hold commas, quotes ("") and line breaks. */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
