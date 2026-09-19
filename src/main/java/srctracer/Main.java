package srctracer;

import srctracer.database.CsvFunctionDatabaseWriter;
import srctracer.database.FunctionDatabaseWriter;
import srctracer.instrumenter.Instrumenter;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

public class Main {

    private static final String USAGE = """
            Usage: java-src-tracer <command> <input.java> [options] [-- program-args...]
            
            Commands:
              instrument  Produce instrumented Java source
              trace       Instrument, compile, run, and produce a trace file
              annotate    Produce trace and pass to key-annotater for .key file
              fuzz        Fuzz, trace interesting inputs, and produce .key files
            
            Options:
              -o <file>      Output file (instrument only; default: <name>.instrumented.java)
              --binary       Use binary trace format (trace/annotate/fuzz; default: text)
              --duration <s> Fuzzing duration in seconds (fuzz only; default: 15)
              --batch-size <n> Number of proofs per KeY invocation (fuzz only; default: 0 = all)
              --start-method <name>  Start tracing from this method (default: main). Main method is not traced if this is not the default value.
              --             Separator for program arguments (trace/annotate)
            """;

    public static final String INSTRUMENT = "instrument";
    public static final String TRACE = "trace";
    public static final String ANNOTATE = "annotate";
    public static final String FUZZ = "fuzz";

    public static final String DEFAULT_FUNCTION_DB_NAME = "functions.csv";

    public static final String DEFAULT_TRACE_OUTPUT_DIR = "trace-out";
    public static final String DEFAULT_KEY_OUTPUT_DIR = "key-out";
    public static final String DEFAULT_FUZZ_OUTPUT_DIR = "fuzz-out";

    public static final String DEFAULT_START_METHOD_NAME = "main";
    public static final boolean DEFAULT_BINARY_TRACE = false;

    public static final int DEFAULT_FUZZ_DURATION = 15;
    public static final int DEFAULT_FUZZ_BATCH_SIZE = 1;
    public static final String DEFAULT_FUZZ_START_METHOD = "fuzzerTestOneInput";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.print(USAGE);
            throw new IllegalArgumentException("No command specified");
        }

        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        TraceArgs parsedArgs = parseTraceArgs(rest, command);

        switch (command) {
            case INSTRUMENT -> instrument(parsedArgs);
            case TRACE -> trace(parsedArgs);
            case ANNOTATE -> annotate(parsedArgs);
            case FUZZ -> fuzz(parsedArgs);
            default -> {
                System.err.print(USAGE);
                throw new IllegalArgumentException("Unknown command: " + command);
            }
        }
    }

    // ---- instrument: produce instrumented source ----

    private static void instrument(TraceArgs args) throws Exception {
        Path runtimeJar = resolveRuntimeJar(false);

        try (FunctionDatabaseWriter dbWriter = new CsvFunctionDatabaseWriter(args.output.resolveSibling(DEFAULT_FUNCTION_DB_NAME))) {
            Instrumenter instrumenter = new Instrumenter(dbWriter, List.of(args.input.getParent()), List.of(runtimeJar), args.startMethodName);

            instrumenter.transform(args.input, args.output);
        }

        System.out.println("Wrote instrumented source: " + args.output);
    }

    // ---- trace: instrument + compile + run → trace file ----

    private static void trace(TraceArgs args) throws Exception {

        Path runtimeJar = resolveRuntimeJar(args.binary);

        String instrumentedSource;
        try (FunctionDatabaseWriter dbWriter = new CsvFunctionDatabaseWriter(args.output().resolve(DEFAULT_FUNCTION_DB_NAME))) {
            Instrumenter instrumenter = new Instrumenter(dbWriter, List.of(args.input.getParent()), List.of(runtimeJar), args.startMethodName);

            instrumentedSource = instrumenter.transformToString(args.input);
        }

        String className = classNameFrom(args.input);
        Path tempDir = Files.createTempDirectory("srctracer-");

        compile(instrumentedSource, className, tempDir, runtimeJar);

        try (URLClassLoader cl = createClassLoader(tempDir, runtimeJar)) {

            Class<?> userClass = cl.loadClass(className);
            Method userMain = userClass.getMethod("main", String[].class);
            userMain.setAccessible(true);
            userMain.invoke(null, (Object) args.programArgs);
        } finally {
            deleteRecursive(tempDir);
        }
    }

