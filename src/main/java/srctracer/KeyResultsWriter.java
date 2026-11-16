package srctracer;

import srctracer.tools.KeY;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes one line per verified corpus entry to {@code key-results.csv}:
 * {@code entry;status;open_goals;automode_ms;nodes;elapsed_ms;timestamp_ms}, where {@code elapsed_ms} is measured
 * from the start of the fuzz run and {@code timestamp_ms} is the wall-clock time (epoch millis) the line was written.
 * The file is flushed after every line so partial runs remain usable. Thread-safe, the KeY proofs run in parallel.
 */
final class KeyResultsWriter implements AutoCloseable {

    static final String FILE_NAME = "key-results.csv";

    /** Status of a corpus entry that does not call the traced method (nothing to verify). */
    static final String NOT_REACHED = "not_reached";

    /** Status of a corpus entry that could not be traced or annotated. */
    static final String PREPARE_ERROR = "prepare_error";

    private final BufferedWriter writer;
    private final long startNanos = System.nanoTime();

    KeyResultsWriter(Path file) throws IOException {
        writer = Files.newBufferedWriter(file);
        writer.write("entry;status;open_goals;automode_ms;nodes;elapsed_ms;timestamp_ms");
        writer.newLine();
        writer.flush();
    }

    synchronized void write(String entry, String status, KeY.ProofResult result) throws IOException {
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        Integer openGoals = result == null ? null : result.openGoals();
        Long automodeMillis = result == null ? null : result.automodeMillis();
        Long nodes = result == null ? null : result.nodes();
        writer.write(entry + ";" + status + ";"
                + (openGoals == null ? "" : openGoals) + ";"
                + (automodeMillis == null ? "" : automodeMillis) + ";"
                + (nodes == null ? "" : nodes) + ";"
                + elapsedMillis + ";"
                + System.currentTimeMillis());
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
