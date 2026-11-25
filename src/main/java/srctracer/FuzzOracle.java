package srctracer;

import java.util.Arrays;
import java.util.stream.Collectors;

/** What flags an input as buggy in the fuzz command. Crashes (uncaught exceptions, timeouts, OOM) always count. */
enum FuzzOracle {

    /** Jazzer on {@code X.java}. */
    CRASH("crash"),
    /** Jazzer on {@code XAssert.java}: failing Java asserts count as well. */
    RUNTIME("runtime"),
    /** Jazzer on {@code X.java}; KeY verifies the contract of the traced method along the trace of every entry. */
    KEY("key");

    private final String cliName;

    FuzzOracle(String cliName) {
        this.cliName = cliName;
    }

    String cliName() {
        return cliName;
    }

    static FuzzOracle fromCliName(String name) {
        return Arrays.stream(values()).filter(o -> o.cliName.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown oracle: " + name + " (expected one of "
                        + Arrays.stream(values()).map(FuzzOracle::cliName).collect(Collectors.joining(", ")) + ")"));
    }
}
