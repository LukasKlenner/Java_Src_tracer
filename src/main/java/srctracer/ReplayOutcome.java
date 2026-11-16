package srctracer;

/**
 * Outcome of running the uninstrumented harness on one corpus entry: {@code ok}, {@code exception} (with the
 * class name; {@link AssertionError} for a failed runtime assert) or {@code timeout}.
 */
record ReplayOutcome(String status, String exception) {
    static final String OK = "ok";
    static final String EXCEPTION = "exception";
    static final String TIMEOUT = "timeout";

    boolean ok() {
        return status.equals(OK);
    }
}
