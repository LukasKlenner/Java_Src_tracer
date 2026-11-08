package srctracer;

import srctracer.tools.Jazzer;
import srctracer.tools.KeY;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

class FuzzCommand {

    static final String FUZZ_TARGET_METHOD = "fuzzerTestOneInput";

    static final String KEY_RESULTS_FILE = "key-results.csv";

    /** key-results.csv status of a corpus entry that does not call the traced method (nothing to verify). */
    static final String NOT_REACHED = "not_reached";

    static final String REPLAY_RESULTS_FILE = "replay-results.csv";

    /** File in a KeY input directory with the number of events of its trace (written for key-results.csv). */
    private static final String TRACE_EVENTS_FILE = "trace-events.txt";

    /** Per-input timeout of a --corpus replay without --jazzer-timeout (libFuzzer's default). */
    private static final int DEFAULT_REPLAY_TIMEOUT_SECONDS = 1200;

    static void run(Main.TraceArgs args) throws Exception {

        Path runtimeJar = Main.resolveRuntimeJar(args.binary());
        String className = args.sources().entryClassName();
        boolean verifyWithKey = args.inputSizesFile() != null || args.oracle().verifyWithKey();

        Path functionDbFile = args.output().resolve(Main.DEFAULT_FUNCTION_DB_NAME);
        Path instrumentedCompileDir = null;
        TraceTarget target = null;
        if (verifyWithKey) {
            target = traceTarget(args);
            System.out.println("Tracing and verifying " + target.methodName() + " (files: "
                    + target.sources().files().stream().map(f -> f.relativePath().toString()).toList() + ")");
            // the traced files are instrumented, the harness (if not traced) is compiled as it is
            Map<ProgramSources.SourceFile, String> compiled = new LinkedHashMap<>(
                    Main.instrumentAll(args, target.sources(), target.methodName(), runtimeJar));
            for (var entry : Main.readAll(args.sources()).entrySet()) {
                compiled.putIfAbsent(entry.getKey(), entry.getValue());
            }
            instrumentedCompileDir = Files.createTempDirectory("srctracer-inst-");
            Main.compile(compiled, instrumentedCompileDir, runtimeJar);
        }

        Path fuzzerCompileDir = null;
        if (args.inputSizesFile() == null) {
            ProgramSources fuzzerSources = args.oracle().fuzzAssertVariant()
                    ? args.sources().withAssertVariant()
                    : args.sources();
            fuzzerCompileDir = Files.createTempDirectory("srctracer-fuzz-");
            Main.compile(Main.readAll(fuzzerSources), fuzzerCompileDir, runtimeJar);
        }

        Path corpusDir = args.output().resolve("corpus");
        Files.createDirectories(corpusDir);

        Path tracesDir = args.output().resolve("traces");
        Path keyInputsDir = args.output().resolve("key-inputs");
        KeyResultsWriter keyResults = null;
        KeyRunner keyRunner = null;
        if (verifyWithKey) {
            Files.createDirectories(tracesDir);
            Files.createDirectories(keyInputsDir);
            keyResults = new KeyResultsWriter(args.output().resolve(KEY_RESULTS_FILE));
            keyRunner = new KeyRunner(args, keyResults);
        }

        int[] result;

        try {
            if (args.inputSizesFile() != null) {
                result = runWithInputSizes(args, className, target, instrumentedCompileDir, runtimeJar,
                        corpusDir, tracesDir, keyInputsDir, functionDbFile, keyResults, keyRunner);
            } else if (args.replayCorpus() != null) {
                result = runWithCorpus(args, className, target, fuzzerCompileDir, instrumentedCompileDir, runtimeJar,
                        corpusDir, tracesDir, keyInputsDir, functionDbFile, keyResults, keyRunner);
            } else {
                result = runWithJazzer(args, className, target, fuzzerCompileDir, instrumentedCompileDir, runtimeJar,
                        corpusDir, tracesDir, keyInputsDir, functionDbFile, keyResults, keyRunner);
            }
        } finally {
            if (keyRunner != null) keyRunner.close();
            if (keyResults != null) keyResults.close();
        }

        if (fuzzerCompileDir != null) Main.deleteRecursive(fuzzerCompileDir);
        if (instrumentedCompileDir != null) Main.deleteRecursive(instrumentedCompileDir);

        int inputCount = result[0], bugsFound = result[1];
        System.out.println("Complete. Processed " + inputCount + " inputs, " + bugsFound + " potential bug(s) found. Output in: " + args.output());
    }

