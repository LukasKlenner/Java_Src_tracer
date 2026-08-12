package srctracer.database;

import java.util.HashMap;
import java.util.Map;

public class InMemoryFunctionDatabase implements FunctionDatabaseReader, FunctionDatabaseWriter {

    private final Map<Integer, String> functionMap = new HashMap<>();

    @Override
    public void storeFunctionId(int id, String canonicalMethodSignature) {
        functionMap.put(id, canonicalMethodSignature);
    }

    @Override
    public String getFunctionSignature(int id) {
        return functionMap.get(id);
    }

    @Override
    public void close() {
        // No resources to close for in-memory implementation
    }
}
