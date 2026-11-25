package srctracer;

import srctracer.tools.KeY;

import java.io.IOException;
import java.nio.file.Path;

/**
 * One KeY proof over the traces of all corpus entries (in key-input/), after the last entry. KeY loads the trace
 * directory as a prefix tree, so the proof splits only where the traces diverge. Open goals mean that some trace has
 * a bug, but not which one.
 */
final class MultiTraceVerifier implements TraceVerifier {
    private final FuzzCommand.Options options;
    private final Path keyDir;
    private final ProofObligation obligation;
    private final KeyResultsWriter results;
    private int traces = 0;
    private KeY.ProofResult result;

    MultiTraceVerifier(FuzzCommand.Options options, Path outputDir, ProofObligation obligation,
                       KeyResultsWriter results) {
        this.options = options;
        this.keyDir = outputDir.resolve("key-input");
        this.obligation = obligation;
        this.results = results;
    }

    @Override
    public Path tracesDir() {
        return keyDir.resolve("traces");
    }

    @Override
    public void add(String entry, Path traceFile) throws IOException {
        traces++;
        results.write(entry, KeyResultsWriter.TRACED);
    }

    @Override
    public void finish() throws IOException {
        if (traces == 0) {
            System.out.println("No corpus entry reaches the traced method, nothing to verify");
            return;
        }
        obligation.create(keyDir, tracesDir());
        KeY key = new KeY(keyDir.resolve("proof.key"), options.keyMemory());
        try {
            key.runKey(options.keyTimeout());
            result = key.readProofResult();
        } catch (Exception e) {
            System.out.println("  Could not run KeY: " + e);
            result = KEY_ERROR;
        }
        results.write(KeyResultsWriter.ALL_TRACES, result);
        TraceVerifier.print("proof over " + traces + " traces", result);
    }

    @Override
    public void stop() {
    }

    @Override
    public int bugsFound() {
        return result != null && result.status() == KeY.ProofStatus.OPEN ? 1 : 0;
    }

    @Override
    public boolean stoppedOnBug() {
        return false;
    }

    @Override
    public void close() {
    }
}
