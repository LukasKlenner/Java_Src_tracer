package srctracer;

import srctracer.database.CsvFunctionDatabaseWriter;
import srctracer.instrumenter.Instrumenter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

class FuzzCommand {

    static final String FUZZ_TARGET_METHOD = "fuzzerTestOneInput";
    private static final String JAZZER_DIR = "tools/jazzer";
    private static final String JAZZER_EXE = "jazzer.exe";
    private static final String KEY_DIR = "tools/key";
    private static final String KEY_JAR = "key.jar";

    static void run(Main.TraceArgs args) throws Exception {
        Files.createDirectories(args.output());

        Path runtimeJar = Main.resolveRuntimeJar(args.binary());
        String className = Main.classNameFrom(args.input());

        Path functionDbFile = args.output().resolve(Main.DEFAULT_FUNCTION_DB_NAME);
        String instrumentedSource;
        try (var dbWriter = new CsvFunctionDatabaseWriter(functionDbFile)) {
            var instrumenter = new Instrumenter(dbWriter, List.of(args.input().getParent()), List.of(runtimeJar), args.startMethodName());
            instrumentedSource = instrumenter.transformToString(args.input());
        }
        Path instrumentedCompileDir = Files.createTempDirectory("srctracer-inst-");
        Main.compile(instrumentedSource, className, instrumentedCompileDir, runtimeJar);

        Path fuzzerCompileDir = null;
        if (args.inputSizesFile() == null) {
            String fuzzerSource = Files.readString(args.input().resolveSibling(args.input().getFileName().toString().replace(".java", "Assert.java")));
            fuzzerCompileDir = Files.createTempDirectory("srctracer-fuzz-");
            Main.compile(fuzzerSource, className, fuzzerCompileDir, runtimeJar);
        }

        Path corpusDir = args.output().resolve("corpus");
        Files.createDirectories(corpusDir);

        Path tracesDir = args.output().resolve("traces");
        Files.createDirectories(tracesDir);

        Path keyInputsDir = args.output().resolve("key-inputs");
        Files.createDirectories(keyInputsDir);

        int[] result;

        if (args.inputSizesFile() != null) {
            result = runWithInputSizes(args, className, instrumentedCompileDir, runtimeJar,
                    corpusDir, tracesDir, keyInputsDir, functionDbFile);
        } else {
            result = runWithJazzer(args, className, fuzzerCompileDir, instrumentedCompileDir, runtimeJar,
                    corpusDir, tracesDir, keyInputsDir, functionDbFile);
        }

        if (fuzzerCompileDir != null) Main.deleteRecursive(fuzzerCompileDir);
        Main.deleteRecursive(instrumentedCompileDir);

        int inputCount = result[0], bugsFound = result[1];
        System.out.println("Complete. Processed " + inputCount + " inputs, " + bugsFound + " potential bug(s) found. Output in: " + args.output());
    }

    private static int[] runWithInputSizes(
            Main.TraceArgs args, String className, Path instrumentedCompileDir, Path runtimeJar,
            Path corpusDir, Path tracesDir, Path keyInputsDir, Path functionDbFile
    ) throws Exception {
        String content = Files.readString(args.inputSizesFile()).trim();
        String[] parts = content.split(",");
        int[] sizes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            sizes[i] = Integer.parseInt(parts[i].trim());
        }

        Random rng = new Random(42);
        int batchCounter = 0;
        int bugsFound = 0;

        System.out.println("Running with " + sizes.length + " prepared input sizes ...");

        for (int size : sizes) {
            byte[] inputBytes = new byte[size];
            rng.nextBytes(inputBytes);

            Path inputFile = corpusDir.resolve("input-" + size);
            Files.write(inputFile, inputBytes);

            Path keyDir = prepareCorpusEntry(inputFile, className, instrumentedCompileDir, runtimeJar,
                    args.binary(), tracesDir, keyInputsDir, args.input(), functionDbFile);
            bugsFound += runPendingKeyInputsAndCheck(new ArrayList<>(List.of(keyDir)), args.output(), batchCounter++, args.keyMemory());
        }

