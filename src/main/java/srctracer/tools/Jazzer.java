package srctracer.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static srctracer.tools.ToolsUtils.findProjectRoot;

public class Jazzer {

    private static final String JAZZER_DIR = "tools/jazzer";
    private static final String JAZZER_EXE = "jazzer.exe";

    public static Process startJazzerAsync(
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

    private static Path resolveJazzer() {
        Path projectRoot = findProjectRoot();
        Path jazzer = projectRoot.resolve(JAZZER_DIR).resolve(JAZZER_EXE);
        if (!Files.exists(jazzer)) {
            throw new RuntimeException("Jazzer not found: " + jazzer
                    + "\nPlace jazzer.exe in " + projectRoot.resolve(JAZZER_DIR));
        }
        return jazzer;
    }

}
