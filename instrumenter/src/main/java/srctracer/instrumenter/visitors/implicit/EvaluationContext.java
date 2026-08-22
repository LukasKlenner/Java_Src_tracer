package srctracer.instrumenter.visitors.implicit;

public enum EvaluationContext {
    NORMAL,
    CONDITION,
    LOOP_CONDITION,
    RETURN_VALUE,
    ASSIGNMENT_VALUE,
    ARRAY_INDEX
}
