package srctracer.instrumenter.visitors.implicit;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;

public final class ImplicitExceptionAnalyzer {
    private static final String SLOT_PREFIX = "__srctracer_evalslot$";
    private int nextSlotId = 0;

    public EvaluationPlan analyzeExpression(Expression expression, EvaluationContext context) {
        return switch (expression) {
            case MethodCallExpr methodCallExpr -> analyzeMethodCall(methodCallExpr, context);
            case FieldAccessExpr fieldAccessExpr -> analyzeFieldAccess(fieldAccessExpr, context);
            case ArrayAccessExpr arrayAccessExpr -> analyzeArrayAccess(arrayAccessExpr, context);
            case VariableDeclarationExpr variableDeclarationExpr ->
                    analyzeVariableDeclaration(variableDeclarationExpr, context);
            case AssignExpr assignExpr -> analyzeAssign(assignExpr, context);
            case CastExpr castExpr -> analyzeCast(castExpr, context);
            case ArrayCreationExpr arrayCreationExpr -> analyzeArrayCreation(arrayCreationExpr, context);
            case ObjectCreationExpr objectCreationExpr -> analyzeObjectCreation(objectCreationExpr, context);
            case ArrayInitializerExpr arrayInitializerExpr -> analyzeArrayInitializer(arrayInitializerExpr, context);
            case BinaryExpr binaryExpr -> analyzeBinary(binaryExpr, context);
            case UnaryExpr unaryExpr -> analyzeUnary(unaryExpr, context);
            case EnclosedExpr enclosedExpr -> analyzeEnclosed(enclosedExpr, context);
            default -> {
                EvaluationPlan plan = new EvaluationPlan();
                plan.setResult(expression.clone());
                yield plan;
            }
        };
    }

