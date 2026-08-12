package srctracer.database;

import srctracer.util.FunctionSignature;

public interface FunctionDatabaseWriter extends AutoCloseable {

    void storeFunctionId(int id, FunctionSignature functionSignature);

}
