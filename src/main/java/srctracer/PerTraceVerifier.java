package srctracer;

import srctracer.tools.KeY;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One KeY proof per trace (in key-inputs/key-input-&lt;entry&gt;/), at most --key-jobs at a time, in the background.
 * Results are written in the order the proofs finish. With --stop-on-bug, the first open proof cancels the pending
 * proofs and kills the running ones; their results are dropped.
 */
final class PerTraceVerifier implements TraceVerifier {
    private final FuzzCommand.Options options;
    private final Path outputDir;
    private final ProofObligation obligation;
    private final KeyResultsWriter results;
    private final ExecutorService executor;
    private final Set<KeY> running = ConcurrentHashMap.newKeySet();
    private final AtomicInteger bugsFound = new AtomicInteger();
    private volatile boolean stopped = false;

    PerTraceVerifier(FuzzCommand.Options options, Path outputDir, ProofObligation obligation, KeyResultsWriter results) {
        this.options = options;
        this.outputDir = outputDir;
        this.obligation = obligation;
        this.results = results;
        this.executor = Executors.newFixedThreadPool(options.keyJobs());
    }

    @Override
    public Path tracesDir() {
        return outputDir.resolve("traces");
    }

    @Override
    public void add(String entry, Path traceFile) throws IOException {
        if (stopped) return;
        Path dir = Files.createDirectories(outputDir.resolve("key-inputs").resolve("key-input-" + entry));
        obligation.create(dir, Files.copy(traceFile, dir.resolve(traceFile.getFileName())));
        try {
            executor.submit(() -> verify(entry, dir.resolve("proof.key")));
        } catch (RejectedExecutionException e) {
            // stopped concurrently
        }
    }

    private void verify(String entry, Path proofFile) {
        KeY key = new KeY(proofFile, options.keyMemory());
        // registered before checking stopped, so stop() either sees the instance or this sees stopped
        running.add(key);
        KeY.ProofResult result;
        try {
            if (stopped) return;
            key.runKey(options.keyTimeout());
            result = key.readProofResult();
        } catch (Exception e) {
            if (!stopped) System.out.println("  Could not run KeY for " + entry + ": " + e);
            result = KEY_ERROR;
        } finally {
            running.remove(key);
        }
        if (stopped) return;

        try {
            results.write(entry, result);
        } catch (IOException e) {
            System.out.println("  Could not write the KeY result of " + entry + ": " + e);
        }
        TraceVerifier.print(entry, result);
        if (result.status() == KeY.ProofStatus.OPEN) {
            bugsFound.incrementAndGet();
            if (options.stopOnBug()) stop();
        }
    }

    @Override
    public void finish() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    @Override
    public void stop() {
        stopped = true;
        executor.shutdownNow();
        running.forEach(KeY::destroy);
    }

    @Override
    public int bugsFound() {
        return bugsFound.get();
    }

    @Override
    public boolean stoppedOnBug() {
        return options.stopOnBug() && bugsFound() > 0;
    }

    @Override
    public void close() throws InterruptedException {
        stop();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }
}
