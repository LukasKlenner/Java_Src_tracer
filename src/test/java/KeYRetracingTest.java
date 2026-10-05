import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import srctracer.Main;
import srctracer.tools.KeY;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class KeYRetracingTest {

    private static final Path RESOURCES_DIR = Path.of("src/test/resources/trace-tests/successful");

    private static final Path FAILED_TESTS_DIR =
            Path.of("build", "failed-test-artifacts");

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
                    "annotate",
                    "-o", tempDir.toAbsolutePath().toString(),
                    inputFile.toAbsolutePath().toString()
            });
        } catch (Exception e) {
            System.out.println("Exception during trace recording for test case: " + testName);
            // silently ignore exceptions, as we are only interested in the trace output
        }

        Path proofFile = tempDir.resolve("proof.key");

        KeY key = new KeY(proofFile);
        key.runKey();

        if (!key.isProofClosed()) {

            Path preserved = Path.of(
                    "build",
                    "failed-test-artifacts",
                    testName
            );

            copyDirectory(tempDir, preserved);

            System.err.println(
                    "Preserved failed test artifacts in: " +
                            preserved.toAbsolutePath()
            );

            fail("At least one goal is not closed.");
        }

    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            deleteRecursively(target);
        }

        Files.createDirectories(target);

        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);

                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(
                            path,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES
                    );
                }
            }
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

}
