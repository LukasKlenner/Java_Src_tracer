package srctracer.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static srctracer.tools.ToolsUtils.findProjectRoot;

public class Jazzer {

    /**
     * Starts tools/jazzer/jazzer.exe on {@code className} for {@code durationSeconds}, with the corpus in
     * {@code corpusDir}. Jazzer runs in {@code outputDir} (where it writes its findings) and logs to jazzer.log there.
     */
    public static Process start(String className, Path classDir, Path corpusDir, int durationSeconds, Path outputDir,
                                List<String> extraArgs) throws Exception {
        Path jazzer = findProjectRoot().resolve("tools/jazzer/jazzer.exe");
        if (!Files.exists(jazzer)) throw new RuntimeException("Jazzer not found: " + jazzer);

        List<String> command = new ArrayList<>(List.of(
                jazzer.toAbsolutePath().toString(),
                "--cp=" + classDir.toAbsolutePath(),
                "--target_class=" + className,
                "-max_total_time=" + durationSeconds));
        command.addAll(extraArgs);
        command.add(corpusDir.toAbsolutePath().toString());

        return new ProcessBuilder(command)
                .directory(outputDir.toAbsolutePath().toFile())
                .redirectOutput(outputDir.resolve("jazzer.log").toFile())
                .redirectErrorStream(true)
                .start();
    }
}
