package ammeter;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.JsonNode;

public class ResultStoreTest {
    private static Run run(String runId, String ammeter, Status status) {
        Statistics stats = new Statistics(2, 1.5, null, 0.7, null, null, 0.47, null, null, null, null, null, null);
        return runWith(runId, ammeter, status, stats);
    }

    private static Run run(String runId) {
        return run(runId, "entes", Status.PASS);
    }

    private static Run runWith(String runId, String ammeter, Status status, Statistics stats) {
        return new Run(runId, status, new AmmeterSpec(ammeter, 0, null, null, null, null, null, null, null, null), null,
                "2026-10-26T10:00:00+00:00", null, null, 0, stats, 0, 0.5, null, 0.0, null, null, null, null, null,
                null, null);
    }

    private static List<String> ids(List<ResultStore.IndexEntry> entries) {
        return entries.stream().map(ResultStore.IndexEntry::runId).toList();
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static List<Path> tmpFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    private static void assertArchiveError(String regex, Runnable action) {
        try {
            action.run();
        } catch (ArchiveException e) {
            assertTrue(e.getMessage().matches("(?s).*" + regex + ".*"), "message: " + e.getMessage());
            return;
        }
        throw new AssertionError("no ArchiveException for " + regex);
    }

    @Test
    public void saveAndLoadKeepFullDetail() throws IOException {
        ResultStore store = new ResultStore(TestSupport.tempDir());
        List<Sample> samples = List.of(new Sample(0, 0.0, 0.0, 0.001, 1.5),
                new Sample(1, 0.1, 0.002, 2.0, null, "AmmeterTimeoutException: slow"));
        Path folder = store.save(run("r1"), samples);
        assertEquals(names(folder), List.of("run.json", "samples.csv"));
        assertEquals(Json.tree(store.load("r1")), Json.tree(run("r1")));
        assertEquals(store.loadSamples("r1"), samples);

        Statistics withMedian = new Statistics(2, 1.5, 1.2, 0.7, null, null, 0.47, null, null, null, null, null, null);
        store.save(runWith("r2", "entes", Status.PASS, withMedian),
                List.of(new Sample(0, 0.0, 0.0, 0.001, 0.004912345678912)));
        assertEquals(store.loadSamples("r2").get(0).value(), 0.004912345678912);
        ResultStore.IndexEntry entry = store.listRuns().get(store.listRuns().size() - 1);
        assertEquals(entry.mean(), 1.5);
        assertEquals(entry.stdDev(), 0.7);
        assertEquals(entry.cv(), 0.47);
        assertEquals(entry.count(), 2);
        assertEquals(entry.startedAt(), "2026-10-26T10:00:00+00:00");

        Comparison comparison = new Comparison(List.of(), List.of(), 30, "entes", List.of(), null, null);
        folder = store.saveComparison(comparison, "## Comparison\n");
        // sorts by time, like the runs
        assertTrue(folder.getFileName().toString().matches("\\d{8}T\\d{6}Z_[0-9a-f]{6}"));
        JsonNode saved = Json.MAPPER.readTree(Files.readString(folder.resolve("comparison.json")));
        assertEquals(saved, Json.tree(comparison));
        assertEquals(saved.get("most_reliable").asText(), "entes");
        assertEquals(Files.readString(folder.resolve("comparison.md"), StandardCharsets.UTF_8), "## Comparison\n");
    }

    @Test
    public void listingFiltersExactlyAndSkipsBrokenLines() throws IOException {
        ResultStore store = new ResultStore(TestSupport.tempDir());
        store.save(run("r1", "entes", Status.PASS), List.of());
        store.save(run("r2", "greenlee", Status.FAIL), List.of());
        append(store.indexPath(), "{not json\n");
        append(store.indexPath(), "{\"run_id\": \"half\"}\n");
        append(store.indexPath(), "[1, 2]\n");
        append(store.indexPath(), "{\"run_id\": \"c\", \"ammeter\": \"entes\", \"status\": \"PASS\"}\n"); // incomplete
        store.save(run("r3", "entes", Status.FAIL), List.of());
        store.save(run("r4", "entes_b", Status.PASS), List.of());

        assertEquals(ids(store.listRuns()), List.of("r1", "r2", "r3", "r4"));
        assertEquals(ids(store.listRuns("ENTES", null)), List.of("r1", "r3"));
        assertEquals(ids(store.listRuns(null, "fail")), List.of("r2", "r3"));
        assertEquals(store.listRuns("e", null), List.of());
        assertEquals(store.latest("entes").runId(), "r3");
        assertNull(store.latest("circutor"));
    }

    @Test
    public void runIdsAreUniqueAndSortByTime() {
        List<String> ids = Stream.generate(() -> ResultStore.newId("entes")).limit(100).toList();
        assertEquals(new HashSet<>(ids).size(), 100);
        assertTrue(ids.stream().allMatch(i -> i.matches("\\d{8}T\\d{6}Z_entes_[0-9a-f]{6}")));
        assertEquals(ids.stream().sorted(Comparator.comparing(i -> i.split("_")[0])).toList(), ids);
        Instant stamp = LocalDateTime.parse(ids.get(0).split("_")[0], DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                .toInstant(ZoneOffset.UTC);
        assertTrue(Math.abs(Duration.between(stamp, Instant.now()).toSeconds()) < 5);
        // zero-padded, so 5 October sorts before 26
        assertTrue(ResultStore.newId("entes", Instant.parse("2026-10-05T09:03:07Z")).startsWith("20261005T090307Z_entes_"));
    }

    @Test
    public void unknownDamagedAndUnsafeRunsAreReportedClearly() throws IOException {
        Path tmp = TestSupport.tempDir();
        ResultStore store = new ResultStore(tmp);
        assertArchiveError("no run 'missing'", () -> store.load("missing"));
        assertArchiveError(Pattern.quote(tmp.toString()), () -> store.load("missing"));
        for (String unsafe : List.of("../../etc", "..\\..\\x", "..")) {
            assertArchiveError("not a run id", () -> store.load(unsafe));
        }

        store.save(run("r1"), List.of());
        Path dir = store.runDir("r1");
        Files.writeString(dir.resolve("run.json"), "{half a file", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("samples.csv"), "index,value\n1,abc\n", StandardCharsets.UTF_8);
        assertArchiveError("damaged", () -> store.load("r1"));
        assertArchiveError("damaged", () -> store.loadSamples("r1"));
        Files.write(dir.resolve("run.json"), new byte[] {'{', '"', 'r', 'u', 'n', '_', 'i', 'd', '"', ':', ' ', '"',
            (byte) 0xff, '"', '}'}); // not UTF-8
        assertArchiveError("damaged", () -> store.load("r1"));
        assertArchiveError("no samples for run 'missing'", () -> store.loadSamples("missing"));
        Files.delete(dir.resolve("run.json")); // as if a crash happened while saving
        assertArchiveError("no run 'r1'", () -> store.load("r1"));
    }

    @Test
    public void writesAreAtomicAndFailuresAreNotHidden() throws IOException {
        Path tmp = TestSupport.tempDir();
        ResultStore store = new ResultStore(tmp);
        Path folder = store.save(run("ok"), List.of());
        Path comparisonFolder = store.saveComparison(
                new Comparison(List.of(), List.of(), 30, "entes", List.of(), null, null), "## Comparison\n");
        // every file was renamed from its own temporary file, so no temporary file is left
        assertEquals(names(folder), List.of("run.json", "samples.csv"));
        assertEquals(names(comparisonFolder), List.of("comparison.json", "comparison.md"));
        assertEquals(tmpFiles(tmp), List.of());
        List<String> lines = Files.readAllLines(store.indexPath(), StandardCharsets.UTF_8);
        assertEquals(lines.size(), 1);
        assertEquals(Json.MAPPER.readTree(lines.get(0)).get("run_id").asText(), "ok");

        // a rewrite replaces the file whole
        ResultStore.writeAtomic(folder.resolve("note.txt"), "old");
        ResultStore.writeAtomic(folder.resolve("note.txt"), "new");
        assertEquals(Files.readString(folder.resolve("note.txt")), "new");
        assertEquals(tmpFiles(tmp), List.of());

        // the "disk" fails: a regular file sits where the folders must go
        Path blocked = TestSupport.tempDir();
        ResultStore full = new ResultStore(blocked);
        Files.writeString(blocked.resolve("runs"), "not a folder");
        Files.writeString(blocked.resolve("comparisons"), "not a folder");
        assertThrows(UncheckedIOException.class, () -> full.save(run("lost"), List.of()));
        assertThrows(UncheckedIOException.class, () -> full.saveComparison(
                new Comparison(List.of(), List.of(), 30, null, List.of(), "x", null), ""));
        assertEquals(full.listRuns(), List.of());
        assertEquals(tmpFiles(blocked), List.of());

        // a failure after earlier runs were saved leaves the index as it was
        Path dir = store.runDir("lost");
        Files.createDirectories(dir.getParent());
        Files.createDirectories(dir.resolve("samples.csv").resolve("in the way"));
        assertThrows(UncheckedIOException.class, () -> store.save(run("lost"), List.of()));
        assertEquals(ids(store.listRuns()), List.of("ok"));
        assertEquals(store.latest("entes").runId(), "ok");
    }
}
