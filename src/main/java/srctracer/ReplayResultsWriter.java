package srctracer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * replay-results.csv: {@code index;entry;status;exception;run_ms;timestamp_ms}, one line per replayed corpus entry in
 * replay order (see {@link ReplayOutcome}). Flushed after every line.
 */
final class ReplayResultsWriter implements AutoCloseable {

    private final BufferedWriter writer;
    private int index = 0;

    ReplayResultsWriter(Path outputDir) throws IOException {
        writer = Files.newBufferedWriter(outputDir.resolve("replay-results.csv"));
        writeLine("index;entry;status;exception;run_ms;timestamp_ms");
    }

    void write(String entry, ReplayOutcome outcome, long runMillis) throws IOException {
        writeLine(String.join(";", String.valueOf(index++), entry, outcome.status(),
                outcome.exception() == null ? "" : outcome.exception(), String.valueOf(runMillis),
                String.valueOf(System.currentTimeMillis())));
    }

    private void writeLine(String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