    /**
     * The method that is traced and verified with KeY, and the files that are traced and passed to KeY.
     * By default this is the one method outside the harness with a JML contract (--start-method overrides this).
     * If the method is not declared in the harness file, the harness is neither instrumented nor given to KeY,
     * so the path class is defined over the arguments of the method and the harness may use any Java code.
     */
    record TraceTarget(String methodName, ProgramSources sources) {
    }

    private static TraceTarget traceTarget(Main.TraceArgs args) {
        String methodName = args.startMethodName() != null
                ? args.startMethodName()
                : args.sources().contractMethodName();
        ProgramSources.SourceFile declaringFile = args.sources().declaringFile(methodName);
        ProgramSources traced = declaringFile.equals(args.sources().entryFile())
                ? args.sources()
                : args.sources().withoutEntryFile();
        return new TraceTarget(methodName, traced);
    }

    private static int[] runWithInputSizes(
            Main.TraceArgs args, String className, TraceTarget target, Path instrumentedCompileDir, Path runtimeJar,
            Path corpusDir, Path tracesDir, Path keyInputsDir, Path functionDbFile, KeyResultsWriter keyResults,
            KeyRunner keyRunner
    ) throws Exception {
        String content = Files.readString(args.inputSizesFile()).trim();
        String[] parts = content.split(",");
        int[] sizes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            sizes[i] = Integer.parseInt(parts[i].trim());
        }

        Random rng = new Random(42);

        System.out.println("Running with " + sizes.length + " prepared input sizes ...");

        for (int size : sizes) {
            byte[] inputBytes = new byte[size];
            rng.nextBytes(inputBytes);

            Path inputFile = corpusDir.resolve("input-" + size);
            Files.write(inputFile, inputBytes);

            Path keyDir = prepareCorpusEntry(inputFile, className, target, instrumentedCompileDir, runtimeJar,
                    args.binary(), tracesDir, keyInputsDir, functionDbFile);
            if (keyDir == null) {
                keyResults.write(inputFile.getFileName().toString(), NOT_REACHED, null);
                continue;
            }
            keyRunner.submit(keyDir);
        }
        keyRunner.awaitAll();

