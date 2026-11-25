package srctracer;

import srctracer.tools.Jazzer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * The fuzz command. Jazzer fuzzes the harness ({@code fuzzerTestOneInput}), or --corpus replays an existing corpus.
 * With the key oracle, every corpus entry is traced from the first call of the traced method on, and the trace is
 * verified with KeY ({@link TraceVerifier}). The traced method is the one method outside the harness with a JML
 * contract (or --start-method); if the harness does not declare it, the harness is neither traced nor given to KeY.
 * <p>
 * Output: corpus/, jazzer.log and Jazzer's findings (crash-*, timeout-*, oom-*), replay-results.csv (--corpus),
 * key-results.csv, traces and KeY inputs (key oracle).
 */
final class FuzzCommand {

    static final String FUZZ_TARGET_METHOD = "fuzzerTestOneInput";

    /** Per-input timeout of a --corpus replay without --jazzer-timeout (libFuzzer's default). */
    private static final int DEFAULT_REPLAY_TIMEOUT_SECONDS = 1200;

    record Options(FuzzOracle oracle, boolean multiTrace, Path corpus, int duration, Long seed, Integer jazzerTimeout,
                   Integer maxLen, Integer keyTimeout, String keyMemory, int keyJobs, boolean stopOnBug) {

        static final class Builder {
            FuzzOracle oracle = FuzzOracle.KEY;
            boolean multiTrace = true;
            Path corpus;
            int duration = 15;
            Long seed;
            Integer jazzerTimeout;
            Integer maxLen;
            Integer keyTimeout;
            String keyMemory = "8g";
            int keyJobs = 1;
            boolean stopOnBug;

            Options build() {
                if (keyJobs < 1) throw new IllegalArgumentException("--key-jobs must be at least 1");
                if (corpus != null && !Files.isDirectory(corpus)) {
                    throw new IllegalArgumentException("--corpus: not a directory: " + corpus);
                }
                return new Options(oracle, multiTrace, corpus, duration, seed, jazzerTimeout, maxLen, keyTimeout,
                        keyMemory, keyJobs, stopOnBug);
            }
        }
    }

    private final Main.TraceArgs args;
    private final Options options;
    private final Path runtimeJar;
    private final String className;
    private final Path corpusDir;

    /** The uninstrumented harness, run by Jazzer and the replay. */
    private Path harnessClassDir;

    // key oracle only
    private Path tracedClassDir;
    private KeyResultsWriter keyResults;
    private TraceVerifier verifier;

    /** Jazzer stopped at a finding with --stop-on-bug; the remaining proofs are skipped. */
    private boolean jazzerFinding = false;

    private FuzzCommand(Main.TraceArgs args) {
        this.args = args;
        this.options = args.fuzz();
        this.runtimeJar = Main.resolveRuntimeJar(args.binary());
        this.className = args.sources().entryClassName();
        this.corpusDir = args.output().resolve("corpus");
    }

    static void run(Main.TraceArgs args) throws Exception {
        new FuzzCommand(args).run();
    }

    private void run() throws Exception {
        ProgramSources harness = options.oracle() == FuzzOracle.RUNTIME
                ? args.sources().withAssertVariant()
                : args.sources();
        harnessClassDir = Main.compileToTempDir(Main.readAll(harness), runtimeJar);
        Files.createDirectories(corpusDir);
        try {
            if (options.oracle() == FuzzOracle.KEY) prepareVerification();
            int inputs = options.corpus() != null ? replayCorpus() : fuzz();
            if (verifier != null) {
                if (jazzerFinding) {
                    verifier.stop();
                } else {
                    verifier.finish();
                }
            }
            System.out.println("Complete. Processed " + inputs + " inputs"
                    + (verifier == null ? "" : ", " + verifier.bugsFound() + " potential bug(s) found")
                    + ". Output in: " + args.output());
        } finally {
            if (verifier != null) verifier.close();
            if (keyResults != null) keyResults.close();
            Main.deleteRecursive(harnessClassDir);
            if (tracedClassDir != null) Main.deleteRecursive(tracedClassDir);
        }
    }

