package srctracer.instrumenter.visitors.implicit;

import com.github.javaparser.ast.expr.Expression;

public sealed interface EvaluationStep
        permits EvaluateStep, CheckStep, BranchStep, NoImplicitExceptionStep {
}

record EvaluateStep(String slot, Expression expression) implements EvaluationStep {
}

record CheckStep(ImplicitCheck check) implements EvaluationStep {
}

record BranchStep(Expression condition, EvaluationPlan thenPlan, EvaluationPlan elsePlan) implements EvaluationStep {
}

record NoImplicitExceptionStep(int count) implements EvaluationStep {
}
