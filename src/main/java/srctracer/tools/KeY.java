package srctracer.tools;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static srctracer.tools.ToolsUtils.findProjectRoot;

public class KeY {

    private static final String KEY_DIR = "tools/key";

    private final Path keYProofFile;

    private final String keyMemory;

    public KeY(Path keYProofFile, String keyMemory) {
        this.keYProofFile = keYProofFile;
        this.keyMemory = keyMemory;
    }

    public KeY(Path keYProofFile) {
        this(keYProofFile, null);
    }

    /**
     * {@code STEP_LIMIT}: open goals remain, but KeY stopped because it reached the
     * {@code MaximumNumberOfAutomaticApplications} limit of the proof settings, so the open goals do not
     * necessarily indicate a bug.
     */
    public enum ProofStatus { CLOSED, OPEN, STEP_LIMIT, KEY_ERROR, KEY_TIMEOUT }

    /**
     * @param openGoals      number of open goals, or {@code null} if KeY produced no statistics
     * @param automodeMillis KeY automode time, or {@code null} if not reported
     * @param nodes          number of proof nodes, or {@code null} if not reported
     */
    public record ProofResult(ProofStatus status, Integer openGoals, Long automodeMillis, Long nodes) {
    }

    private static final Pattern MAX_APPLICATIONS_SETTING = Pattern.compile("\"MaximumNumberOfAutomaticApplications\"\\s*:\\s*(\\d+)");

    private boolean timedOut = false;

    private Process process;

    private boolean destroyed = false;

    public void runKey() throws Exception {
        runKey(null);
    }

    /**
     * Runs KeY on the proof file. If {@code timeoutSeconds} is non-null and KeY does not finish in time,
     * the process is killed and {@link #readProofResult()} reports {@link ProofStatus#KEY_TIMEOUT}.
     */
    public void runKey(Integer timeoutSeconds) throws Exception {
        Path workingDir = keYProofFile.getParent();
        Path keyLog = workingDir.resolve("key.log");


        System.out.println("Running KeY for " + workingDir.getFileName() + " ... ");

        ProcessBuilder pb = new ProcessBuilder(buildKeYCommand());
        pb.directory(workingDir.toFile());
        pb.redirectOutput(keyLog.toFile());
        pb.redirectErrorStream(true);
        Process process;
        synchronized (this) {
            if (destroyed) return;
            process = pb.start();
            this.process = process;
        }

        if (timeoutSeconds != null && !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            timedOut = true;
            process.destroyForcibly();
            process.waitFor();
            System.out.println("  KeY timed out after " + timeoutSeconds + "s (log: " + keyLog + ")");
            return;
        }
        int exitCode = process.waitFor();

        System.out.println("  KeY exited with code " + exitCode + " (log: " + keyLog + ")");
    }

    /**
     * Kills the KeY process (also if called from another thread before {@link #runKey(Integer)} started it),
     * so {@link #runKey(Integer)} returns. The proof result is meaningless afterwards.
     */
    public synchronized void destroy() {
        destroyed = true;
        if (process != null) process.destroyForcibly();
    }

    public boolean isProofClosed() throws Exception {
        Path csvFile = findStatisticsCsv();

        if (csvFile == null) {
            throw new IllegalStateException("No KeY result CSV found in " + keYProofFile.getParent());
        }

        Integer openGoals = parseStatistics(csvFile).openGoals();
        if (openGoals == null) {
            throw new IllegalStateException("no 'open goals' entry in " + csvFile);
        }
        return openGoals == 0;
    }

    /**
     * Like {@link #isProofClosed()}, but never throws for a missing or malformed statistics file;
     * such cases are reported as {@link ProofStatus#KEY_ERROR}.
     */
    public ProofResult readProofResult() throws Exception {
        if (timedOut) {
            return new ProofResult(ProofStatus.KEY_TIMEOUT, null, null, null);
        }
        Path csvFile = findStatisticsCsv();
        if (csvFile == null) {
            return new ProofResult(ProofStatus.KEY_ERROR, null, null, null);
        }
        ProofResult result = parseStatistics(csvFile);
        Long maxApplications = readMaxAutomaticApplications();
        if (result.status() == ProofStatus.OPEN && result.nodes() != null
                && maxApplications != null && result.nodes() >= maxApplications) {
            return new ProofResult(ProofStatus.STEP_LIMIT, result.openGoals(), result.automodeMillis(), result.nodes());
        }
        return result;
    }

    private Long readMaxAutomaticApplications() throws Exception {
        Matcher m = MAX_APPLICATIONS_SETTING.matcher(Files.readString(keYProofFile));
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    private Path findStatisticsCsv() throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(keYProofFile.getParent(), "*.csv")) {
            for (Path entry : stream) {
                if (entry.getFileName().toString().endsWith("functions.csv")) {
                    continue;
                }
                return entry;
            }
        }
        return null;
    }

    private static ProofResult parseStatistics(Path csvFile) throws Exception {
        Integer openGoals = null;
        Long automodeMillis = null;
        Long nodes = null;
        for (String line : Files.readAllLines(csvFile)) {
            if (line.startsWith("open goals;")) {
                openGoals = Integer.parseInt(line.substring("open goals;".length()).trim());
            } else if (line.startsWith("Nodes;")) {
                // KeY formats large numbers with thousands separators, e.g. "100,001"
                nodes = Long.parseLong(line.substring("Nodes;".length()).trim().replace(",", ""));
            } else if (line.startsWith("Automode time;")) {
                String value = line.substring("Automode time;".length()).trim();
                if (value.endsWith("ms")) value = value.substring(0, value.length() - 2);
                try {
                    automodeMillis = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (openGoals == null) {
            return new ProofResult(ProofStatus.KEY_ERROR, null, automodeMillis, nodes);
        }
        return new ProofResult(openGoals == 0 ? ProofStatus.CLOSED : ProofStatus.OPEN, openGoals, automodeMillis, nodes);
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
        command.add("--smt-open-goals");
        command.add(keYProofFile.toAbsolutePath().toString());
        return command;
    }

    private static Path resolveKeyJar() {
        Path keyDir = findProjectRoot().resolve(KEY_DIR);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(keyDir, "key*exe*.jar")) {
            for (Path entry : stream) {
                return entry;
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not scan for KeY JAR in " + keyDir, e);
        }
        throw new RuntimeException("No KeY JAR found in " + keyDir + "\nPlace a key*.jar file there.");
    }

}
