package srctracer.database;

import java.io.BufferedOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public class CsvFunctionDatabaseWriter implements FunctionDatabaseWriter {

    private final PrintWriter writer;

    public CsvFunctionDatabaseWriter(Path outputFile) {
        try {
            this.writer = new PrintWriter(new BufferedOutputStream(Files.newOutputStream(outputFile)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create output writer for file: " + outputFile, e);
        }
    }

    @Override
    public void storeFunctionId(int id, String canonicalMethodSignature) {
        writer.println(id + "," + canonicalMethodSignature);
    }

    @Override
    public void close() {
        writer.close();
    }
}
