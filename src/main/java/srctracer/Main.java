package srctracer;

import srctracer.database.CsvFunctionDatabaseWriter;
import srctracer.database.FunctionDatabaseWriter;
import srctracer.instrumenter.Instrumenter;
import srctracer.tools.ToolsUtils;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {

    private static final String USAGE = """
            Usage: java-src-tracer <command> <input.java|dir>... [options] [-- program-args...]

            Inputs are .java files and/or directories (searched recursively; *.instrumented.java and <X>Assert.java
            next to an <X>.java are skipped). The entry class is the one class declaring main (trace, annotate) or
            fuzzerTestOneInput (fuzz).

            Commands:
              instrument  Write the instrumented sources
              trace       Instrument, compile and run main; write the trace
              annotate    As trace, then write the KeY proof obligation (proof.key) for the trace
              fuzz        Fuzz with Jazzer (or replay a corpus) and verify the traces of the corpus entries with KeY

            Options:
              -o <dir>                  Output directory (default: <command>-out/<EntryClass>_<timestamp>)
              --binary                  Binary trace format (default: text)
              --start-method <name>     Method where tracing starts (default: main; fuzz: the one method outside the
                                        harness with a JML contract). Must be unique in the program.
              --no-implicit-exceptions  Do not record implicit exceptions (instrument, trace)
              --strictRetracing         Strict retracing (annotate)
              --                        Arguments of main (trace, annotate)

            Fuzz options:
              --oracle <name>           What flags an input as buggy (default: key):
                                          crash    Jazzer on <X>.java, crashes only
                                          runtime  Jazzer on <X>Assert.java (Java asserts)
                                          key      Jazzer on <X>.java, KeY verifies the contract of the traced method
                                                   along the trace of every corpus entry
              --proof-mode <mode>       multi-trace: one proof over all traces after fuzzing (default)
                                        per-trace:   one proof per trace, run while the corpus is traced
              --corpus <dir>            Replay this corpus instead of fuzzing; the outcome of every entry is written to
                                        replay-results.csv
              --duration <s>            Fuzzing time (default: 15)
              --seed <n>                Jazzer seed (default: random)
              --jazzer-timeout <s>      Per-input timeout (default: libFuzzer's)
              --max-len <n>             Maximum input length in bytes (default: 4096)
              --key-timeout <s>         Wall-clock limit per KeY proof (default: none)
              --key-memory <size>       JVM heap per KeY process (default: 8g)
              --key-jobs <n>            Parallel KeY proofs, per-trace only (default: 1)
              --stop-on-bug             Stop at the first Jazzer finding or open per-trace proof
            """;

    public static final String DEFAULT_FUNCTION_DB_NAME = "functions.csv";
    private static final String DEFAULT_START_METHOD_NAME = "main";

    record TraceArgs(
            ProgramSources sources,
            Path output,
            boolean binary,
            String startMethodName, // fuzz: null = the method with a JML contract
            String[] programArgs,
            boolean strictRetracing,
            boolean implicitExceptions,
            FuzzCommand.Options fuzz
    ) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw usageError("No command specified");
        String command = args[0];
        TraceArgs parsed = parseArgs(command, Arrays.copyOfRange(args, 1, args.length));
        Files.createDirectories(parsed.output());
        switch (command) {
            case "instrument" -> instrument(parsed);
            case "trace" -> trace(parsed);
            case "annotate" -> annotate(parsed);
            case "fuzz" -> {
                if (!parsed.implicitExceptions()) throw usageError("--no-implicit-exceptions is not supported for fuzz");
                if (parsed.strictRetracing()) throw usageError("--strictRetracing is not supported for fuzz");
                FuzzCommand.run(parsed);
            }
            default -> throw usageError("Unknown command: " + command);
        }
    }

    // ---- commands ----

    private static void instrument(TraceArgs args) throws Exception {
        Map<ProgramSources.SourceFile, String> instrumented =
                instrumentAll(args, args.sources(), args.startMethodName(), resolveRuntimeJar(false));
        for (var entry : instrumented.entrySet()) {
            Path relative = entry.getKey().relativePath();
            Path target = args.output().resolve(relative).resolveSibling(
                    relative.getFileName().toString().replace(".java", ".instrumented.java"));
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue());
        }
        System.out.println("Wrote " + instrumented.size() + " instrumented source file(s) to " + args.output());
    }

    /** Instruments, compiles and runs main; returns the trace file. */
    private static Path trace(TraceArgs args) throws Exception {
        Path runtimeJar = resolveRuntimeJar(args.binary());
        Path classDir = Files.createTempDirectory("srctracer-");
        try {
            compile(instrumentAll(args, args.sources(), args.startMethodName(), runtimeJar), classDir, runtimeJar);
            try (URLClassLoader cl = createClassLoader(classDir, runtimeJar)) {
                Method main = cl.loadClass(args.sources().entryClassName()).getMethod("main", String[].class);
                byte[] trace = recordTrace(cl, args.binary(), false, () -> invoke(main, args.programArgs()));
                return Files.write(args.output().resolve(traceFileName(args.sources().displayName(), args.binary())),
                        trace);
            }
        } finally {
            deleteRecursive(classDir);
        }
    }

    private static void annotate(TraceArgs args) throws Exception {
        if (!args.implicitExceptions()) throw usageError("--no-implicit-exceptions is not supported for annotate");
        Path traceFile = trace(args);
        KeyAnnotater.annotate(args.sources(), args.output(), traceFile, args.output().resolve(DEFAULT_FUNCTION_DB_NAME),
                args.startMethodName(), args.strictRetracing());
        System.out.println("Annotation complete.");
    }

    // ---- arguments ----

    private static IllegalArgumentException usageError(String message) {
        System.err.print(USAGE);
        return new IllegalArgumentException(message);
    }

    /** Reads the arguments one by one. */
    private static final class ArgReader {
        private final String[] args;
        private int next = 0;

        ArgReader(String[] args) {
            this.args = args;
        }

        boolean hasNext() {
            return next < args.length;
        }

        String next() {
            return args[next++];
        }

        String[] rest() {
            String[] rest = Arrays.copyOfRange(args, next, args.length);
            next = args.length;
            return rest;
        }

        String value(String option) {
            if (!hasNext()) throw usageError(option + " requires a value");
            return next();
        }

        int intValue(String option) {
            return Integer.parseInt(value(option));
        }
    }

    private static TraceArgs parseArgs(String command, String[] arguments) throws IOException {
        boolean fuzz = command.equals("fuzz");
        List<Path> inputs = new ArrayList<>();
        Path output = null;
        boolean binary = false;
        String startMethodName = fuzz ? null : DEFAULT_START_METHOD_NAME;
        String[] programArgs = new String[0];
        boolean strictRetracing = false;
        boolean implicitExceptions = true;
        FuzzCommand.Options.Builder fuzzOptions = new FuzzCommand.Options.Builder();

        ArgReader args = new ArgReader(arguments);
        while (args.hasNext()) {
            String arg = args.next();
            switch (arg) {
                case "-o" -> output = Path.of(args.value(arg));
                case "--binary" -> binary = true;
                case "--start-method" -> startMethodName = args.value(arg);
                case "--no-implicit-exceptions" -> implicitExceptions = false;
                case "--strictRetracing" -> strictRetracing = true;
                case "--" -> programArgs = args.rest();
                case "--oracle" -> fuzzOptions.oracle = FuzzOracle.fromCliName(args.value(arg));
                case "--proof-mode" -> fuzzOptions.multiTrace = switch (args.value(arg)) {
                    case "multi-trace" -> true;
                    case "per-trace" -> false;
                    default -> throw usageError("--proof-mode: expected multi-trace or per-trace");
                };
                case "--corpus" -> fuzzOptions.corpus = Path.of(args.value(arg));
                case "--duration" -> fuzzOptions.duration = args.intValue(arg);
                case "--seed" -> fuzzOptions.seed = Long.parseLong(args.value(arg));
                case "--jazzer-timeout" -> fuzzOptions.jazzerTimeout = args.intValue(arg);
                case "--max-len" -> fuzzOptions.maxLen = args.intValue(arg);
                case "--key-timeout" -> fuzzOptions.keyTimeout = args.intValue(arg);
                case "--key-memory" -> fuzzOptions.keyMemory = args.value(arg);
                case "--key-jobs" -> fuzzOptions.keyJobs = args.intValue(arg);
                case "--stop-on-bug" -> fuzzOptions.stopOnBug = true;
                default -> {
                    if (arg.startsWith("-")) throw usageError("Unknown option: " + arg);
                    inputs.add(Path.of(arg));
                }
            }
        }
        if (inputs.isEmpty()) throw usageError("Missing input file");

        // the entry class is the one that is run; --start-method only selects where tracing starts
        String entryMethod = fuzz ? FuzzCommand.FUZZ_TARGET_METHOD : DEFAULT_START_METHOD_NAME;
        ProgramSources sources = ProgramSources.collect(inputs, entryMethod, !command.equals("instrument"));
        if (output == null) {
            String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").format(LocalDateTime.now());
            output = Path.of(command + "-out", sources.displayName() + "_" + timestamp);
        }
        return new TraceArgs(sources, output, binary, startMethodName, programArgs, strictRetracing,
                implicitExceptions, fuzzOptions.build());
    }

    // ---- shared helpers ----

    /**
     * Instruments all files of {@code sources} with one instrumenter, so they share the function database in the
     * output directory. Tracing starts in {@code startMethodName}.
     */
    static Map<ProgramSources.SourceFile, String> instrumentAll(TraceArgs args, ProgramSources sources,
                                                               String startMethodName, Path runtimeJar) throws Exception {
        Map<ProgramSources.SourceFile, String> result = new LinkedHashMap<>();
        try (FunctionDatabaseWriter dbWriter = new CsvFunctionDatabaseWriter(
                args.output().resolve(DEFAULT_FUNCTION_DB_NAME))) {
            Instrumenter instrumenter = new Instrumenter(dbWriter, sources.sourceRoots(), List.of(runtimeJar),
                    startMethodName, args.implicitExceptions());
            for (ProgramSources.SourceFile file : sources.files()) {
                result.put(file, instrumenter.transformToString(file.path()));
            }
        }
        return result;
    }

    static Map<ProgramSources.SourceFile, String> readAll(ProgramSources sources) throws IOException {
        Map<ProgramSources.SourceFile, String> result = new LinkedHashMap<>();
        for (ProgramSources.SourceFile file : sources.files()) {
            result.put(file, Files.readString(file.path()));
        }
        return result;
    }

    static String traceFileName(String name, boolean binary) {
        return name + (binary ? ".trace" : ".trace.txt");
    }

    interface Action {
        void run() throws Exception;
    }

    /**
     * Records the trace of {@code run} with the runtime loaded by {@code cl}. With {@code onEntry}, recording starts
     * at the first entry of the start method (code that runs before, e.g. a fuzz harness, is not recorded).
     */
    static byte[] recordTrace(ClassLoader cl, boolean binary, boolean onEntry, Action run) throws Exception {
        Class<?> trace = cl.loadClass("srctracer.Trace");
        String start = onEntry ? "trace_start_on_entry" : "trace_start";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StringWriter text = new StringWriter();
        if (binary) {
            trace.getMethod(start, OutputStream.class).invoke(null, bytes);
        } else {
            trace.getMethod(start, Writer.class).invoke(null, text);
        }
        run.run();
        trace.getMethod("trace_end").invoke(null);
        return binary ? bytes.toByteArray() : text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the start method was entered in the last {@link #recordTrace} with {@code onEntry}. */
    static boolean traceRecorded(ClassLoader cl) throws Exception {
        return (boolean) cl.loadClass("srctracer.Trace").getMethod("trace_recorded").invoke(null);
    }

    /** Invokes the static method; an exception thrown by the method itself is ignored (it is part of the trace). */
    static void invoke(Method method, Object argument) throws IllegalAccessException {
        method.setAccessible(true);
        try {
            method.invoke(null, argument);
        } catch (InvocationTargetException ignored) {
        }
    }

    static URLClassLoader createClassLoader(Path classDir, Path runtimeJar) throws Exception {
        return new URLClassLoader(new URL[]{classDir.toUri().toURL(), runtimeJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
    }

    /** Compiles the given sources (file -> source text) together into {@code outputDir}. */
    static void compile(Map<ProgramSources.SourceFile, String> sources, Path outputDir, Path runtimeJar) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new RuntimeException("No Java compiler available. Run with a JDK, not a JRE.");
        List<JavaFileObject> files = sources.entrySet().stream()
                .<JavaFileObject>map(e -> new InMemoryJavaFile(e.getKey().relativePath(), e.getValue()))
                .toList();
        List<String> options = List.of("-d", outputDir.toAbsolutePath().toString(),
                "-cp", runtimeJar.toAbsolutePath().toString());
        if (!compiler.getTask(null, null, null, options, null, files).call()) {
            throw new RuntimeException("Compilation failed");
        }
    }

    /** Compiles the sources into a new temporary directory. */
    static Path compileToTempDir(Map<ProgramSources.SourceFile, String> sources, Path runtimeJar) throws IOException {
        Path dir = Files.createTempDirectory("srctracer-");
        compile(sources, dir, runtimeJar);
        return dir;
    }

    static Path resolveRuntimeJar(boolean binary) {
        String module = binary ? "runtime-binary" : "runtime";
        Path jar = ToolsUtils.findProjectRoot().resolve(module + "/build/libs/" + module + "-0.1.0-SNAPSHOT.jar");
        if (!Files.exists(jar)) throw new RuntimeException("Runtime JAR not found: " + jar + "\nRun: gradlew :" + module + ":jar");
        return jar;
    }

    /** Best-effort recursive delete. */
    static void deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var entries = Files.list(path)) {
                    entries.forEach(Main::deleteRecursive);
                }
            }
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    static class InMemoryJavaFile extends SimpleJavaFileObject {
        private final String code;

        /** @param relativePath package directories + file name, e.g. {@code a/b/Foo.java} */
        InMemoryJavaFile(Path relativePath, String code) {
            super(URI.create("string:///" + relativePath.toString().replace('\\', '/')), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