    /** Instruments and compiles the traced files and sets up the verifier. */
    private void prepareVerification() throws Exception {
        ProgramSources sources = args.sources();
        String method = args.startMethodName() != null ? args.startMethodName() : sources.contractMethodName();
        ProgramSources traced = sources.declaringFile(method).equals(sources.entryFile())
                ? sources
                : sources.withoutEntryFile();
        System.out.println("Tracing and verifying " + method + " (files: "
                + traced.files().stream().map(f -> f.relativePath().toString()).toList() + ")");

        // the traced files are instrumented, the harness (if not traced) is compiled as it is
        Map<ProgramSources.SourceFile, String> compiled =
                new LinkedHashMap<>(Main.instrumentAll(args, traced, method, runtimeJar));
        Main.readAll(sources).forEach(compiled::putIfAbsent);
        tracedClassDir = Main.compileToTempDir(compiled, runtimeJar);

        Path functionDb = args.output().resolve(Main.DEFAULT_FUNCTION_DB_NAME);
        TraceVerifier.ProofObligation obligation = (dir, traces) -> {
            Path db = Files.copy(functionDb, dir.resolve(functionDb.getFileName()), REPLACE_EXISTING);
            KeyAnnotater.annotate(traced, dir, traces, db, method, false);
        };
        keyResults = new KeyResultsWriter(args.output());
        verifier = options.multiTrace()
                ? new MultiTraceVerifier(options, args.output(), obligation, keyResults)
                : new PerTraceVerifier(options, args.output(), obligation, keyResults);
        Files.createDirectories(verifier.tracesDir());
    }

    // ---- inputs ----

    /**
     * Runs Jazzer to completion, then traces the corpus entries and hands them to the verifier. Returns the number of corpus entries traced (0 without KeY).
     */
    private int fuzz() throws Exception {
        List<String> jazzerArgs = new ArrayList<>();
        if (options.seed() != null) jazzerArgs.add("-seed=" + options.seed());
        if (options.jazzerTimeout() != null) jazzerArgs.add("-timeout=" + options.jazzerTimeout());
        if (options.maxLen() != null) jazzerArgs.add("-max_len=" + options.maxLen());
        writeDefaultSeeds();

        System.out.println("Starting Jazzer for " + options.duration() + "s (oracle: " + options.oracle().cliName()
                + ") ...");
        Process jazzer = Jazzer.start(className, harnessClassDir, corpusDir, options.duration(), args.output(),
                jazzerArgs);
        try {
            jazzer.waitFor();
        } finally {
            if (jazzer.isAlive()) {
                System.out.println("Stopping Jazzer ...");
                jazzer.destroyForcibly().waitFor();
            }
        }
        System.out.println("Jazzer exited with code " + jazzer.exitValue() + " (log: "
                + args.output().resolve("jazzer.log") + ")");

        if (verifier == null) return 0;
        if (options.stopOnBug() && hasJazzerFinding()) {
            System.out.println("Jazzer reported a finding, skipping tracing and proofs");
            jazzerFinding = true;
            return 0;
        }
        List<Path> entries;
        try (var stream = Files.list(corpusDir)) {
            entries = stream.filter(Files::isRegularFile).sorted().toList();
        }
        System.out.println("Tracing " + entries.size() + " corpus entries ...");
        int traced = 0;
        for (Path entry : entries) {
            if (verifier.stoppedOnBug()) break;
            processEntry(entry);
            traced++;
        }
        return traced;
    }

    /**
     * Replays the corpus (--corpus) in the order Jazzer found the entries (modification time, then name): each entry
     * is copied to corpus/, run once on the harness (outcome in replay-results.csv) and, with KeY, traced and verified.
     * A timeout stops the replay, like Jazzer stops at its first timeout. Returns the number of replayed entries.
     */
    private int replayCorpus() throws Exception {
        List<Path> entries;
        try (var stream = Files.list(options.corpus())) {
            entries = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(FuzzCommand::lastModified)
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();
        }
        int timeoutSeconds = options.jazzerTimeout() != null ? options.jazzerTimeout() : DEFAULT_REPLAY_TIMEOUT_SECONDS;
        System.out.println("Replaying " + entries.size() + " corpus entries from " + options.corpus() + " (oracle: "
                + options.oracle().cliName() + ", timeout per input: " + timeoutSeconds + "s) ...");

        int replayed = 0, failures = 0;
        try (ReplayResultsWriter replayResults = new ReplayResultsWriter(args.output())) {
            for (Path source : entries) {
                if (verifier != null && verifier.stoppedOnBug()) break;
                Path entry = Files.copy(source, corpusDir.resolve(source.getFileName()), REPLACE_EXISTING,
                        COPY_ATTRIBUTES);
                String name = entry.getFileName().toString();

                long start = System.currentTimeMillis();
                ReplayOutcome outcome = replay(entry, timeoutSeconds);
                replayResults.write(name, outcome, System.currentTimeMillis() - start);
                replayed++;
                if (!outcome.ok()) {
                    failures++;
                    System.out.println("  " + name + " fails on the concrete run: " + outcome.status()
                            + (outcome.exception() == null ? "" : " " + outcome.exception()));
                }
                if (outcome.equals(ReplayOutcome.TIMEOUT)) {
                    System.out.println("  Timeout, skipping the remaining " + (entries.size() - replayed) + " entries");
                    break;
                }
                if (verifier != null) processEntry(entry);
            }
        }
        System.out.println(failures + " of " + replayed + " replayed entries fail on the concrete run");
        return replayed;
    }

