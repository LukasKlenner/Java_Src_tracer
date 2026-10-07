package srctracer.instrumenter.visitors.implicit;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
        // Merge consecutive EvaluateSteps into a single EvaluateStep if possible
        if (!steps.isEmpty() &&
                step instanceof EvaluateStep(String newSlot, Expression newExpression, boolean newIsFinal) &&
                steps.getLast() instanceof EvaluateStep(String oldSlot, Expression oldExpression, boolean oldIsFinal)) {

            boolean isFinal = newIsFinal && oldIsFinal;

            if (newExpression instanceof NameExpr nameExpr && nameExpr.equals(new NameExpr(oldSlot))) {
                steps.set(steps.size() - 1, new EvaluateStep(newSlot, oldExpression.clone(), isFinal));
                return;
            }

            AtomicBoolean replaced = new AtomicBoolean(false);
            newExpression.walk(n -> {
                if (n.equals(new NameExpr(oldSlot))) {
                    n.replace(oldExpression);
                    replaced.set(true);
                }
            });

            if (replaced.get()) {
                // try to propagate the merging further
                steps.removeLast();
                addStep(new EvaluateStep(newSlot, newExpression, isFinal));
                return;
            }
        }

        steps.add(step);
    }

    public void addAll(EvaluationPlan other) {
        other.steps.forEach(this::addStep);
    }
}
