package srctracer.database;

public interface FunctionDatabaseReader extends AutoCloseable {

    String getFunctionSignature(int id);

    default boolean isMainFunction(int id) {
        return getFunctionSignature(id).endsWith("#main(String[]):void");
    }

}
