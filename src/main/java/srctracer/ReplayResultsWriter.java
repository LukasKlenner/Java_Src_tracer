package srctracer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes one line per replayed corpus entry to {@code replay-results.csv}:
 * {@code index;entry;status;exception;run_ms;timestamp_ms}, where {@code index} is the position of the entry in
 * the replay order and {@code timestamp_ms} the wall-clock time (epoch millis) the line was written.
 */
final class ReplayResultsWriter implements AutoCloseable {

    static final String FILE_NAME = "replay-results.csv";

    private final BufferedWriter writer;

    ReplayResultsWriter(Path file) throws IOException {
        writer = Files.newBufferedWriter(file);
        writer.write("index;entry;status;exception;run_ms;timestamp_ms");
        writer.newLine();
        writer.flush();
    }

    void write(int index, String entry, ReplayOutcome outcome, long runMillis) throws IOException {
        writer.write(index + ";" + entry + ";" + outcome.status() + ";"
                + (outcome.exception() == null ? "" : outcome.exception()) + ";"
                + runMillis + ";" + System.currentTimeMillis());
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
