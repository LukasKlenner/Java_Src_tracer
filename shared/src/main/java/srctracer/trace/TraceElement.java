package srctracer.trace;

import srctracer.database.FunctionDatabaseReader;
import srctracer.util.FunctionHash;

public sealed interface TraceElement {

    boolean createsRequiresString();

    String asRequiresString(int index, FunctionDatabaseReader functionDatabaseReader);

    record Call(int functionID) implements TraceElement {

        @Override
        public boolean createsRequiresString() {
            return true;
        }

        @Override
        public String asRequiresString(int index, FunctionDatabaseReader functionDatabaseReader) {
            String functionHash = FunctionHash.computeFunctionHash(functionDatabaseReader.getFunctionSignature(functionID));
            return "\\dl_traceCall(" + index + ",\"" + functionHash + "\")";
        }
    }

    record Catch(int catchIndex) implements TraceElement {

        @Override
        public boolean createsRequiresString() {
            return true;
        }

        @Override
        public String asRequiresString(int index, FunctionDatabaseReader functionDatabaseReader) {
            return "\\dl_traceCatch(" + index + ")";
        }
    }

    record If() implements TraceElement {

        @Override
        public boolean createsRequiresString() {
            return true;
        }

        @Override
        public String asRequiresString(int index, FunctionDatabaseReader functionDatabaseReader) {
            return "\\dl_traceIfTrue(" + index + ")";
        }
    }

    record Else() implements TraceElement {

        @Override
        public boolean createsRequiresString() {
            return true;
        }

        @Override
        public String asRequiresString(int index, FunctionDatabaseReader functionDatabaseReader) {
            return "\\dl_traceIfFalse(" + index + ")";
        }
    }

    record Return() implements TraceElement {

        @Override
        public boolean createsRequiresString() {
            return false;
        }

        @Override
        public String asRequiresString(int index, FunctionDatabaseReader functionDatabaseReader) {
            throw new UnsupportedOperationException();
        }
    }

    record Try() implements TraceElement {

        @Override
        public boolean createsRequiresString() {
            return false;
        }

        @Override
        public String asRequiresString(int index, FunctionDatabaseReader functionDatabaseReader) {
            throw new UnsupportedOperationException();
        }
    }

    record TryEnd() implements TraceElement {

        @Override
        public boolean createsRequiresString() {
            return false;
        }

        @Override
        public String asRequiresString(int index, FunctionDatabaseReader functionDatabaseReader) {
            throw new UnsupportedOperationException();
        }
    }

    record END() implements TraceElement {

        @Override
        public boolean createsRequiresString() {
            return false;
        }

        @Override
        public String asRequiresString(int index, FunctionDatabaseReader functionDatabaseReader) {
            throw new UnsupportedOperationException();
        }
    }
}
