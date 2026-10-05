package srctracer;

import srctracer.database.CsvFunctionDatabaseWriter;
import srctracer.instrumenter.Instrumenter;
import srctracer.tools.Jazzer;
import srctracer.tools.KeY;

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

    static void run(Main.TraceArgs args) throws Exception {

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
        Process jazzerProcess = Jazzer.startJazzerAsync(className, fuzzerCompileDir, corpusDir, args.fuzzDuration(), args.output());

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
        int bugsFound = 0;

        for (Path keyDir : pendingKeyDirs) {
            Path keyProofFile = keyDir.resolve("proof.key");
            if (!Files.exists(keyProofFile)) {
                continue;
            }

            KeY keyInstance = new KeY(keyProofFile, keyMemory);
            keyInstance.runKey();

            if (keyInstance.isProofClosed()) {
                System.out.println("  " + keyDir.getFileName() + " verified (0 open goals)");
            } else {
                System.out.println("  >>> BUG FOUND on path from " + keyDir.getFileName() + " (open goals > 0)");
                bugsFound++;
            }

        }

        return bugsFound;
    }

    private static void runKeyBatch(List<Path> proofKeyFiles, Path logFile, String keyMemory) throws Exception {

        for (Path p : proofKeyFiles) {
            new KeY(p, keyMemory).runKey();
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
