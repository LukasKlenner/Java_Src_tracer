package srctracer.instrumenter.visitors;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.VariableDeclarator;
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
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import srctracer.trace.TracerMethod;

import java.util.List;

import static com.github.javaparser.StaticJavaParser.parseExpression;
import static com.github.javaparser.StaticJavaParser.parseStatement;

/**
 * Further instruments the given {@link CompilationUnit} to trace implicit exceptions, such as those thrown by arithmetic operations or null dereferences.
 * <p>
 * This is done by rewriting nested expressions into a sequence of statements that check for potential exceptions before evaluating the expression.
 * <p>
 * This visitor must be applied after the {@link InstrumenterVisitor} has been applied, so that the statements that are added
 * are not instrumented again.
 */
public class ImplicitExceptionVisitor extends ModifierVisitor<Void> {
    int nextTmpId = 0;

    @Override
    public Visitable visit(ExpressionStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    @Override
    public Visitable visit(ReturnStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    @Override
    public Visitable visit(YieldStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    @Override
    public Visitable visit(IfStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    @Override
    public Visitable visit(ThrowStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    @Override
    public Visitable visit(ForEachStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    @Override
    public Visitable visit(WhileStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    @Override
    public Visitable visit(ForStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    @Override
    public Visitable visit(DoStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    private void addImplicitExceptionChecks(Statement stmt) {
        NodeList<Statement> implicitExceptionChecks = switch (stmt) {
            case ExpressionStmt es -> getImplicitExceptionChecks(es.getExpression());
            case ReturnStmt rs -> {
                if (rs.getExpression().isPresent()) {
                    yield getImplicitExceptionChecks(rs.getExpression().get());
                }
                yield new NodeList<>();
            }
            case YieldStmt ys -> getImplicitExceptionChecks(ys.getExpression());
            case IfStmt is -> getImplicitExceptionChecks(is.getCondition());
            case ThrowStmt ts -> getImplicitExceptionChecks(ts.getExpression());
            case ForEachStmt fes -> getImplicitExceptionChecks(fes.getIterable());
            case WhileStmt ws -> getImplicitExceptionChecks(ws.getCondition());
            case ForStmt fs -> {
                NodeList<Statement> checks = new NodeList<>();
                for (Expression init : fs.getInitialization()) {
                    checks.addAll(getImplicitExceptionChecks(init));
                }
                if (fs.getCompare().isPresent()) {
                    NodeList<Statement> checksForComparison = getImplicitExceptionChecks(fs.getCompare().get());
                    checks.addAll(checksForComparison);
                    // TODO this also can add an var tmp = arr.length
                    fs.getBody().asBlockStmt().getStatements().addAll(checksForComparison);
                }
                for (Expression update : fs.getUpdate()) {
                    checks.addAll(getImplicitExceptionChecks(update));
                }
                yield checks;
            }
            case DoStmt ds -> getImplicitExceptionChecks(ds.getCondition());
            default -> throw new IllegalArgumentException("Unsupported statement type: " + stmt);
        };

        if (implicitExceptionChecks.isEmpty()) {
            return;
        }

        Node parent = stmt.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            int index = block.getStatements().indexOf(stmt);
            block.getStatements().addAll(index, implicitExceptionChecks);
        } else {
            throw new IllegalStateException("Statement is not inside a block: " + stmt);
        }
    }

    private NodeList<Statement> getImplicitExceptionChecks(Expression expr) {

        NodeList<Statement> implicitExceptionChecks = new NodeList<>();

        switch (expr) {
            case MethodCallExpr mce -> {
                for (Expression arg : mce.getArguments()) {
                    implicitExceptionChecks.addAll(getImplicitExceptionChecks(arg));
                }

                if (!isNullSafe(mce)) {
                    Expression extractedScope = extractExprToTmp(mce.getScope().get(), implicitExceptionChecks);
                    addNullGuardFor(extractedScope, implicitExceptionChecks);

                    mce.setScope(extractedScope);
                }
            }
            case FieldAccessExpr fae -> {
                Expression scope = fae.getScope();

                Expression extractedScope = extractExprToTmp(scope, implicitExceptionChecks);
                addNullGuardFor(extractedScope, implicitExceptionChecks);

                fae.setScope(extractedScope);
            }
            case ArrayAccessExpr aae -> {
                Expression arr = extractExprToTmp(aae.getName(), implicitExceptionChecks);
                Expression idx = extractExprToTmp(aae.getIndex(), implicitExceptionChecks);

                addArrayGuardFor(arr, idx, implicitExceptionChecks);

                aae.setName(arr);
                aae.setIndex(idx);
            }
            case VariableDeclarationExpr vde -> {
                for (VariableDeclarator vd : vde.getVariables()) {
                    if (vd.getInitializer().isPresent()) {
                        Expression extractedInitializer = extractExprToTmp(vd.getInitializer().get(), implicitExceptionChecks);
                        vd.setInitializer(extractedInitializer);
                    }
                }
            }
            case AssignExpr ae -> {
                Expression target = ae.getTarget();


                if (target instanceof FieldAccessExpr fae && !isNullSafe(fae)) {
                    Expression extractedScope = extractExprToTmp(fae.getScope(), implicitExceptionChecks);
                    addNullGuardFor(extractedScope, implicitExceptionChecks);

                    fae.setScope(extractedScope);
                } else if (target instanceof ArrayAccessExpr aae) {
                    Expression arr = extractExprToTmp(aae.getName(), implicitExceptionChecks);
                    Expression idx = extractExprToTmp(aae.getIndex(), implicitExceptionChecks);

                    addArrayGuardFor(arr, idx, implicitExceptionChecks);

                    aae.setName(arr);
                    aae.setIndex(idx);
                }

                Expression extractedValue = extractExprToTmp(ae.getValue(), implicitExceptionChecks);
                ae.setValue(extractedValue);
            }
            case CastExpr ce -> {
                Expression inner = extractExprToTmp(ce.getExpression(), implicitExceptionChecks);

                addCastGuardFor(inner, ce.getType().asString(), implicitExceptionChecks);

                ce.setExpression(inner);
            }
            case ArrayCreationExpr ace -> {
                for (ArrayCreationLevel level : ace.getLevels()) {
                    if (level.getDimension().isPresent()) {
                        Expression dim = extractExprToTmp(level.getDimension().get(), implicitExceptionChecks);
                        addNegArraySizeGuardFor(dim, implicitExceptionChecks);
                        level.setDimension(dim);
                    }
                }
            }
            case ObjectCreationExpr oce -> {
                for (Expression arg : oce.getArguments()) {
                    implicitExceptionChecks.addAll(getImplicitExceptionChecks(arg));
                }

                // key emits two field writes who never fail
                implicitExceptionChecks.add(parseStatement(TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString()));
                implicitExceptionChecks.add(parseStatement(TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString()));
            }
            case ArrayInitializerExpr aie -> {
                for (Expression value : aie.getValues()) {
                    implicitExceptionChecks.addAll(getImplicitExceptionChecks(value));
                    // key emits one array write per initialized value, which never fails
                    implicitExceptionChecks.add(parseStatement(TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString()));
                }
            }
            case BinaryExpr be -> {
                Expression extractedLeft = extractExprToTmp(be.getLeft(), implicitExceptionChecks);
                Expression extractedRight = extractExprToTmp(be.getRight(), implicitExceptionChecks);

                if (be.getOperator() == BinaryExpr.Operator.DIVIDE
                        || be.getOperator() == BinaryExpr.Operator.REMAINDER) {
                    addDivGuardFor(extractedRight, implicitExceptionChecks);
                }

                be.setLeft(extractedLeft);
                be.setRight(extractedRight);
            }
            case UnaryExpr ue -> {
                Expression extractedInner = extractExprToTmp(ue.getExpression(), implicitExceptionChecks);
                ue.setExpression(extractedInner);
            }
            case EnclosedExpr ee -> {
                Expression extractedInner = extractExprToTmp(ee.getInner(), implicitExceptionChecks);
                ee.setInner(extractedInner);
            }
            default -> {
            }
        }

        return implicitExceptionChecks;
    }

    // Only extract to tmpExpr if expr itself is complex (i.e., not already a simple name)
    private Expression extractExprToTmp(Expression expr, List<Statement> implicitExceptionChecks) {
        if (expr instanceof NameExpr || expr instanceof ThisExpr || expr instanceof SuperExpr || expr instanceof LiteralExpr) {
            return expr;
        }

        implicitExceptionChecks.addAll(getImplicitExceptionChecks(expr));

        String tmpName = "__srctracer_tmp$" + nextTmpId++;
        Statement tmpAssignment = parseStatement("var " + tmpName + " = " + expr + ";");
        implicitExceptionChecks.add(tmpAssignment);

        return parseExpression(tmpName);
    }

    private static boolean isNullSafe(Expression expr) {

        return switch (expr) {
            case FieldAccessExpr fae -> {
                if (fae.resolve().isField()) {
                    yield fae.resolve().asField().isStatic();
                }
                yield !(fae.getScope() instanceof NameExpr ne) || !ne.resolve().getType().isArray();
            }
            case MethodCallExpr mce ->
                    mce.resolve().isStatic() || mce.getScope().isEmpty() || isNullSafe(mce.getScope().get());
            case ThisExpr te -> true;
            case SuperExpr se -> true;
            case LiteralExpr le -> true;
            default -> false;
        };
    }

    private static void addNullGuardFor(Expression scope, NodeList<Statement> implicitExceptionChecks) {
        if (isNullSafe(scope)) {
            return;
        }

        implicitExceptionChecks.add(
                parseStatement(
                        "if (" + scope + " == null) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " throw new java.lang.NullPointerException(); } " +
                                "else { " + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }")
        );
    }

    private static void addArrayGuardFor(Expression arr, Expression idx, NodeList<Statement> implicitExceptionChecks) {
        implicitExceptionChecks.add(
                parseStatement(
                        "if (" + arr + " == null) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " throw new java.lang.NullPointerException(); } " +
                                "else { " + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " } ")
        );
        implicitExceptionChecks.add(
                parseStatement(
                        "if (" + idx + " < 0 || " + idx + " >= " + arr + ".length) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " throw new java.lang.ArrayIndexOutOfBoundsException(); } " +
                                "else { " + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }")
        );
    }

    private static void addDivGuardFor(Expression divisor, NodeList<Statement> implicitExceptionChecks) {
        implicitExceptionChecks.add(
                parseStatement(
                        "if (" + divisor + " == 0) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " throw new java.lang.ArithmeticException(); } " +
                                "else { " + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }")
        );
    }

    private static void addNegArraySizeGuardFor(Expression size, NodeList<Statement> implicitExceptionChecks) {
        implicitExceptionChecks.add(
                parseStatement(
                        "if (" + size + " < 0) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " throw new java.lang.NegativeArraySizeException(); } " +
                                "else { " + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }")
        );
    }

    private static void addCastGuardFor(Expression inner, String targetType, NodeList<Statement> implicitExceptionChecks) {
        implicitExceptionChecks.add(
                parseStatement(
                        "if (" + inner + " != null && !(" + inner + " instanceof " + targetType + ")) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " throw new java.lang.ClassCastException(); } " +
                                "else { " + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + " }")
        );
    }
}