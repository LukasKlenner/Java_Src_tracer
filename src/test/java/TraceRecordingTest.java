import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import srctracer.Main;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class TraceRecordingTest {

    private static final Path RESOURCES_DIR = Path.of("src/test/resources/trace-tests/successful");

    static Stream<Arguments> testCases() throws IOException {
        try (var paths = Files.walk(RESOURCES_DIR)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(RESOURCES_DIR))
                    .filter(dir ->
                            Files.isRegularFile(dir.resolve("Input.java"))
                                    && Files.isRegularFile(
                                    dir.resolve("expected.trace.txt")))
                    .sorted()
                    .map(dir -> Arguments.of(
                            RESOURCES_DIR.relativize(dir).toString().replace("\\", "/"),
                            dir))
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("testCases")
    void testTraceRecording(
            String testName,
            Path testDir,
            @TempDir Path tempDir) throws Exception {

        Path inputFile = testDir.resolve("Input.java");
        Path expectedTraceFile = testDir.resolve("expected.trace.txt");

        if (!Files.exists(inputFile)) {
            fail("Missing Input.java in " + testDir);
        }
        if (!Files.exists(expectedTraceFile)) {
            fail("Missing expected.trace.txt in " + testDir);
        }

        try {
            Main.main(new String[]{
                    "trace",
                    "-o", tempDir.toAbsolutePath().toString(),
                    inputFile.toAbsolutePath().toString()
            });
        } catch (Exception e) {
            System.out.println("Exception during trace recording for test case: " + testName);
            e.printStackTrace();
            // silently ignore exceptions, as we are only interested in the trace output
        }

        Path outputTrace = tempDir.resolve("Input.trace.txt");
        if (!Files.exists(outputTrace)) {
            fail("Missing output trace file: " + outputTrace);
        }

        String actualTrace = getNormalizedTrace(outputTrace);
        String expectedTrace = Files.readString(expectedTraceFile);

        assertEquals(expectedTrace, actualTrace,
                "Trace mismatch for test case: " + testName);

    }

    private static String getNormalizedTrace(Path traceFile) {
        try {
            String trace = Files.readString(traceFile).strip();
            return trace.replaceAll("C[0-9a-f]+", "C*");
        } catch (IOException e) {
            throw new RuntimeException("Failed to read trace file: " + traceFile, e);
        }
    }
}
