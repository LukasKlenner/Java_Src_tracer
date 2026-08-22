package srctracer.instrumenter.visitors.implicit;

import com.github.javaparser.ast.expr.Expression;

import java.util.ArrayList;
import java.util.List;

public final class EvaluationPlan {
    private final List<EvaluationStep> steps = new ArrayList<>();
    private Expression result;

    public List<EvaluationStep> getSteps() {
        return steps;
    }

    public Expression getResult() {
        return result;
    }

    public void setResult(Expression result) {
        this.result = result;
    }

    public void addStep(EvaluationStep step) {
        steps.add(step);
    }

    public void addAll(EvaluationPlan other) {
        steps.addAll(other.steps);
    }
}