// ---- annotate: instrument + compile + run (in-memory trace) → key-annotater ----

    private static void annotate(TraceArgs args) throws Exception {

        Path runtimeJar = resolveRuntimeJar(args.binary);

        String instrumentedSource;
        Path functionDatabaseFile = args.output.resolve(DEFAULT_FUNCTION_DB_NAME);
        try (FunctionDatabaseWriter dbWriter = new CsvFunctionDatabaseWriter(functionDatabaseFile)) {
            Instrumenter instrumenter = new Instrumenter(dbWriter, List.of(args.input.getParent()), List.of(runtimeJar), args.startMethodName);

            instrumentedSource = instrumenter.transformToString(args.input);
        }

        String className = classNameFrom(args.input);
        Path tempDir = Files.createTempDirectory("srctracer-");

        compile(instrumentedSource, className, tempDir, runtimeJar);

        try (URLClassLoader cl = createClassLoader(tempDir, runtimeJar)) {
            Class<?> traceClass = cl.loadClass("srctracer.Trace");

            Object memoryTarget;
            if (args.binary) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                traceClass.getMethod("trace_start", OutputStream.class).invoke(null, baos);
                memoryTarget = baos;
            } else {
                StringWriter sw = new StringWriter();
                traceClass.getMethod("trace_start", Writer.class).invoke(null, sw);
                memoryTarget = sw;
            }

            Class<?> userClass = cl.loadClass(className);
            Method userMain = userClass.getMethod("main", String[].class);
            userMain.setAccessible(true);
            try {
                userMain.invoke(null, (Object) args.programArgs);
            } catch (InvocationTargetException ignored) {
            }

            traceClass.getMethod("trace_end").invoke(null);

            Path traceFile = args.output.resolve(className + ".trace" + (args.binary ? "" : ".txt"));
            if (memoryTarget instanceof StringWriter sw) {
                Files.createDirectories(traceFile.getParent());
                Files.writeString(traceFile, sw.toString());
            } else {
                ByteArrayOutputStream baos = (ByteArrayOutputStream) memoryTarget;
                Files.createDirectories(traceFile.getParent());
                Files.write(traceFile, baos.toByteArray());
            }

            KeyAnnotater.annotate(args.input, args.output, traceFile, functionDatabaseFile, args.startMethodName);
            System.out.println("Annotation complete.");
        } finally {
            deleteRecursive(tempDir);
        }
    }

    // ---- fuzz: fuzz + trace interesting inputs + produce .key files ----

    private static void fuzz(TraceArgs args) throws Exception {
        FuzzCommand.run(args);
    }

