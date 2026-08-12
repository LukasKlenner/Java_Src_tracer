package srctracer;

import srctracer.database.FunctionDatabaseReader;

import java.io.IOException;
import java.nio.file.Path;

public class KeyAnnotater {

    public static final String JAVA_SOURCE_DIR = "java_source";

    public static void annotate(
            Path inputFile,
            Path outputDir,
            Path traceFile,
            FunctionDatabaseReader functionDatabaseReader) throws IOException {


        KeySourceTransformer annotater = new KeySourceTransformer(traceFile, functionDatabaseReader);
        annotater.transform(inputFile, outputDir.resolve(Path.of(JAVA_SOURCE_DIR, inputFile.getFileName().toString())));

        KeyProofObligationCreator.createProofObligation(outputDir, annotater.getTracedMethod());
    }

}
