package srctracer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class KeyAnnotater {

    public static final String JAVA_SOURCE_DIR = "java_source";

    public static void annotate(
            ProgramSources sources,
            Path outputDir,
            Path traceFile,
            Path functionDatabaseFile,
            boolean strictRetracing
    ) throws IOException {
        annotate(sources, outputDir, traceFile, functionDatabaseFile, null, strictRetracing);
    }

    /**
     * Writes all source files to {@code <outputDir>/java_source/} (keeping their package directories), with a JML
     * contract on the target method, and creates the proof obligation for that method.
     */
    public static void annotate(
            ProgramSources sources,
            Path outputDir,
            Path traceFile,
            Path functionDatabaseFile,
            String targetMethodName,
            boolean strictRetracing
    ) throws IOException {
        // one transformer for all files: it remembers the traced method of whichever file declares it
        KeySourceTransformer annotater = new KeySourceTransformer(targetMethodName);
        Path javaSourceDir = outputDir.resolve(JAVA_SOURCE_DIR);
        for (ProgramSources.SourceFile file : sources.files()) {
            Path target = javaSourceDir.resolve(file.relativePath());
            Files.createDirectories(target.getParent());
            annotater.transform(file.path(), target);
        }
        if (annotater.getTracedMethod() == null) {
            throw new IllegalArgumentException("Traced method " + (targetMethodName == null ? "main" : targetMethodName)
                    + " not found in " + sources.files().stream().map(ProgramSources.SourceFile::path).toList());
        }

        KeyProofObligationCreator.createProofObligation(outputDir, annotater.getTracedMethod(), traceFile, functionDatabaseFile, strictRetracing);
    }

}