    /**
     * Runs the uninstrumented harness on the entry in a fresh class loader with assertions enabled (as Jazzer does).
     * The run happens on a daemon thread, which is abandoned after the timeout (it keeps running until the JVM exits).
     */
    private ReplayOutcome replay(Path entry, int timeoutSeconds) throws Exception {
        byte[] input = Files.readAllBytes(entry);
        URLClassLoader cl = Main.createClassLoader(harnessClassDir, runtimeJar);
        cl.setDefaultAssertionStatus(true);
        FutureTask<ReplayOutcome> task = new FutureTask<>(() -> {
            Method target = cl.loadClass(className).getMethod(FUZZ_TARGET_METHOD, byte[].class);
            target.setAccessible(true);
            try {
                target.invoke(null, (Object) input);
                return ReplayOutcome.OK;
            } catch (InvocationTargetException e) {
                return ReplayOutcome.exception(e.getCause());
            }
        });
        Thread thread = new Thread(task, "replay-" + entry.getFileName());
        thread.setDaemon(true);
        thread.start();
        try {
            ReplayOutcome outcome = task.get(timeoutSeconds, TimeUnit.SECONDS);
            cl.close();
            return outcome;
        } catch (TimeoutException e) {
            return ReplayOutcome.TIMEOUT;
        }
    }

    // ---- tracing ----

    /** Traces the corpus entry and passes the trace to the verifier. */
    private void processEntry(Path entry) throws IOException {
        String name = entry.getFileName().toString();
        try {
            Path traceFile = traceEntry(entry);
            if (traceFile == null) {
                keyResults.write(name, KeyResultsWriter.NOT_REACHED);
            } else {
                verifier.add(name, traceFile);
            }
        } catch (Exception e) {
            System.out.println("  Could not prepare corpus entry " + name + ": " + e);
            keyResults.write(name, KeyResultsWriter.PREPARE_ERROR);
        }
    }

    /**
     * Runs the instrumented harness on the entry and writes the trace of the first call of the traced method. Returns
     * null (and writes no trace) if the entry does not reach the method: an empty trace would be a prefix of all others.
     */
    private Path traceEntry(Path entry) throws Exception {
        byte[] input = Files.readAllBytes(entry);
        String name = entry.getFileName().toString();
        System.out.println("Tracing corpus entry " + name + " (" + input.length + " bytes) ...");
        try (URLClassLoader cl = Main.createClassLoader(tracedClassDir, runtimeJar)) {
            Method target = cl.loadClass(className).getMethod(FUZZ_TARGET_METHOD, byte[].class);
            byte[] trace = Main.recordTrace(cl, args.binary(), true, () -> Main.invoke(target, input));
            if (!Main.traceRecorded(cl)) {
                System.out.println("  " + name + " does not reach the traced method, not verified");
                return null;
            }
            return Files.write(verifier.tracesDir().resolve(Main.traceFileName(name, args.binary())), trace);
        }
    }

    // ---- corpus directory ----

    /**
     * With an empty corpus, libFuzzer runs the empty input and starts fuzzing from "\n", but writes neither to the
     * corpus (only inputs with new coverage). If no input adds coverage (e.g. a mutant that disables all loops), the
     * corpus would stay empty and nothing would be verified, so both are written as seeds.
     */
    private void writeDefaultSeeds() throws IOException {
        try (var stream = Files.list(corpusDir)) {
            if (stream.findAny().isPresent()) return;
        }
        Files.write(corpusDir.resolve("seed-empty"), new byte[0]);
        Files.write(corpusDir.resolve("seed-newline"), new byte[]{'\n'});
    }

    private boolean hasJazzerFinding() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(args.output(), "{crash,timeout,oom}-*")) {
            return stream.iterator().hasNext();
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