        return new int[]{sizes.length, bugsFound};
    }

    private static int[] runWithJazzer(
            Main.TraceArgs args, String className, Path fuzzerCompileDir, Path instrumentedCompileDir,
            Path runtimeJar, Path corpusDir, Path tracesDir, Path keyInputsDir, Path functionDbFile
    ) throws Exception {
        System.out.println("Starting Jazzer for " + args.fuzzDuration() + "s ...");
        Process jazzerProcess = startJazzer(className, fuzzerCompileDir, corpusDir, args.fuzzDuration(), args.output());

        Set<Path> processedFiles = new HashSet<>();
        List<Path> pendingKeyDirs = new ArrayList<>();
        int bugsFound = 0;
        int batchCounter = 0;

        try {
            boolean changed = true;
            while (jazzerProcess.isAlive() || changed) {
                changed = false;

                Thread.sleep(500);

                List<Path> newFiles = findNewCorpusEntries(corpusDir, processedFiles);

                for (Path entry : newFiles) {
                    if (!Files.exists(entry)) continue;

                    pendingKeyDirs.add(prepareCorpusEntry(
                            entry, className, instrumentedCompileDir, runtimeJar,
                            args.binary(), tracesDir, keyInputsDir, args.input(), functionDbFile));
                    processedFiles.add(entry);
                    changed = true;
                }

                for (Path keyDir : pendingKeyDirs) {
                    bugsFound += runPendingKeyInputsAndCheck(new ArrayList<>(List.of(keyDir)), args.output(), batchCounter++, args.keyMemory());
                }
                pendingKeyDirs.clear();
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

        return new int[]{processedFiles.size(), bugsFound};
    }

    private static Path prepareCorpusEntry(
            Path corpusEntry,
            String className,
            Path instrumentedClassDir,
            Path runtimeJar,
            boolean binary,
            Path tracesDir,
            Path keyInputsDir,
            Path originalInput,
            Path functionDbFile
    ) throws Exception {
        byte[] inputBytes = Files.readAllBytes(corpusEntry);
        String corpusEntryName = corpusEntry.getFileName().toString();

        System.out.println("Preparing corpus entry " + corpusEntryName + " with " + inputBytes.length + " bytes ...");

        Path traceFile;
        try (URLClassLoader cl = Main.createClassLoader(instrumentedClassDir, runtimeJar)) {
            Class<?> traceClass = cl.loadClass("srctracer.Trace");
            Class<?> userClass = cl.loadClass(className);
            Method fuzzMethod = userClass.getMethod(FUZZ_TARGET_METHOD, byte[].class);
            fuzzMethod.setAccessible(true);

            if (binary) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                traceClass.getMethod("trace_start", OutputStream.class).invoke(null, baos);
                invokeFuzzTarget(fuzzMethod, inputBytes);
                traceClass.getMethod("trace_end").invoke(null);

                traceFile = tracesDir.resolve(corpusEntryName + ".trace");
                Files.write(traceFile, baos.toByteArray());
            } else {
                StringWriter sw = new StringWriter();
                traceClass.getMethod("trace_start", Writer.class).invoke(null, sw);
                invokeFuzzTarget(fuzzMethod, inputBytes);
                traceClass.getMethod("trace_end").invoke(null);

                traceFile = tracesDir.resolve(corpusEntryName + ".trace.txt");
                Files.writeString(traceFile, sw.toString());
            }
        }

        Path keyDir = keyInputsDir.resolve("key-input-" + corpusEntryName);
        Files.createDirectories(keyDir);

        Files.copy(traceFile, keyDir.resolve(traceFile.getFileName()));
        Files.copy(functionDbFile, keyDir.resolve(functionDbFile.getFileName()));

        KeyAnnotater.annotate(originalInput, keyDir, keyDir.resolve(traceFile.getFileName()), keyDir.resolve(functionDbFile.getFileName()), FUZZ_TARGET_METHOD);

        return keyDir;
    }

    private static int runPendingKeyInputsAndCheck(List<Path> pendingKeyDirs, Path outputDir, int batchIndex, String keyMemory) throws Exception {
        List<Path> proofFiles = new ArrayList<>();
        for (Path keyDir : pendingKeyDirs) {
            Path proofKey = keyDir.resolve("proof.key");
            if (Files.exists(proofKey)) {
                proofFiles.add(proofKey);
            }
        }

        if (proofFiles.isEmpty()) return 0;

        System.out.println("Running KeY batch " + batchIndex + " with " + proofFiles.size() + " proof(s) ...");
        Path keyLog = outputDir.resolve("key-batch-" + batchIndex + ".log");
        runKeyBatch(proofFiles, keyLog, keyMemory);

        int bugsFound = 0;
        for (Path keyDir : pendingKeyDirs) {
            String dirName = keyDir.getFileName().toString();
            boolean bugFound = checkKeyResult(keyDir);
            if (bugFound) {
                System.out.println("  >>> BUG FOUND on path from " + dirName + " (open goals > 0)");
                bugsFound++;
            } else {
                System.out.println("  " + dirName + " verified (0 open goals)");
            }
        }
        return bugsFound;
    }

    private static Path findProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir;
            dir = dir.getParent();
        }
        throw new RuntimeException("Cannot find project root (no settings.gradle.kts found)");
    }

    private static Path resolveJazzer() {
        Path projectRoot = findProjectRoot();
        Path jazzer = projectRoot.resolve(JAZZER_DIR).resolve(JAZZER_EXE);
        if (!Files.exists(jazzer)) {
            throw new RuntimeException("Jazzer not found: " + jazzer
                    + "\nPlace jazzer.exe in " + projectRoot.resolve(JAZZER_DIR));
        }
        return jazzer;
    }

    private static Path resolveKeyJar() {
        Path projectRoot = findProjectRoot();
        Path jar = projectRoot.resolve(KEY_DIR).resolve(KEY_JAR);
        if (!Files.exists(jar)) {
            throw new RuntimeException("KeY JAR not found: " + jar
                    + "\nPlace " + KEY_JAR + " in " + projectRoot.resolve(KEY_DIR));
        }
        return jar;
    }

    private static Process startJazzer(
            String className,
            Path classDir,
            Path corpusDir,
            int durationSeconds,
            Path outputDir
    ) throws Exception {
        Path jazzer = resolveJazzer();

        List<String> command = List.of(
                jazzer.toAbsolutePath().toString(),
                "--cp=" + classDir.toAbsolutePath(),
                "--target_class=" + className,
                "--keep_going=0",
                String.format("-max_total_time=%d", durationSeconds),
                corpusDir.toAbsolutePath().toString()
        );

        Path jazzerLog = outputDir.resolve("jazzer.log");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(outputDir.toAbsolutePath().toFile());
        pb.redirectOutput(jazzerLog.toFile());
        pb.redirectErrorStream(true);

        return pb.start();
    }

    private static void runKeyBatch(List<Path> proofKeyFiles, Path logFile, String keyMemory) throws Exception {

        for (Path p : proofKeyFiles) {
            runKey(p, keyMemory);
        }
// TODO fix
//        Path keyJar = resolveKeyJar();
//
//        List<String> command = new ArrayList<>();
//        command.add("java");
//        command.add("-jar");
//        command.add(keyJar.toAbsolutePath().toString());
//        command.add("--auto");
//        for (Path p : proofKeyFiles) {
//            command.add(p.toAbsolutePath().toString());
//        }
//
//        ProcessBuilder pb = new ProcessBuilder(command);
//        pb.redirectOutput(logFile.toFile());
//        pb.redirectErrorStream(true);
//        Process process = pb.start();
//        int exitCode = process.waitFor();
//
//        System.out.println("  KeY batch exited with code " + exitCode + " (log: " + logFile + ")");
    }

    private static void runKey(Path proofKeyFile, String keyMemory) throws Exception {
        Path keyJar = resolveKeyJar();

        List<String> command = List.of(
                "java", "-Xmx" + keyMemory, "-jar", keyJar.toAbsolutePath().toString(),
                "--auto", proofKeyFile.toAbsolutePath().toString()
        );

        Path workingDir = proofKeyFile.getParent();
        Path keyLog = workingDir.resolve("key.log");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectOutput(keyLog.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();

        System.out.println("  KeY exited with code " + exitCode + " (log: " + keyLog + ")");
    }

    private static boolean checkKeyResult(Path keyDir) throws Exception {
        Path csvFile = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(keyDir, "*.csv")) {
            for (Path entry : stream) {
                if (entry.getFileName().toString().endsWith("functions.csv")) {
                    continue;
                }
                csvFile = entry;
                break;
            }
        }

        if (csvFile == null) {
            System.out.println("  Warning: no KeY result CSV found in " + keyDir);
            return false;
        }

        for (String line : Files.readAllLines(csvFile)) {
            if (line.startsWith("open goals;")) {
                int openGoals = Integer.parseInt(line.substring("open goals;".length()).trim());
                return openGoals > 0;
            }
        }

        System.out.println("  Warning: no 'open goals' entry in " + csvFile);
        return false;
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
