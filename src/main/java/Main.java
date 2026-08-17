import srctracer.KeyAnnotater;
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
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class Main {

    private static final String USAGE = """
            Usage: java-src-tracer <command> <input.java> [options] [-- program-args...]
            
            Commands:
              instrument  Produce instrumented Java source
              trace       Instrument, compile, run, and produce a trace file
              annotate    Produce trace and pass to key-annotater for .key file
            
            Options:
              -o <file>   Output file (instrument only; default: <name>.instrumented.java)
              --binary    Use binary trace format (trace/annotate; default: text)
              --          Separator for program arguments (trace/annotate)
            """;

    public static final String DEFAULT_FUNCTION_DB_NAME = "functions.csv";
    public static final String DEFAULT_TRACE_OUTPUT_DIR = "trace-out";
    public static final String DEFAULT_KEY_OUTPUT_DIR = "key-out";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.print(USAGE);
            throw new IllegalArgumentException("No command specified");
        }

        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "instrument" -> instrument(rest);
            case "trace" -> trace(rest);
            case "annotate" -> annotate(rest);
            default -> {
                System.err.print(USAGE);
                throw new IllegalArgumentException("Unknown command: " + command);
            }
        }
    }

    // ---- instrument: produce instrumented source ----

    private static void instrument(String[] args) throws Exception {
        Path input = null;
        Path output = null;

        for (int i = 0; i < args.length; i++) {
            if ("-o".equals(args[i]) && i + 1 < args.length) {
                output = Path.of(args[++i]);
            } else if (input == null) {
                input = Path.of(args[i]);
            } else {
                System.err.print(USAGE);
                throw new IllegalArgumentException("Unexpected argument: " + args[i]);
            }
        }

        if (input == null) {
            System.err.print(USAGE);
            throw new IllegalArgumentException("No input file specified");
        }

        if (output == null) {
            String name = input.getFileName().toString().replace(".java", "");
            output = input.resolveSibling(name + ".instrumented.java");
        }

        Path runtimeJar = resolveRuntimeJar(false);

        try (FunctionDatabaseWriter dbWriter = new CsvFunctionDatabaseWriter(output.resolveSibling(DEFAULT_FUNCTION_DB_NAME))) {
            Instrumenter instrumenter = new Instrumenter(dbWriter, List.of(input.getParent()), List.of(runtimeJar));

            instrumenter.transform(input, output);
        }

        System.out.println("Wrote instrumented source: " + output);
    }

    // ---- trace: instrument + compile + run → trace file ----

    private static void trace(String[] args) throws Exception {
        TraceArgs parsed = parseTraceArgs(args, "trace");

        Path runtimeJar = resolveRuntimeJar(parsed.binary);

        String instrumentedSource;
        try (FunctionDatabaseWriter dbWriter = new CsvFunctionDatabaseWriter(Path.of(DEFAULT_TRACE_OUTPUT_DIR, DEFAULT_FUNCTION_DB_NAME))) {
            Instrumenter instrumenter = new Instrumenter(dbWriter, List.of(parsed.input.getParent()), List.of(runtimeJar));

            instrumentedSource = instrumenter.transformToString(parsed.input);
        }

        String className = classNameFrom(parsed.input);
        Path tempDir = Files.createTempDirectory("srctracer-");

        compile(instrumentedSource, className, tempDir, runtimeJar);

        try (URLClassLoader cl = createClassLoader(tempDir, runtimeJar)) {

            Class<?> userClass = cl.loadClass(className);
            Method userMain = userClass.getMethod("main", String[].class);
            userMain.setAccessible(true);
            userMain.invoke(null, (Object) parsed.programArgs);
        } finally {
            deleteRecursive(tempDir);
        }
    }

// ---- annotate: instrument + compile + run (in-memory trace) → key-annotater ----

    private static void annotate(String[] args) throws Exception {
        TraceArgs parsed = parseTraceArgs(args, "annotate");

        Path runtimeJar = resolveRuntimeJar(parsed.binary);

        String instrumentedSource;
        Path functionDatabaseFile = Path.of(DEFAULT_KEY_OUTPUT_DIR, DEFAULT_FUNCTION_DB_NAME);
        try (FunctionDatabaseWriter dbWriter = new CsvFunctionDatabaseWriter(functionDatabaseFile)) {
            Instrumenter instrumenter = new Instrumenter(dbWriter, List.of(parsed.input.getParent()), List.of(runtimeJar));

            instrumentedSource = instrumenter.transformToString(parsed.input);
        }


        String className = classNameFrom(parsed.input);
        Path tempDir = Files.createTempDirectory("srctracer-");

        compile(instrumentedSource, className, tempDir, runtimeJar);

        try (URLClassLoader cl = createClassLoader(tempDir, runtimeJar)) {
            Class<?> traceClass = cl.loadClass("srctracer.Trace");

            Object memoryTarget;
            if (parsed.binary) {
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
            userMain.invoke(null, (Object) parsed.programArgs);

            traceClass.getMethod("trace_end").invoke(null);

            Path traceFile = Path.of(DEFAULT_KEY_OUTPUT_DIR, className + ".trace" + (parsed.binary ? "" : ".txt"));
            if (memoryTarget instanceof StringWriter sw) {
                Files.createDirectories(traceFile.getParent());
                Files.writeString(traceFile, sw.toString());
            } else {
                ByteArrayOutputStream baos = (ByteArrayOutputStream) memoryTarget;
                Files.createDirectories(traceFile.getParent());
                Files.write(traceFile, baos.toByteArray());
            }

            KeyAnnotater.annotate(parsed.input, Path.of(DEFAULT_KEY_OUTPUT_DIR), traceFile, functionDatabaseFile);
            System.out.println("Annotation complete.");
        } finally {
            deleteRecursive(tempDir);
        }
    }

// ---- shared arg parsing for trace/annotate ----

    private record TraceArgs(Path input, boolean binary, String[] programArgs) {
    }

    private static TraceArgs parseTraceArgs(String[] args, String command) {
        Path input = null;
        boolean binary = false;
        String[] programArgs = new String[0];

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--binary" -> binary = true;
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

        return new TraceArgs(input, binary, programArgs);
    }

// ---- shared helpers ----

    private static String classNameFrom(Path input) {
        return input.getFileName().toString().replace(".java", "");
    }

    private static URLClassLoader createClassLoader(Path classDir, Path runtimeJar)
            throws Exception {
        return new URLClassLoader(
                new URL[]{
                        classDir.toUri().toURL(),
                        runtimeJar.toUri().toURL(),
                },
                ClassLoader.getPlatformClassLoader()
        );
    }

    private static void compile(
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

    private static Path resolveRuntimeJar(boolean binary) {
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

    private static void deleteRecursive(Path path) {
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

    private static class InMemoryJavaFile extends SimpleJavaFileObject {
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
