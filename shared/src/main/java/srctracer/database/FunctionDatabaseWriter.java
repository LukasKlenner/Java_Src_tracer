package srctracer.database;

public interface FunctionDatabaseWriter extends AutoCloseable {

    void storeFunctionId(int id, String canonicalMethodSignature);

}
