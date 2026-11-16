package srctracer;

import srctracer.tools.Jazzer;

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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class FuzzCommand {

    static final String FUZZ_TARGET_METHOD = "fuzzerTestOneInput";

    /** Per-input timeout of a --corpus replay without --jazzer-timeout (libFuzzer's default). */
    private static final int DEFAULT_REPLAY_TIMEOUT_SECONDS = 1200;

    /**
     * The method that is traced and verified with KeY, and the files that are traced and passed to KeY.
     * By default this is the one method outside the harness with a JML contract (--start-method overrides this).
     * If the method is not declared in the harness file, the harness is neither instrumented nor given to KeY,
     * so the path class is defined over the arguments of the method and the harness may use any Java code.
     */
    record TraceTarget(String methodName, ProgramSources sources) {
    }

    private final Main.TraceArgs args;
    private final Path runtimeJar;
    private final String className;
    private final Path functionDbFile;
    private final Path corpusDir;
    private final Path tracesDir;
    private final Path keyInputsDir;

    // only set if the corpus entries are verified with KeY
    private TraceTarget target;
    private Path instrumentedCompileDir;
    private KeyResultsWriter keyResults;
    private KeyRunner keyRunner;

    // only set if the harness is run uninstrumented (Jazzer or --corpus replay)
    private Path fuzzerCompileDir;

    private FuzzCommand(Main.TraceArgs args) {
        this.args = args;
        this.runtimeJar = Main.resolveRuntimeJar(args.binary());
        this.className = args.sources().entryClassName();
        this.functionDbFile = args.output().resolve(Main.DEFAULT_FUNCTION_DB_NAME);
        this.corpusDir = args.output().resolve("corpus");
        this.tracesDir = args.output().resolve("traces");
        this.keyInputsDir = args.output().resolve("key-inputs");
    }

    static void run(Main.TraceArgs args) throws Exception {
        new FuzzCommand(args).run();
    }

    private void run() throws Exception {
        boolean verifyWithKey = args.inputSizesFile() != null || args.oracle().verifyWithKey();

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

        if (args.inputSizesFile() == null) {
            ProgramSources fuzzerSources = args.oracle().fuzzAssertVariant()
                    ? args.sources().withAssertVariant()
                    : args.sources();
            fuzzerCompileDir = Files.createTempDirectory("srctracer-fuzz-");
            Main.compile(Main.readAll(fuzzerSources), fuzzerCompileDir, runtimeJar);
        }

        Files.createDirectories(corpusDir);
        if (verifyWithKey) {
            Files.createDirectories(tracesDir);
            Files.createDirectories(keyInputsDir);
            keyResults = new KeyResultsWriter(args.output().resolve(KeyResultsWriter.FILE_NAME));
            keyRunner = new KeyRunner(args, keyResults);
        }

        int inputCount;
        try {
            if (args.inputSizesFile() != null) {
                inputCount = runWithInputSizes();
            } else if (args.replayCorpus() != null) {
                inputCount = runWithCorpus();
            } else {
                inputCount = runWithJazzer();
            }
        } finally {
            if (keyRunner != null) keyRunner.close();
            if (keyResults != null) keyResults.close();
        }

        if (fuzzerCompileDir != null) Main.deleteRecursive(fuzzerCompileDir);
        if (instrumentedCompileDir != null) Main.deleteRecursive(instrumentedCompileDir);

        System.out.println("Complete. Processed " + inputCount + " inputs, " + bugsFound()
                + " potential bug(s) found. Output in: " + args.output());
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

    private int bugsFound() {
        return keyRunner == null ? 0 : keyRunner.bugsFound();
    }

    private boolean stoppedOnBug() {
        return keyRunner != null && keyRunner.stoppedOnBug();
    }

    /** Waits for the submitted KeY proofs, or cancels them if {@code stop} is set or KeY stopped on a bug. */
    private void finishProofs(boolean stop) throws InterruptedException {
        if (keyRunner == null) return;
        if (stop || keyRunner.stoppedOnBug()) {
            keyRunner.stop();
        } else {
            keyRunner.awaitAll();
        }
    }

    private int runWithInputSizes() throws Exception {
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
            processEntry(inputFile);
        }
        finishProofs(false);

        return sizes.length;
    }

    private int runWithJazzer() throws Exception {
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
            while ((jazzerProcess.isAlive() || changed) && !stoppedOnBug()) {
                changed = false;

                if (args.stopOnBug() && !jazzerProcess.isAlive() && hasJazzerFinding(args.output())) {
                    System.out.println("Jazzer reported a finding, skipping remaining corpus entries");
                    jazzerFinding = true;
                    break;
                }

                Thread.sleep(500);

                for (Path entry : findNewCorpusEntries(corpusDir, processedFiles)) {
                    if (!Files.exists(entry)) continue;

                    processedFiles.add(entry);
                    changed = true;
                    processEntry(entry);
                }
            }

            finishProofs(jazzerFinding);
        } finally {
            if (jazzerProcess.isAlive()) {
                System.out.println("Stopping Jazzer ...");
                jazzerProcess.destroyForcibly();
                jazzerProcess.waitFor();
            }
        }

        int exitCode = jazzerProcess.exitValue();
        System.out.println("Jazzer exited with code " + exitCode + " (log: " + args.output().resolve("jazzer.log") + ")");

        return processedFiles.size();
    }

    /**
     * Replays the entries of an existing corpus (--corpus) instead of fuzzing. The entries are copied to the output
     * corpus directory and processed in the order Jazzer found them (modification time, then name). Each entry is run
     * once with the oracle's harness (as Jazzer would run it, see {@link #replay}); the outcome is written to
     * replay-results.csv. With a KeY oracle, the entry is then traced and verified like a new Jazzer corpus entry.
     * An entry that exceeds the per-input timeout stops the replay, like Jazzer stops at its first timeout; the
     * remaining entries are neither replayed nor verified.
     */
    private int runWithCorpus() throws Exception {
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
        try (ReplayResultsWriter replayResults = new ReplayResultsWriter(args.output().resolve(ReplayResultsWriter.FILE_NAME))) {
            for (Path source : entries) {
                if (stoppedOnBug()) break;

                Path entry = corpusDir.resolve(source.getFileName());
                Files.copy(source, entry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                String entryName = entry.getFileName().toString();

                long startMillis = System.currentTimeMillis();
                ReplayOutcome outcome = replay(entry, timeoutSeconds);
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

                if (keyRunner != null) processEntry(entry);
            }
        }
        System.out.println(failures + " of " + processed + " replayed entries fail on the concrete run");

        finishProofs(false);
        return processed;
    }

    /**
     * Runs fuzzerTestOneInput of the uninstrumented harness on the entry in a fresh class loader. The run happens on a
     * daemon thread; after the timeout it is abandoned (it cannot be killed and keeps running until the JVM exits).
     */
    private ReplayOutcome replay(Path corpusEntry, int timeoutSeconds) throws Exception {
        byte[] inputBytes = Files.readAllBytes(corpusEntry);
        URLClassLoader cl = Main.createClassLoader(fuzzerCompileDir, runtimeJar);
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
     * Traces the corpus entry and submits its proof to KeY. If the entry does not reach the traced method or cannot
     * be prepared, this is written to key-results.csv instead.
     */
    private void processEntry(Path entry) throws IOException {
        String entryName = entry.getFileName().toString();
        Path keyDir;
        try {
            keyDir = prepareCorpusEntry(entry);
        } catch (Exception e) {
            // e.g. libFuzzer removed the entry while it was being read
            System.out.println("  Could not prepare corpus entry " + entryName + ": " + e);
            keyResults.write(entryName, KeyResultsWriter.PREPARE_ERROR, null);
            return;
        }
        if (keyDir == null) {
            keyResults.write(entryName, KeyResultsWriter.NOT_REACHED, null);
        } else {
            keyRunner.submit(keyDir);
        }
    }

    /**
     * Runs the harness on the corpus entry, records the trace of the first invocation of the traced method and
     * creates the KeY input directory. Returns null if the input does not reach the traced method.
     */
    private Path prepareCorpusEntry(Path corpusEntry) throws Exception {
        byte[] inputBytes = Files.readAllBytes(corpusEntry);
        String corpusEntryName = corpusEntry.getFileName().toString();

        System.out.println("Preparing corpus entry " + corpusEntryName + " with " + inputBytes.length + " bytes ...");

        Path traceFile;
        try (URLClassLoader cl = Main.createClassLoader(instrumentedCompileDir, runtimeJar)) {
            Class<?> traceClass = cl.loadClass("srctracer.Trace");
            Class<?> userClass = cl.loadClass(className);
            Method fuzzMethod = userClass.getMethod(FUZZ_TARGET_METHOD, byte[].class);
            fuzzMethod.setAccessible(true);

            if (args.binary()) {
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
        }

        Path keyDir = keyInputsDir.resolve("key-input-" + corpusEntryName);
        Files.createDirectories(keyDir);

        Files.copy(traceFile, keyDir.resolve(traceFile.getFileName()));
        Files.copy(functionDbFile, keyDir.resolve(functionDbFile.getFileName()));

        KeyAnnotater.annotate(target.sources(), keyDir, keyDir.resolve(traceFile.getFileName()),
                keyDir.resolve(functionDbFile.getFileName()), target.methodName(), false);

        return keyDir;
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
}
