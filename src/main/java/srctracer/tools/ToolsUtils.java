package srctracer.tools;

import java.nio.file.Files;
import java.nio.file.Path;

class ToolsUtils {

    public static Path findProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir;
            dir = dir.getParent();
        }
        throw new RuntimeException("Cannot find project root (no settings.gradle.kts found)");
    }

}
