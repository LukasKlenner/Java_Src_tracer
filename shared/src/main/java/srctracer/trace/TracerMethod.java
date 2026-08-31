package srctracer.trace;

public enum TracerMethod {

    FUNCTION_CALL("_FUNC", 1),
    IF("_IF", 0),
    ELSE("_ELSE", 0),
    LOOP_BODY("_LOOP_BODY", 0),
    LOOP_END("_LOOP_END", 0),
    BREAK("_BREAK", 0),
    RETURN("_RETURN", 0),
    CASE("_CASE", 1),
    TRY("_TRY", 1),
    TRY_END("_TRY_END", 0),
    CATCH("_CATCH", 1),
    IMPLICIT_EXCEPTION("_IMPLICIT_EXCEPTION", 0),
    NO_IMPLICIT_EXCEPTION("_NO_IMPLICIT_EXCEPTION", 0),
    TRACE_START("trace_start", 1),
    TRACE_END("trace_end", 0);

    private final String methodName;

    private final int numArgs;

    TracerMethod(String methodName, int numArgs) {
        this.methodName = methodName;
        this.numArgs = numArgs;
    }

    public String getMethodCallString(Object... args) {

        if (args.length != numArgs) {
            throw new IllegalArgumentException("Expected " + numArgs + " arguments, but got " + args.length);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("srctracer.Trace.").append(methodName).append("(");
        for (int i = 0; i < numArgs; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        sb.append(");");
        return sb.toString();
    }
}
