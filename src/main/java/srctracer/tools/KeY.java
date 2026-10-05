package srctracer.tools;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static srctracer.tools.ToolsUtils.findProjectRoot;

public class KeY {

    private static final String KEY_DIR = "tools/key";
    private static final String KEY_JAR = "key.jar";

    private final Path keYProofFile;

    private final String keyMemory;

    public KeY(Path keYProofFile, String keyMemory) {
        this.keYProofFile = keYProofFile;
        this.keyMemory = keyMemory;
    }

    public KeY(Path keYProofFile) {
        this(keYProofFile, null);
    }

    public void runKey() throws Exception {
        Path workingDir = keYProofFile.getParent();
        Path keyLog = workingDir.resolve("key.log");


        System.out.println("Running KeY for " + workingDir.getFileName() + " ... ");

        ProcessBuilder pb = new ProcessBuilder(buildKeYCommand());
        pb.directory(workingDir.toFile());
        pb.redirectOutput(keyLog.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();

        System.out.println("  KeY exited with code " + exitCode + " (log: " + keyLog + ")");
    }

    public boolean isProofClosed() throws Exception {
        Path csvFile = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(keYProofFile.getParent(), "*.csv")) {
            for (Path entry : stream) {
                if (entry.getFileName().toString().endsWith("functions.csv")) {
                    continue;
                }
                csvFile = entry;
                break;
            }
        }

        if (csvFile == null) {
            throw new IllegalStateException("No KeY result CSV found in " + keYProofFile.getParent());
        }

        for (String line : Files.readAllLines(csvFile)) {
            if (line.startsWith("open goals;")) {
                int openGoals = Integer.parseInt(line.substring("open goals;".length()).trim());
                return openGoals == 0;
            }
        }

        throw new IllegalStateException("no 'open goals' entry in " + csvFile);
    }

    private List<String> buildKeYCommand() {
        List<String> command = new ArrayList<>();
        command.add("java");
        if (keyMemory != null) {
            command.add("-Xmx" + keyMemory);
        }
        command.add("-jar");
        command.add(resolveKeyJar().toAbsolutePath().toString());
        command.add("--auto");
        command.add(keYProofFile.toAbsolutePath().toString());
        return command;
    }

    private static Path resolveKeyJar() {
        Path projectRoot = findProjectRoot();
        Path jar = projectRoot.resolve(KEY_DIR).resolve(KEY_JAR);
        if (!Files.exists(jar)) {
            throw new RuntimeException("KeY JAR not found: " + jar
                    + "\nPlace " + KEY_JAR + " in " + projectRoot.resolve(KEY_DIR));
        }
        return jar;
    }

}
