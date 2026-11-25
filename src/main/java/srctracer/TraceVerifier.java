package srctracer;

import srctracer.tools.KeY;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Verifies the traces of the corpus entries with KeY and writes the results to key-results.csv.
 * {@link PerTraceVerifier}: one proof per trace, in the background while further entries are traced.
 * {@link MultiTraceVerifier}: one proof over all traces after the last entry.
 */
interface TraceVerifier extends AutoCloseable {

    /** Creates the KeY proof obligation (proof.key) in {@code dir} for the trace file or trace directory. */
    interface ProofObligation {
        void create(Path dir, Path traces) throws IOException;
    }

    /** Directory the traces of the corpus entries are written to. */
    Path tracesDir();

    /** Verifies the trace of the corpus entry (or keeps it for the proof over all traces). */
    void add(String entry, Path traceFile) throws IOException;

    /** Called after the last entry: finishes the verification. */
    void finish() throws Exception;

    /** Called after the last entry instead of {@link #finish()}: drops the proofs that did not finish yet. */
    void stop();

    /** Number of proofs with open goals. */
    int bugsFound();

    /** Whether a bug stopped the verification (--stop-on-bug), so no further entries need to be traced. */
    boolean stoppedOnBug();

    @Override
    void close() throws Exception;

    KeY.ProofResult KEY_ERROR = new KeY.ProofResult(KeY.ProofStatus.KEY_ERROR, null, null, null);

    static void print(String proof, KeY.ProofResult result) {
        System.out.println("  " + proof + ": " + switch (result.status()) {
            case CLOSED -> "verified (0 open goals)";
            case OPEN -> ">>> BUG FOUND (open goals > 0)";
            case STEP_LIMIT -> "rule application limit reached (open goals remain, not counted as bug)";
            case KEY_ERROR -> "KeY produced no result";
            case KEY_TIMEOUT -> "KeY timed out";
        });
    }
}
