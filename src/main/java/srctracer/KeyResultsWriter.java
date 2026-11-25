package srctracer;

import srctracer.tools.KeY;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * key-results.csv: {@code entry;status;open_goals;automode_ms;nodes;timestamp_ms}, one line per corpus entry with the
 * result of its proof (per-trace) or {@link #TRACED} (multi-trace), {@link #NOT_REACHED} or {@link #PREPARE_ERROR};
 * multi-trace adds a last line {@link #ALL_TRACES} with the result of the proof over all traces. A proof status is
 * the lower-case {@link KeY.ProofStatus}. Flushed after every line; thread-safe.
 */
final class KeyResultsWriter implements AutoCloseable {

    static final String ALL_TRACES = "traces";
    static final String TRACED = "traced";
    static final String NOT_REACHED = "not_reached";
    static final String PREPARE_ERROR = "prepare_error";

    private final BufferedWriter writer;

    KeyResultsWriter(Path outputDir) throws IOException {
        writer = Files.newBufferedWriter(outputDir.resolve("key-results.csv"));
        writeLine("entry;status;open_goals;automode_ms;nodes;timestamp_ms");
    }

    void write(String entry, String status) throws IOException {
        writeLine(entry + ";" + status + ";;;;" + System.currentTimeMillis());
    }

    void write(String entry, KeY.ProofResult result) throws IOException {
        writeLine(String.join(";", entry, result.status().name().toLowerCase(), str(result.openGoals()),
                str(result.automodeMillis()), str(result.nodes()), String.valueOf(System.currentTimeMillis())));
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private synchronized void writeLine(String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