        return new int[]{sizes.length, keyRunner.bugsFound()};
    }

    private static int[] runWithJazzer(
            Main.TraceArgs args, String className, TraceTarget target, Path fuzzerCompileDir, Path instrumentedCompileDir,
            Path runtimeJar, Path corpusDir, Path tracesDir, Path keyInputsDir, Path functionDbFile,
            KeyResultsWriter keyResults, KeyRunner keyRunner
    ) throws Exception {
        List<String> jazzerArgs = new ArrayList<>();
        if (args.fuzzSeed() != null) jazzerArgs.add("-seed=" + args.fuzzSeed());
        if (args.jazzerTimeout() != null) jazzerArgs.add("-timeout=" + args.jazzerTimeout());
        if (args.maxLen() != null) jazzerArgs.add("-max_len=" + args.maxLen());

        writeDefaultSeeds(corpusDir);

        System.out.println("Starting Jazzer for " + args.fuzzDuration() + "s (oracle: " + args.oracle().cliName() + ") ...");
        Process jazzerProcess = Jazzer.startJazzerAsync(className, fuzzerCompileDir, corpusDir, args.fuzzDuration(), args.output(),
                args.stopOnBug(), jazzerArgs);

        Set<Path> processedFiles = new HashSet<>();

        try {
            if (!args.oracle().verifyWithKey()) {
                jazzerProcess.waitFor();
            }

            // the KeY proofs run in the background (keyRunner), so new corpus entries are prepared while proving
            boolean changed = args.oracle().verifyWithKey();
            boolean jazzerFinding = false;
            while ((jazzerProcess.isAlive() || changed) && !(args.stopOnBug() && keyRunner != null && keyRunner.bugsFound() > 0)) {
                changed = false;

                if (args.stopOnBug() && !jazzerProcess.isAlive() && hasJazzerFinding(args.output())) {
                    System.out.println("Jazzer reported a finding, skipping remaining corpus entries");
                    jazzerFinding = true;
                    break;
                }

                Thread.sleep(500);

                List<Path> newFiles = findNewCorpusEntries(corpusDir, processedFiles);

                for (Path entry : newFiles) {
                    if (!Files.exists(entry)) continue;

                    processedFiles.add(entry);
                    changed = true;
                    try {
                        Path keyDir = prepareCorpusEntry(
                                entry, className, target, instrumentedCompileDir, runtimeJar,
                                args.binary(), tracesDir, keyInputsDir, functionDbFile);
                        if (keyDir == null) {
                            keyResults.write(entry.getFileName().toString(), NOT_REACHED, null);
                        } else {
                            keyRunner.submit(keyDir);
                        }
                    } catch (Exception e) {
                        // e.g. libFuzzer removed the entry while it was being read
                        System.out.println("  Could not prepare corpus entry " + entry.getFileName() + ": " + e);
                        keyResults.write(entry.getFileName().toString(), "prepare_error", null);
                    }
                }
            }

            if (keyRunner != null) {
                if (jazzerFinding || args.stopOnBug() && keyRunner.bugsFound() > 0) {
                    keyRunner.stop();
                } else {
                    keyRunner.awaitAll();
                }
            }
        } finally {
            if (jazzerProcess.isAlive()) {
                System.out.println("Stopping Jazzer ...");
                jazzerProcess.destroyForcibly();
                jazzerProcess.waitFor();
            }
        }

        int exitCode = jazzerProcess.exitValue();
        System.out.println("Jazzer exited with code " + exitCode + " (log: " + args.output().resolve("jazzer.log") + ")");

        return new int[]{processedFiles.size(), keyRunner == null ? 0 : keyRunner.bugsFound()};
    }

    /**
     * Replays the entries of an existing corpus (--corpus) instead of fuzzing. The entries are copied to the output
     * corpus directory and processed in the order Jazzer found them (modification time, then name). Each entry is run
     * once with the oracle's harness (as Jazzer would run it, see {@link #replay}); the outcome is written to
     * replay-results.csv. With a KeY oracle, the entry is then traced and verified like a new Jazzer corpus entry.
     * An entry that exceeds the per-input timeout stops the replay, like Jazzer stops at its first timeout; the
     * remaining entries are neither replayed nor verified.
     */
    private static int[] runWithCorpus(
            Main.TraceArgs args, String className, TraceTarget target, Path fuzzerCompileDir, Path instrumentedCompileDir,
            Path runtimeJar, Path corpusDir, Path tracesDir, Path keyInputsDir, Path functionDbFile,
            KeyResultsWriter keyResults, KeyRunner keyRunner
    ) throws Exception {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(args.replayCorpus())) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) entries.add(entry);
            }
        }
        Comparator<Path> byModified = Comparator.comparing(p -> {
            try {
                return Files.getLastModifiedTime(p);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        entries.sort(byModified.thenComparing(p -> p.getFileName().toString()));

        int timeoutSeconds = args.jazzerTimeout() != null ? args.jazzerTimeout() : DEFAULT_REPLAY_TIMEOUT_SECONDS;
        System.out.println("Replaying " + entries.size() + " corpus entries from " + args.replayCorpus()
                + " (oracle: " + args.oracle().cliName() + ", timeout per input: " + timeoutSeconds + "s) ...");

        int processed = 0, failures = 0;
        try (ReplayResultsWriter replayResults = new ReplayResultsWriter(args.output().resolve(REPLAY_RESULTS_FILE))) {
            for (Path source : entries) {
                if (args.stopOnBug() && keyRunner != null && keyRunner.bugsFound() > 0) break;

                Path entry = corpusDir.resolve(source.getFileName());
                Files.copy(source, entry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                String entryName = entry.getFileName().toString();

                long startMillis = System.currentTimeMillis();
                ReplayOutcome outcome = replay(entry, className, fuzzerCompileDir, runtimeJar, timeoutSeconds);
                replayResults.write(processed, entryName, outcome, System.currentTimeMillis() - startMillis);
                processed++;
                if (!outcome.ok()) {
                    failures++;
                    System.out.println("  " + entryName + " fails on the concrete run: " + outcome.status()
                            + (outcome.exception() == null ? "" : " " + outcome.exception()));
                }
                if (outcome.status().equals(ReplayOutcome.TIMEOUT)) {
                    System.out.println("  Timeout, skipping the remaining " + (entries.size() - processed) + " entries");
                    break;
                }

                if (keyRunner == null) continue;
                try {
                    Path keyDir = prepareCorpusEntry(entry, className, target, instrumentedCompileDir, runtimeJar,
                            args.binary(), tracesDir, keyInputsDir, functionDbFile);
                    if (keyDir == null) {
                        keyResults.write(entryName, NOT_REACHED, null);
                    } else {
                        keyRunner.submit(keyDir);
                    }
                } catch (Exception e) {
                    System.out.println("  Could not prepare corpus entry " + entryName + ": " + e);
                    keyResults.write(entryName, "prepare_error", null);
                }
            }
        }
        System.out.println(failures + " of " + processed + " replayed entries fail on the concrete run");

        if (keyRunner != null) {
            if (args.stopOnBug() && keyRunner.bugsFound() > 0) {
                keyRunner.stop();
            } else {
                keyRunner.awaitAll();
            }
        }
        return new int[]{processed, keyRunner == null ? 0 : keyRunner.bugsFound()};
    }

    /**
     * Outcome of running the uninstrumented harness on one corpus entry: {@code ok}, {@code exception} (with the
     * class name; {@link AssertionError} for a failed runtime assert) or {@code timeout}.
     */
    record ReplayOutcome(String status, String exception) {
        static final String OK = "ok";
        static final String EXCEPTION = "exception";
        static final String TIMEOUT = "timeout";

        boolean ok() {
            return status.equals(OK);
        }
    }

    /**
     * Runs fuzzerTestOneInput of the uninstrumented harness on the entry in a fresh class loader. The run happens on a
     * daemon thread; after the timeout it is abandoned (it cannot be killed and keeps running until the JVM exits).
     */
    private static ReplayOutcome replay(Path corpusEntry, String className, Path classDir, Path runtimeJar,
                                        int timeoutSeconds) throws Exception {
        byte[] inputBytes = Files.readAllBytes(corpusEntry);
        URLClassLoader cl = Main.createClassLoader(classDir, runtimeJar);
        // Jazzer runs the harness with assertions enabled (runtime oracle)
        cl.setDefaultAssertionStatus(true);
        FutureTask<ReplayOutcome> task = new FutureTask<>(() -> {
            Method fuzzMethod = cl.loadClass(className).getMethod(FUZZ_TARGET_METHOD, byte[].class);
            fuzzMethod.setAccessible(true);
            try {
                fuzzMethod.invoke(null, (Object) inputBytes);
                return new ReplayOutcome(ReplayOutcome.OK, null);
            } catch (InvocationTargetException e) {
                return new ReplayOutcome(ReplayOutcome.EXCEPTION, e.getCause().getClass().getName());
            }
        });
        Thread thread = new Thread(task, "replay-" + corpusEntry.getFileName());
        thread.setDaemon(true);
        thread.start();
        try {
            ReplayOutcome outcome = task.get(timeoutSeconds, TimeUnit.SECONDS);
            cl.close();
            return outcome;
        } catch (TimeoutException e) {
            return new ReplayOutcome(ReplayOutcome.TIMEOUT, null);
        }
    }

    /**
     * Runs the harness on the corpus entry, records the trace of the first invocation of the traced method and
     * creates the KeY input directory. Returns null if the input does not reach the traced method.
     */
    private static Path prepareCorpusEntry(
            Path corpusEntry,
            String className,
            TraceTarget target,
            Path instrumentedClassDir,
            Path runtimeJar,
            boolean binary,
            Path tracesDir,
            Path keyInputsDir,
            Path functionDbFile
    ) throws Exception {
        byte[] inputBytes = Files.readAllBytes(corpusEntry);
        String corpusEntryName = corpusEntry.getFileName().toString();

        System.out.println("Preparing corpus entry " + corpusEntryName + " with " + inputBytes.length + " bytes ...");

        Path traceFile;
        long traceEvents;
        try (URLClassLoader cl = Main.createClassLoader(instrumentedClassDir, runtimeJar)) {
            Class<?> traceClass = cl.loadClass("srctracer.Trace");
            Class<?> userClass = cl.loadClass(className);
            Method fuzzMethod = userClass.getMethod(FUZZ_TARGET_METHOD, byte[].class);
            fuzzMethod.setAccessible(true);

            if (binary) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                traceClass.getMethod("trace_start_on_entry", OutputStream.class).invoke(null, baos);
                invokeFuzzTarget(fuzzMethod, inputBytes);
                traceClass.getMethod("trace_end").invoke(null);

                traceFile = tracesDir.resolve(corpusEntryName + ".trace");
                Files.write(traceFile, baos.toByteArray());
            } else {
                StringWriter sw = new StringWriter();
                traceClass.getMethod("trace_start_on_entry", Writer.class).invoke(null, sw);
                invokeFuzzTarget(fuzzMethod, inputBytes);
                traceClass.getMethod("trace_end").invoke(null);

                traceFile = tracesDir.resolve(corpusEntryName + ".trace.txt");
                Files.writeString(traceFile, sw.toString());
            }

            if (!(boolean) traceClass.getMethod("trace_recorded").invoke(null)) {
                System.out.println("  " + corpusEntryName + " does not reach " + target.methodName() + ", not verified");
                return null;
            }
            traceEvents = (long) traceClass.getMethod("trace_event_count").invoke(null);
        }

        Path keyDir = keyInputsDir.resolve("key-input-" + corpusEntryName);
        Files.createDirectories(keyDir);

        Files.copy(traceFile, keyDir.resolve(traceFile.getFileName()));
        Files.copy(functionDbFile, keyDir.resolve(functionDbFile.getFileName()));

        KeyAnnotater.annotate(target.sources(), keyDir, keyDir.resolve(traceFile.getFileName()),
                keyDir.resolve(functionDbFile.getFileName()), target.methodName(), false);
        Files.writeString(keyDir.resolve(TRACE_EVENTS_FILE), Long.toString(traceEvents));

        return keyDir;
    }

    /**
     * Runs the KeY proofs of the corpus entries in the background, at most {@code --key-jobs} KeY processes at a
     * time. Results are written to key-results.csv in the order the proofs finish. With {@code --stop-on-bug}, the
     * first open proof cancels the pending proofs and kills the running KeY processes; their results are discarded.
     */
    private static final class KeyRunner implements AutoCloseable {
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
            Long traceEvents = readTraceEvents(keyDir);

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
                keyResults.write(entryName, result.status().name().toLowerCase(), result, traceEvents);
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

        private static Long readTraceEvents(Path keyDir) {
            try {
                return Long.parseLong(Files.readString(keyDir.resolve(TRACE_EVENTS_FILE)).trim());
            } catch (IOException | NumberFormatException e) {
                return null;
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

    /**
     * With an empty corpus, libFuzzer runs the empty input and starts fuzzing from the input "\n", but writes neither
     * to the corpus directory; only inputs with new coverage are written. If no input adds coverage (e.g. a mutant
     * that disables all loops), the corpus stays empty and nothing is verified. Writing these two inputs as seeds
     * keeps the fuzzing behaviour and makes them visible to the KeY verification.
     */
    private static void writeDefaultSeeds(Path corpusDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(corpusDir)) {
            if (stream.iterator().hasNext()) return;
        }
        Files.write(corpusDir.resolve("seed-empty"), new byte[0]);
        Files.write(corpusDir.resolve("seed-newline"), new byte[]{'\n'});
    }

    /** Jazzer writes its findings as crash-*, timeout-* and oom-* files into its working directory. */
    private static boolean hasJazzerFinding(Path outputDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir, "{crash,timeout,oom}-*")) {
            return stream.iterator().hasNext();
        }
    }

    private static List<Path> findNewCorpusEntries(Path corpusDir, Set<Path> processed) throws Exception {
        List<Path> newEntries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(corpusDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && !processed.contains(entry)) {
                    newEntries.add(entry);
                }
            }
        }
        newEntries.sort(null);
        return newEntries;
    }

    private static void invokeFuzzTarget(Method fuzzMethod, byte[] input) {
        try {
            fuzzMethod.invoke(null, (Object) input);
        } catch (InvocationTargetException ignored) {
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Writes one line per replayed corpus entry to {@code replay-results.csv}:
     * {@code index;entry;status;exception;run_ms;timestamp_ms}, where {@code index} is the position of the entry in
     * the replay order and {@code timestamp_ms} the wall-clock time (epoch millis) the line was written.
     */
    private static final class ReplayResultsWriter implements AutoCloseable {
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

    /**
     * Writes one line per verified corpus entry to {@code key-results.csv}:
     * {@code entry;status;open_goals;automode_ms;nodes;trace_events;elapsed_ms;timestamp_ms}, where {@code trace_events}
     * is the number of events of the verified trace, {@code elapsed_ms} is measured from the start of the fuzz run and
     * {@code timestamp_ms} is the wall-clock time (epoch millis) the line was written.
     * The file is flushed after every line so partial runs remain usable. Thread-safe, the KeY proofs run in parallel.
     */
    private static final class KeyResultsWriter implements AutoCloseable {
        private final BufferedWriter writer;
        private final long startNanos = System.nanoTime();

        KeyResultsWriter(Path file) throws IOException {
            writer = Files.newBufferedWriter(file);
            writer.write("entry;status;open_goals;automode_ms;nodes;trace_events;elapsed_ms;timestamp_ms");
            writer.newLine();
            writer.flush();
        }

        synchronized void write(String entry, String status, KeY.ProofResult result) throws IOException {
            write(entry, status, result, null);
        }

        synchronized void write(String entry, String status, KeY.ProofResult result, Long traceEvents)
                throws IOException {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            Integer openGoals = result == null ? null : result.openGoals();
            Long automodeMillis = result == null ? null : result.automodeMillis();
            Long nodes = result == null ? null : result.nodes();
            writer.write(entry + ";" + status + ";"
                    + (openGoals == null ? "" : openGoals) + ";"
                    + (automodeMillis == null ? "" : automodeMillis) + ";"
                    + (nodes == null ? "" : nodes) + ";"
                    + (traceEvents == null ? "" : traceEvents) + ";"
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
}