    private EvaluationPlan analyzeMethodCall(MethodCallExpr expression, EvaluationContext context) {
        MethodCallExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        for (int i = 0; i < rewritten.getArguments().size(); i++) {
            Expression argValue = extractToValue(expression.getArgument(i), plan, EvaluationContext.ASSIGNMENT_VALUE);
            rewritten.setArgument(i, argValue);
        }

        if (expression.getScope().isPresent() && !isNullSafe(expression)) {
            Expression scopeValue = extractToValue(expression.getScope().get(), plan, EvaluationContext.NORMAL);
            plan.addStep(new CheckStep(new NullCheck(scopeValue.clone())));
            rewritten.setScope(scopeValue);
        }

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeFieldAccess(FieldAccessExpr expression, EvaluationContext context) {
        FieldAccessExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        if (!isNullSafe(expression)) {
            Expression scopeValue = extractToValue(expression.getScope(), plan, EvaluationContext.NORMAL);
            plan.addStep(new CheckStep(new NullCheck(scopeValue.clone())));
            rewritten.setScope(scopeValue);
        }

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeArrayAccess(ArrayAccessExpr expression, EvaluationContext context) {
        ArrayAccessExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        Expression arrayValue = extractToValue(expression.getName(), plan, EvaluationContext.NORMAL);
        Expression indexValue = extractToValue(expression.getIndex(), plan, EvaluationContext.ARRAY_INDEX);

        plan.addStep(new CheckStep(new NullCheck(arrayValue.clone())));
        plan.addStep(new CheckStep(new ArrayBoundsCheck(arrayValue.clone(), indexValue.clone())));

        rewritten.setName(arrayValue);
        rewritten.setIndex(indexValue);

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeVariableDeclaration(VariableDeclarationExpr expression, EvaluationContext context) {
        VariableDeclarationExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        for (int i = 0; i < rewritten.getVariables().size(); i++) {
            if (rewritten.getVariable(i).getInitializer().isPresent()) {
                Expression init = extractToValue(
                        expression.getVariable(i).getInitializer().get(),
                        plan,
                        EvaluationContext.ASSIGNMENT_VALUE
                );
                rewritten.getVariable(i).setInitializer(init);
            }
        }

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeAssign(AssignExpr expression, EvaluationContext context) {
        AssignExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        Expression originalTarget = expression.getTarget();
        if (rewritten.getTarget() instanceof FieldAccessExpr fieldAccessExpr
                && originalTarget instanceof FieldAccessExpr originalFieldAccessExpr
                && !isNullSafe(originalFieldAccessExpr)) {
            Expression scopeValue = extractToValue(originalFieldAccessExpr.getScope(), plan, EvaluationContext.NORMAL);
            plan.addStep(new CheckStep(new NullCheck(scopeValue.clone())));
            fieldAccessExpr.setScope(scopeValue);
        } else if (rewritten.getTarget() instanceof ArrayAccessExpr arrayAccessExpr
                && originalTarget instanceof ArrayAccessExpr originalArrayAccessExpr) {
            Expression arrayValue = extractToValue(originalArrayAccessExpr.getName(), plan, EvaluationContext.NORMAL);
            Expression indexValue = extractToValue(originalArrayAccessExpr.getIndex(), plan, EvaluationContext.ARRAY_INDEX);
            plan.addStep(new CheckStep(new NullCheck(arrayValue.clone())));
            plan.addStep(new CheckStep(new ArrayBoundsCheck(arrayValue.clone(), indexValue.clone())));
            arrayAccessExpr.setName(arrayValue);
            arrayAccessExpr.setIndex(indexValue);
        }

        Expression value = extractToValue(expression.getValue(), plan, EvaluationContext.ASSIGNMENT_VALUE);
        rewritten.setValue(value);
        if (expression.getTarget() instanceof ArrayAccessExpr arrayAccessExpr &&
                arrayAccessExpr.getName().calculateResolvedType().asArrayType().getComponentType().isReferenceType()) {
            plan.addStep(new CheckStep(new ArrayStoreCheck(arrayAccessExpr.getName().clone(), value.clone())));
        }

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeCast(CastExpr expression, EvaluationContext context) {
        CastExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        Expression value = extractToValue(expression.getExpression(), plan, EvaluationContext.NORMAL);
        plan.addStep(new CheckStep(new CastCheck(value.clone(), rewritten.getType().asString())));
        rewritten.setExpression(value);

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeArrayCreation(ArrayCreationExpr expression, EvaluationContext context) {
        ArrayCreationExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        for (int i = 0; i < rewritten.getLevels().size(); i++) {
            ArrayCreationLevel level = rewritten.getLevels().get(i);
            if (expression.getLevels().get(i).getDimension().isPresent()) {
                Expression dimValue = extractToValue(expression.getLevels().get(i).getDimension().get(), plan, EvaluationContext.NORMAL);
                plan.addStep(new CheckStep(new NegativeArraySizeCheck(dimValue.clone())));
                level.setDimension(dimValue);
            }
        }

        // TODO wie viele bei mehreren Dimensionen? 1 pro Dimension?
        plan.addStep(new NoImplicitExceptionStep(1));

        if (expression.getInitializer().isPresent()) {
            EvaluationPlan initPlan = analyzeArrayInitializer(expression.getInitializer().get(), EvaluationContext.NORMAL);
            plan.addAll(initPlan);
            rewritten.setInitializer((ArrayInitializerExpr) initPlan.getResult());
        }

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeObjectCreation(ObjectCreationExpr expression, EvaluationContext context) {
        ObjectCreationExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        for (int i = 0; i < rewritten.getArguments().size(); i++) {
            Expression argValue = extractToValue(expression.getArgument(i), plan, EvaluationContext.ASSIGNMENT_VALUE);
            rewritten.setArgument(i, argValue);
        }

        plan.addStep(new NoImplicitExceptionStep(1));
        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeArrayInitializer(ArrayInitializerExpr expression, EvaluationContext context) {
        ArrayInitializerExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        if (!(expression.getParentNode().get() instanceof ArrayCreationExpr ace)) {
            throw new IllegalStateException("ArrayInitializerExpr must be a child of ArrayCreationExpr");
        }

        // no array store check for primitive types
        int implicitChecksPerElement = ace.getElementType().isPrimitiveType() ? 2 : 3;

        for (int i = 0; i < rewritten.getValues().size(); i++) {
            Expression value = extractToValue(expression.getValues().get(i), plan, EvaluationContext.ASSIGNMENT_VALUE);
            rewritten.getValues().set(i, value);

            plan.addStep(new NoImplicitExceptionStep(implicitChecksPerElement));
        }

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeBinary(BinaryExpr expression, EvaluationContext context) {
        return switch (expression.getOperator()) {
            case AND, OR -> analyzeShortCircuitBinary(expression, context);
            case DIVIDE, REMAINDER -> analyzeDivisionBinary(expression, context);
            default -> analyzeRegularBinary(expression, context);
        };
    }

    private EvaluationPlan analyzeShortCircuitBinary(BinaryExpr expression, EvaluationContext context) {
        EvaluationPlan plan = new EvaluationPlan();
        BinaryExpr.Operator operator = expression.getOperator();

        Expression leftValue = extractToValue(expression.getLeft(), plan, context);
        String resultSlot = newSlot();
        plan.addStep(new EvaluateStep(resultSlot, leftValue.clone()));

        if (operator == BinaryExpr.Operator.AND) {
            EvaluationPlan thenPlan = new EvaluationPlan();
            Expression rightValue = extractToValue(expression.getRight(), thenPlan, context);
            thenPlan.addStep(new EvaluateStep(resultSlot, rightValue));
            plan.addStep(new BranchStep(leftValue.clone(), thenPlan, new EvaluationPlan()));
        } else {
            EvaluationPlan elsePlan = new EvaluationPlan();
            Expression rightValue = extractToValue(expression.getRight(), elsePlan, context);
            elsePlan.addStep(new EvaluateStep(resultSlot, rightValue));
            plan.addStep(new BranchStep(leftValue.clone(), new EvaluationPlan(), elsePlan));
        }

        plan.setResult(new NameExpr(resultSlot));
        return plan;
    }

    private EvaluationPlan analyzeDivisionBinary(BinaryExpr expression, EvaluationContext context) {
        BinaryExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        Expression leftValue = extractToValue(expression.getLeft(), plan, context);
        Expression rightValue = extractToValue(expression.getRight(), plan, context);
        plan.addStep(new CheckStep(new DivisionByZeroCheck(rightValue.clone())));

        rewritten.setLeft(leftValue);
        rewritten.setRight(rightValue);
        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeRegularBinary(BinaryExpr expression, EvaluationContext context) {
        BinaryExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        Expression leftValue = extractToValue(expression.getLeft(), plan, context);
        Expression rightValue = extractToValue(expression.getRight(), plan, context);
        rewritten.setLeft(leftValue);
        rewritten.setRight(rightValue);

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeUnary(UnaryExpr expression, EvaluationContext context) {
        UnaryExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        Expression value = extractToValue(expression.getExpression(), plan, context);
        rewritten.setExpression(value);

        plan.setResult(rewritten);
        return plan;
    }

    private EvaluationPlan analyzeEnclosed(EnclosedExpr expression, EvaluationContext context) {
        EnclosedExpr rewritten = expression.clone();
        EvaluationPlan plan = new EvaluationPlan();

        Expression value = extractToValue(expression.getInner(), plan, context);
        rewritten.setInner(value);

        plan.setResult(rewritten);
        return plan;
    }

    private Expression extractToValue(Expression expression, EvaluationPlan targetPlan, EvaluationContext context) {
        if (isSimpleExpression(expression)) {
            return expression.clone();
        }

        EvaluationPlan nested = analyzeExpression(expression, context);
        targetPlan.addAll(nested);

        String slot = newSlot();
        targetPlan.addStep(new EvaluateStep(slot, nested.getResult().clone()));
        return new NameExpr(slot);
    }

    private static boolean isSimpleExpression(Expression expression) {
        return expression instanceof NameExpr
                || expression instanceof ThisExpr
                || expression instanceof SuperExpr
                || expression instanceof LiteralExpr;
    }

    private static boolean isNullSafe(Expression expression) {
        return switch (expression) {
            case FieldAccessExpr fieldAccessExpr -> {

                if (fieldAccessExpr.resolve().isField()) {
                    yield fieldAccessExpr.resolve().asField().isStatic() || isNullSafe(fieldAccessExpr.getScope());
                }

                // check for array.length
                if (fieldAccessExpr.getScope() instanceof NameExpr nameExpr && nameExpr.resolve().getType().isArray()) {
                    if (fieldAccessExpr.getNameAsString().equals("length")) {
                        yield false;
                    }
                    throw new IllegalStateException("Field access on array type that is not 'length': " + fieldAccessExpr);
                }

                throw new IllegalStateException("Field access expression does not resolve to a field or array length access: " + fieldAccessExpr);

            }
            case MethodCallExpr methodCallExpr ->
                    (methodCallExpr.getScope().isPresent() && methodCallExpr.getScope().get().toString().equals("srctracer.Trace"))
                            ||
                            methodCallExpr.resolve().isStatic()
                            || methodCallExpr.getScope().isEmpty()
                            || isNullSafe(methodCallExpr.getScope().get());
            case ThisExpr ignored -> true;
            case SuperExpr ignored -> true;
            case LiteralExpr ignored -> true;
            default -> false;
        };
    }

    private String newSlot() {
        return SLOT_PREFIX + nextSlotId++;
    }
}
