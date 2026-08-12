package srctracer.database;

import srctracer.util.FunctionSignature;

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
    public void storeFunctionId(int id, FunctionSignature signature) {
        writer.println(id + "," + signature);
    }

    @Override
    public void close() {
        writer.close();
    }
}
