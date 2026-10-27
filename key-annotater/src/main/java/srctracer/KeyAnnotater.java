package srctracer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class KeyAnnotater {

    public static final String JAVA_SOURCE_DIR = "java_source";

    public static void annotate(
            Path inputFile,
            Path outputDir,
            Path traceFile,
            Path functionDatabaseFile,
            boolean strictRetracing
    ) throws IOException {
        annotate(inputFile, outputDir, traceFile, functionDatabaseFile, null, strictRetracing);
    }

    public static void annotate(
            Path inputFile,
            Path outputDir,
            Path traceFile,
            Path functionDatabaseFile,
            String targetMethodName,
            boolean strictRetracing
    ) throws IOException {
        KeySourceTransformer annotater = new KeySourceTransformer(targetMethodName);
        Files.createDirectories(outputDir.resolve(JAVA_SOURCE_DIR));
        annotater.transform(inputFile, outputDir.resolve(Path.of(JAVA_SOURCE_DIR, inputFile.getFileName().toString())));

        KeyProofObligationCreator.createProofObligation(outputDir, annotater.getTracedMethod(), traceFile, functionDatabaseFile, strictRetracing);
    }

}
