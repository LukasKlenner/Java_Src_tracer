package srctracer.instrumenter.visitors.implicit;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import srctracer.trace.TracerMethod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.github.javaparser.StaticJavaParser.parseExpression;
import static com.github.javaparser.StaticJavaParser.parseStatement;

public final class EvaluationPlanRewriter {
    public record RewriteResult(NodeList<Statement> statements, Expression result, int nextTmpId) {
    }

    private static final class TmpCounter {
        int value;

        private TmpCounter(int value) {
            this.value = value;
        }
    }

    private static final class RewriteContext {
        private final TmpCounter tmpCounter;
        private final Map<String, String> slotToVar;
        private final Set<String> declaredSlots;

        private RewriteContext(int nextTmpId) {
            this.tmpCounter = new TmpCounter(nextTmpId);
            this.slotToVar = new HashMap<>();
            this.declaredSlots = new HashSet<>();
        }

        private RewriteContext(RewriteContext parent) {
            this.tmpCounter = parent.tmpCounter;
            this.slotToVar = new HashMap<>(parent.slotToVar);
            this.declaredSlots = new HashSet<>(parent.declaredSlots);
        }
    }

    public RewriteResult rewrite(EvaluationPlan plan, int nextTmpId) {
        RewriteContext context = new RewriteContext(nextTmpId);
        NodeList<Statement> statements = new NodeList<>();
        rewritePlan(plan, statements, context);
        Expression rewrittenResult = substituteSlots(plan.getResult(), context);
        return new RewriteResult(statements, rewrittenResult, context.tmpCounter.value);
    }

    private void rewritePlan(EvaluationPlan plan, NodeList<Statement> out, RewriteContext context) {
        for (EvaluationStep step : plan.getSteps()) {
            switch (step) {
                case EvaluateStep evaluateStep -> rewriteEvaluateStep(evaluateStep, out, context);
                case CheckStep checkStep -> out.add(rewriteCheck(checkStep.check(), context));
                case BranchStep branchStep -> rewriteBranchStep(branchStep, out, context);
                case NoImplicitExceptionStep noImplicitExceptionStep -> {
                    for (int i = 0; i < noImplicitExceptionStep.count(); i++) {
                        out.add(parseStatement(TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString()));
                    }
                }
            }
        }
    }

    private void rewriteEvaluateStep(EvaluateStep step, NodeList<Statement> out, RewriteContext context) {
        String variableName = context.slotToVar.computeIfAbsent(
                step.slot(),
                ignored -> "__srctracer_tmp$" + context.tmpCounter.value++
        );
        Expression expression = substituteSlots(step.expression(), context);

        if (context.declaredSlots.contains(step.slot())) {
            out.add(parseStatement(variableName + " = " + expression + ";"));
        } else {
            out.add(parseStatement("var " + variableName + " = " + expression + ";"));
            context.declaredSlots.add(step.slot());
        }
    }

    private void rewriteBranchStep(BranchStep step, NodeList<Statement> out, RewriteContext context) {
        RewriteContext thenContext = new RewriteContext(context);
        RewriteContext elseContext = new RewriteContext(context);

        NodeList<Statement> thenStatements = new NodeList<>();
        NodeList<Statement> elseStatements = new NodeList<>();
        rewritePlan(step.thenPlan(), thenStatements, thenContext);
        rewritePlan(step.elsePlan(), elseStatements, elseContext);

        Expression condition = substituteSlots(step.condition(), context);

        BlockStmt thenBlock = new BlockStmt(thenStatements);
        BlockStmt elseBlock = new BlockStmt(elseStatements);
        out.add(new IfStmt(condition, thenBlock, elseBlock));

        context.tmpCounter.value = Math.max(thenContext.tmpCounter.value, elseContext.tmpCounter.value);
    }

    private static Statement rewriteCheck(ImplicitCheck check, RewriteContext context) {
        return switch (check) {
            case NullCheck nullCheck -> parseStatement(
                    "if (" + substituteSlots(nullCheck.value(), context) + " == null) { "
                            + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString()
                            + " throw new java.lang.NullPointerException(); } else { "
                            + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }"
            );
            case ArrayBoundsCheck arrayBoundsCheck -> {
                Expression array = substituteSlots(arrayBoundsCheck.array(), context);
                Expression index = substituteSlots(arrayBoundsCheck.index(), context);
                yield parseStatement(
                        "if (" + index + " < 0 || " + index + " >= " + array + ".length) { "
                                + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString()
                                + " throw new java.lang.ArrayIndexOutOfBoundsException(); } else { "
                                + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }"
                );
            }
            case ArrayStoreCheck arrayStoreCheck -> {
                Expression array = substituteSlots(arrayStoreCheck.array(), context);
                Expression value = substituteSlots(arrayStoreCheck.value(), context);
                yield parseStatement(
                        "if (!" + array + ".getClass().getComponentType().isInstance(" + value + ")) { "
                                + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString()
                                + " throw new java.lang.ArrayStoreException(); } else { "
                                + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }"
                );
            }
            case DivisionByZeroCheck divisionByZeroCheck -> parseStatement(
                    "if (" + substituteSlots(divisionByZeroCheck.divisor(), context) + " == 0) { "
                            + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString()
                            + " throw new java.lang.ArithmeticException(); } else { "
                            + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }"
            );
            case NegativeArraySizeCheck negativeArraySizeCheck -> parseStatement(
                    "if (" + substituteSlots(negativeArraySizeCheck.size(), context) + " < 0) { "
                            + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString()
                            + " throw new java.lang.NegativeArraySizeException(); } else { "
                            + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }"
            );
            case CastCheck castCheck -> {
                Expression value = substituteSlots(castCheck.value(), context);
                yield parseStatement(
                        "if (" + value + " != null && !(" + value + " instanceof " + castCheck.targetType() + ")) { "
                                + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString()
                                + " throw new java.lang.ClassCastException(); } else { "
                                + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }"
                );
            }
        };
    }

    private static Expression substituteSlots(Expression expression, RewriteContext context) {
        Expression clone = expression.clone();
        clone.findAll(NameExpr.class).forEach(nameExpr -> {
            String replacement = context.slotToVar.get(nameExpr.getNameAsString());
            if (replacement != null) {
                nameExpr.replace(parseExpression(replacement));
            }
        });
        String wholeExprReplacement = context.slotToVar.get(clone.toString());
        if (wholeExprReplacement != null) {
            return parseExpression(wholeExprReplacement);
        }
        return clone;
    }
}
