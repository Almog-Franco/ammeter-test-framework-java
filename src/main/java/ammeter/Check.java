package ammeter;

/** One acceptance check. A skipped check counts as passed. */
public record Check(String name, boolean passed, boolean skipped, String detail) {

    public static Check of(String name, boolean passed, String detail) {
        return new Check(name, passed, false, detail);
    }

    public static Check skip(String name, String detail) {
        return new Check(name, true, true, "skipped: " + detail);
    }
}
