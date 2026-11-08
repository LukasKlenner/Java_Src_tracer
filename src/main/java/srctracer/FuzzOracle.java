package srctracer;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Selects which oracle(s) the fuzz command uses to flag an input as buggy.
 * Jazzer always reports crashes (uncaught exceptions, timeouts, OOM); the oracle decides
 * which source Jazzer fuzzes and whether corpus entries are verified with KeY.
 */
enum FuzzOracle {

    /** Jazzer on {@code X.java}; only crashes count. */
    CRASH("crash", false, false),
    /** Jazzer on {@code X.java}; for every corpus entry the method with a JML contract is traced and verified with KeY. */
    KEY("key", false, true),
    /** Jazzer on {@code XAssert.java} (Java runtime assertions). */
    RUNTIME("runtime", true, false),
    /** Jazzer on {@code XAssert.java}, plus KeY verification of every corpus entry. */
    RUNTIME_KEY("runtime+key", true, true);

    private final String cliName;
    private final boolean fuzzAssertVariant;
    private final boolean verifyWithKey;

    FuzzOracle(String cliName, boolean fuzzAssertVariant, boolean verifyWithKey) {
        this.cliName = cliName;
        this.fuzzAssertVariant = fuzzAssertVariant;
        this.verifyWithKey = verifyWithKey;
    }

    String cliName() {
        return cliName;
    }

    boolean fuzzAssertVariant() {
        return fuzzAssertVariant;
    }

    boolean verifyWithKey() {
        return verifyWithKey;
    }

    static FuzzOracle fromCliName(String name) {
        for (FuzzOracle oracle : values()) {
            if (oracle.cliName.equals(name)) return oracle;
        }
        throw new IllegalArgumentException("Unknown oracle: " + name + " (expected one of "
                + Arrays.stream(values()).map(FuzzOracle::cliName).collect(Collectors.joining(", ")) + ")");
    }
}
