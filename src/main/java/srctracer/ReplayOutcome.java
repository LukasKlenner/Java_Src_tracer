package srctracer;

/**
 * Outcome of running the uninstrumented harness on one corpus entry: {@code ok}, {@code exception} (with the class
 * name; {@link AssertionError} for a failed runtime assert) or {@code timeout}.
 */
record ReplayOutcome(String status, String exception) {
    static final ReplayOutcome OK = new ReplayOutcome("ok", null);
    static final ReplayOutcome TIMEOUT = new ReplayOutcome("timeout", null);

    static ReplayOutcome exception(Throwable t) {
        return new ReplayOutcome("exception", t.getClass().getName());
    }

    boolean ok() {
        return this.equals(OK);
    }
}
