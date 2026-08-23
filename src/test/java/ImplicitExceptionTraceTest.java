import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import srctracer.database.InMemoryFunctionDatabase;
import srctracer.instrumenter.Instrumenter;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class ImplicitExceptionTraceTest {

    private static final Path RESOURCES_DIR = Path.of("src/test/resources/implicit-exception-tests");
    private static final Path RUNTIME_JAR = resolveRuntimeJar();

    static Stream<Arguments> testCases() throws IOException {
        return Files.list(RESOURCES_DIR)
                .filter(Files::isDirectory)
                .sorted()
                .map(dir -> Arguments.of(dir.getFileName().toString(), dir));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    void testImplicitExceptionTrace(String testName, Path testDir) throws Exception {
        Path inputFile = testDir.resolve("Input.java");
        Path expectedTraceFile = testDir.resolve("expected.trace.txt");

        if (!Files.exists(inputFile)) {
            fail("Missing Input.java in " + testDir);
        }
        if (!Files.exists(expectedTraceFile)) {
            fail("Missing expected.trace.txt in " + testDir);
        }

        String actualTrace = instrumentAndRun(inputFile);
        String normalizedActual = normalizeTrace(actualTrace);
        String expectedTrace = Files.readString(expectedTraceFile).strip();

        assertEquals(expectedTrace, normalizedActual,
                "Trace mismatch for test case: " + testName);
    }

    private String instrumentAndRun(Path inputFile) throws Exception {
        String instrumentedSource;
        try (var db = new InMemoryFunctionDatabase()) {
            Instrumenter instrumenter = new Instrumenter(db, List.of(inputFile.getParent()), List.of(RUNTIME_JAR));
            instrumentedSource = instrumenter.transformToString(inputFile);
        }

        String className = inputFile.getFileName().toString().replace(".java", "");
        Path tempDir = Files.createTempDirectory("srctracer-test-");

        try {
            compile(instrumentedSource, className, tempDir);
            return runWithTrace(className, tempDir);
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private void compile(String source, String className, Path outputDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("No Java compiler available. Run tests with a JDK.");
        }

        JavaFileObject sourceFile = new InMemoryJavaFile(className, source);
        List<String> options = List.of(
                "-d", outputDir.toAbsolutePath().toString(),
                "-cp", RUNTIME_JAR.toAbsolutePath().toString()
        );

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, null, null, options, null, List.of(sourceFile));

        if (!task.call()) {
            fail("Compilation failed for instrumented source:\n" + source);
        }
    }

    private String runWithTrace(String className, Path classDir) throws Exception {
        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{
                        classDir.toUri().toURL(),
                        RUNTIME_JAR.toUri().toURL()
                },
                ClassLoader.getPlatformClassLoader()
        )) {
            Class<?> traceClass = cl.loadClass("srctracer.Trace");
            StringWriter traceWriter = new StringWriter();
            traceClass.getMethod("trace_start", Writer.class).invoke(null, traceWriter);

            Class<?> userClass = cl.loadClass(className);
            Method userMain = userClass.getMethod("main", String[].class);
            userMain.setAccessible(true);

            try {
                userMain.invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException e) {
                // Expected for tests that trigger exceptions.
            }

            return traceWriter.toString();
        }
    }

    private static String normalizeTrace(String trace) {
        return trace.replaceAll("C[0-9a-f]+", "C*")
                .replaceAll("J[0-9a-f]+", "J*");
    }

    private static Path resolveRuntimeJar() {
        Path projectRoot = Path.of("").toAbsolutePath();
        Path jar = projectRoot.resolve("runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar");
        if (!Files.exists(jar)) {
            throw new IllegalStateException("Runtime JAR not found: " + jar
                    + "\nRun: ./gradlew :runtime:jar before running tests");
        }
        return jar;
    }

    private static void deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var entries = Files.list(path)) {
                    entries.forEach(ImplicitExceptionTraceTest::deleteRecursive);
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
