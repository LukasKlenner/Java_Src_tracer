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
 * Runs the KeY proofs of the corpus entries in the background, at most {@code --key-jobs} KeY processes at a
 * time. Results are written to key-results.csv in the order the proofs finish. With {@code --stop-on-bug}, the
 * first open proof cancels the pending proofs and kills the running KeY processes; their results are discarded.
 */
final class KeyRunner implements AutoCloseable {
    private final Main.TraceArgs args;
    private final KeyResultsWriter keyResults;
    private final ExecutorService executor;
    private final Set<KeY> running = ConcurrentHashMap.newKeySet();
    private final AtomicInteger bugsFound = new AtomicInteger();
    private volatile boolean stopped = false;

    KeyRunner(Main.TraceArgs args, KeyResultsWriter keyResults) {
        this.args = args;
        this.keyResults = keyResults;
        this.executor = Executors.newFixedThreadPool(args.keyJobs());
    }

    int bugsFound() {
        return bugsFound.get();
    }

    /** Whether a bug was found with {@code --stop-on-bug}, i.e. no further entries need to be processed. */
    boolean stoppedOnBug() {
        return args.stopOnBug() && bugsFound() > 0;
    }

    void submit(Path keyDir) {
        if (stopped) return;
        try {
            executor.submit(() -> verify(keyDir));
        } catch (RejectedExecutionException e) {
            // stopped concurrently
        }
    }

    private void verify(Path keyDir) {
        String entryName = keyDir.getFileName().toString().replaceFirst("^key-input-", "");
        Path keyProofFile = keyDir.resolve("proof.key");
        if (stopped || !Files.exists(keyProofFile)) return;

        KeY keyInstance = new KeY(keyProofFile, args.keyMemory());
        // registered before checking stopped, so stop() either sees the instance or verify sees stopped
        running.add(keyInstance);
        KeY.ProofResult result;
        try {
            if (stopped) return;
            keyInstance.runKey(args.keyTimeout());
            if (stopped) return;
            result = keyInstance.readProofResult();
        } catch (Exception e) {
            if (stopped) return;
            System.out.println("  Could not run KeY for " + keyDir.getFileName() + ": " + e);
            result = new KeY.ProofResult(KeY.ProofStatus.KEY_ERROR, null, null, null);
        } finally {
            running.remove(keyInstance);
        }

        try {
            keyResults.write(entryName, result.status().name().toLowerCase(), result);
        } catch (IOException e) {
            System.out.println("  Could not write the KeY result of " + entryName + ": " + e);
        }

        switch (result.status()) {
            case CLOSED -> System.out.println("  " + keyDir.getFileName() + " verified (0 open goals)");
            case OPEN -> {
                System.out.println("  >>> BUG FOUND on path from " + keyDir.getFileName() + " (open goals > 0)");
                bugsFound.incrementAndGet();
                if (args.stopOnBug()) stop();
            }
            case STEP_LIMIT -> System.out.println("  KeY reached the rule application limit for " + keyDir.getFileName()
                    + " (open goals remain, not counted as bug)");
            case KEY_ERROR -> System.out.println("  KeY produced no result for " + keyDir.getFileName());
            case KEY_TIMEOUT -> System.out.println("  KeY timed out for " + keyDir.getFileName());
        }
    }

    /** Waits until all submitted proofs are finished (or {@link #stop()} was called). */
    void awaitAll() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    /** Cancels the pending proofs and kills the running KeY processes. */
    void stop() {
        stopped = true;
        executor.shutdownNow();
        for (KeY keyInstance : running) {
            keyInstance.destroy();
        }
    }

    @Override
    public void close() throws InterruptedException {
        stop();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }
}