// ---- shared arg parsing for trace/annotate ----

    record TraceArgs(
            Path input,
            Path output,
            boolean binary,
            int fuzzDuration,
            int batchSize,
            String startMethodName,
            String[] programArgs
    ) {
    }

    private static TraceArgs parseTraceArgs(String[] args, String command) {
        Path input = null;
        Path output = null;
        boolean binary = false;
        int fuzzDuration = 15;
        int batchSize = 1;
        String startMethodName = command.equals(FUZZ) ? DEFAULT_FUZZ_START_METHOD : DEFAULT_START_METHOD_NAME;
        String[] programArgs = new String[0];

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--binary" -> binary = true;
                case "--start-method" -> {
                    if (i + 1 >= args.length) {
                        System.err.print(USAGE);
                        throw new IllegalArgumentException("--start-method requires a value");
                    }
                    startMethodName = args[++i];
                }
                case "--duration" -> {
                    if (i + 1 >= args.length) {
                        System.err.print(USAGE);
                        throw new IllegalArgumentException("--duration requires a value");
                    }
                    fuzzDuration = Integer.parseInt(args[++i]);
                }
                case "--batch-size" -> {
                    if (i + 1 >= args.length) {
                        System.err.print(USAGE);
                        throw new IllegalArgumentException("--batch-size requires a value");
                    }
                    batchSize = Integer.parseInt(args[++i]);
                }
                case "-o" -> {
                    if (i + 1 >= args.length) {
                        System.err.print(USAGE);
                        throw new IllegalArgumentException("-o requires a value");
                    }
                    output = Path.of(args[++i]);
                }
                case "--" -> {
                    programArgs = Arrays.copyOfRange(args, i + 1, args.length);
                    i = args.length;
                }
                default -> {
                    if (input == null) {
                        input = Path.of(args[i]);
                    } else {
                        System.err.print(USAGE);
                        throw new IllegalArgumentException("Unexpected argument: " + args[i]);
                    }
                }
            }
        }

        if (input == null) {
            System.err.print(USAGE);
            throw new IllegalArgumentException("Missing input file");
        }

        if (output == null) {

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
            String timestamp = fmt.format(LocalDateTime.now());

            String fileName = input.getFileName().toString().replace(".java", "");
            switch (command) {
                case INSTRUMENT -> output = input.resolveSibling(fileName + ".instrumented.java");
                case TRACE -> output = Path.of(DEFAULT_TRACE_OUTPUT_DIR, fileName + "_" + timestamp);
                case ANNOTATE -> output = Path.of(DEFAULT_KEY_OUTPUT_DIR, fileName + "_" + timestamp);
                case FUZZ -> output = Path.of(DEFAULT_FUZZ_OUTPUT_DIR, fileName + "_" + timestamp);
            }
        }

        return new TraceArgs(input, output, binary, fuzzDuration, batchSize, startMethodName, programArgs);
    }

// ---- shared helpers ----

    static Method getStartMethod(String startMethodName, Class<?> userClass) {
        List<Method> startMethods = Arrays.stream(userClass.getMethods())
                .filter(m -> m.getName().equals(startMethodName))
                .toList();

        if (startMethods.isEmpty()) {
            throw new IllegalArgumentException("No suitable start method found: " + startMethodName);
        }

        if (startMethods.size() > 1) {
            throw new IllegalArgumentException("Multiple methods found with name: " + startMethodName);
        }

        return startMethods.getFirst();
    }

    static String classNameFrom(Path input) {
        return input.getFileName().toString().replace(".java", "");
    }

    static URLClassLoader createClassLoader(Path classDir, Path runtimeJar)
            throws Exception {
        return new URLClassLoader(
                new URL[]{
                        classDir.toUri().toURL(),
                        runtimeJar.toUri().toURL(),
                },
                ClassLoader.getPlatformClassLoader()
        );
    }

    static void compile(
            String source,
            String className,
            Path outputDir,
            Path runtimeJar
    ) {

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("No Java compiler available. Run with a JDK, not a JRE.");
        }

        JavaFileObject sourceFile = new InMemoryJavaFile(className, source);

        String classpath = runtimeJar.toAbsolutePath().toString();

        List<String> options = List.of(
                "-d", outputDir.toAbsolutePath().toString(),
                "-cp", classpath
        );

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, null, null, options, null, List.of(sourceFile));

        if (!task.call()) {
            throw new RuntimeException("Compilation failed");
        }
    }

    static Path resolveRuntimeJar(boolean binary) {
        Path projectRoot = findProjectRoot();
        String module = binary ? "runtime-binary" : "runtime";
        Path jar = projectRoot.resolve(module + "/build/libs/" + module + "-0.1.0-SNAPSHOT.jar");
        if (!Files.exists(jar)) {
            throw new RuntimeException("Runtime JAR not found: " + jar
                    + "\nRun: gradlew :" + module + ":jar");
        }
        return jar;
    }

    private static Path findProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir;
            dir = dir.getParent();
        }
        throw new RuntimeException("Cannot find project root (no settings.gradle.kts found)");
    }

    static void deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var entries = Files.list(path)) {
                    entries.forEach(Main::deleteRecursive);
                }
            }
            Files.deleteIfExists(path);
        } catch (Exception e) {
            // best-effort cleanup
        }
    }

    static class InMemoryJavaFile extends SimpleJavaFileObject {
        private final String code;

        InMemoryJavaFile(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
