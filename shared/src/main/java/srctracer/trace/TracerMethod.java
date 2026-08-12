package srctracer.trace;

public enum TracerMethod {

    FUNCTION_CALL("_FUNC", 1, new Class<?>[]{Integer.class}),
    IF("_IF", 0, new Class<?>[0]),
    ELSE("_ELSE", 0, new Class<?>[0]),
    LOOP_BODY("_LOOP_BODY", 0, new Class<?>[0]),
    LOOP_END("_LOOP_END", 0, new Class<?>[0]),
    BREAK("_BREAK", 0, new Class<?>[0]),
    RETURN("_RETURN", 0, new Class<?>[0]),
    CASE("_CASE", 1, new Class<?>[]{Integer.class}),
    TRY("_TRY", 0, new Class<?>[0]),
    TRY_END("_TRY_END", 0, new Class<?>[0]),
    CATCH("_CATCH", 1, new Class<?>[]{Integer.class}),
    IMPLICIT_EXCEPTION("_IMPLICIT_EXCEPTION", 0, new Class<?>[0]),
    NO_IMPLICIT_EXCEPTION("_NO_IMPLICIT_EXCEPTION", 0, new Class<?>[0]),
    TRACE_START("trace_start", 1, new Class<?>[]{String.class}),
    TRACE_END("trace_end", 0, new Class<?>[0]);

    private final String methodName;

    private final int numArgs;

    private final Class<?>[] argTypes;

    TracerMethod(String methodName, int numArgs, Class<?>[] argTypes) {
        this.methodName = methodName;
        this.numArgs = numArgs;
        this.argTypes = argTypes;
    }

    public String getMethodCallString(Object... args) {

        if (args.length != numArgs) {
            throw new IllegalArgumentException("Expected " + numArgs + " arguments, but got " + args.length);
        }

        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                if (!argTypes[i].isInstance(args[i])) {
                    throw new IllegalArgumentException("Argument " + i + " is not of type " + argTypes[i].getName());
                }
            }
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
