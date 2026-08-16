package srctracer;

import java.io.IOException;
import java.nio.file.Path;

public class KeyAnnotater {

    public static final String JAVA_SOURCE_DIR = "java_source";

    public static void annotate(
            Path inputFile,
            Path outputDir,
            Path traceFile,
            Path functionDatabaseFile
    ) throws IOException {


        KeySourceTransformer annotater = new KeySourceTransformer();
        annotater.transform(inputFile, outputDir.resolve(Path.of(JAVA_SOURCE_DIR, inputFile.getFileName().toString())));

        KeyProofObligationCreator.createProofObligation(outputDir, annotater.getTracedMethod(), traceFile, functionDatabaseFile);
    }

}
