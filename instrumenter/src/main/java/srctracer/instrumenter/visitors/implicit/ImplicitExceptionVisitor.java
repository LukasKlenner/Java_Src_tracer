package srctracer.instrumenter.visitors.implicit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import srctracer.instrumenter.visitors.InstrumenterVisitor;

import java.util.function.Consumer;

import static com.github.javaparser.StaticJavaParser.parseStatement;

/**
 * Further instruments the given {@link CompilationUnit} to trace implicit exceptions, such as those thrown by arithmetic operations or null dereferences.
 * <p>
 * This is done in two phases for each expression:
 * 1) Analyze expression semantics into an evaluation plan.
 * 2) Rewrite the plan into JavaParser statements and expression replacements.
 * <p>
 * This visitor must be applied after the {@link InstrumenterVisitor} has been applied, so that the statements that are added
 * are not instrumented again.
 */
public class ImplicitExceptionVisitor extends ModifierVisitor<Void> {
    private int nextTmpId = 0;
    private final ImplicitExceptionAnalyzer analyzer = new ImplicitExceptionAnalyzer();
    private final EvaluationPlanRewriter rewriter = new EvaluationPlanRewriter();

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

    @Override
    public Visitable visit(ExplicitConstructorInvocationStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    private void addImplicitExceptionChecks(Statement stmt) {
        NodeList<Statement> checks = switch (stmt) {
            case ExpressionStmt es -> rewriteExpression(es.getExpression(), EvaluationContext.NORMAL, es::setExpression);
            case ReturnStmt rs -> {
                if (rs.getExpression().isPresent()) {
                    yield rewriteExpression(rs.getExpression().get(), EvaluationContext.RETURN_VALUE, rs::setExpression);
                }
                yield new NodeList<>();
            }
            case YieldStmt ys -> rewriteExpression(ys.getExpression(), EvaluationContext.RETURN_VALUE, ys::setExpression);
            case IfStmt is -> rewriteExpression(is.getCondition(), EvaluationContext.CONDITION, is::setCondition);
            case ThrowStmt ts -> rewriteExpression(ts.getExpression(), EvaluationContext.NORMAL, ts::setExpression);
            case ForEachStmt fes -> rewriteExpression(fes.getIterable(), EvaluationContext.NORMAL, fes::setIterable);
            case WhileStmt ws -> rewriteExpression(ws.getCondition(), EvaluationContext.LOOP_CONDITION, ws::setCondition);
            case ForStmt fs -> rewriteForStatement(fs);
            case DoStmt ds -> rewriteExpression(ds.getCondition(), EvaluationContext.LOOP_CONDITION, ds::setCondition);
            case ExplicitConstructorInvocationStmt ecis -> {
                NodeList<Statement> checksForArgs = new NodeList<>();
                for (int i = 0; i < ecis.getArguments().size(); i++) {
                    Expression arg = ecis.getArguments().get(i);
                    int finalI = i;
                    checksForArgs.addAll(rewriteExpression(arg, EvaluationContext.NORMAL, expr -> ecis.setArgument(finalI, expr)));
                }
                yield checksForArgs;
            }
            default -> throw new IllegalArgumentException("Unsupported statement type: " + stmt);
        };

        if (checks.isEmpty()) {
            return;
        }

        Node parent = stmt.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            int index = block.getStatements().indexOf(stmt);
            block.getStatements().addAll(index, checks);
            return;
        }
        throw new IllegalStateException("Statement is not inside a block: " + stmt);
    }

    private NodeList<Statement> rewriteForStatement(ForStmt forStmt) {
        NodeList<Statement> checks = new NodeList<>();

        for (int i = 0; i < forStmt.getInitialization().size(); i++) {
            final int idx = i;
            Expression init = forStmt.getInitialization().get(i);
            checks.addAll(rewriteExpression(init, EvaluationContext.NORMAL, expr -> forStmt.getInitialization().set(idx, expr)));
        }

        if (forStmt.getCompare().isPresent()) {
            NodeList<Statement> compareChecks = rewriteExpression(
                    forStmt.getCompare().get(),
                    EvaluationContext.LOOP_CONDITION,
                    forStmt::setCompare
            );
            checks.addAll(compareChecks);
            forStmt.getBody().asBlockStmt().getStatements().addAll(toLoopRefreshChecks(compareChecks));
        }

        for (int i = 0; i < forStmt.getUpdate().size(); i++) {
            final int idx = i;
            Expression update = forStmt.getUpdate().get(i);
            checks.addAll(rewriteExpression(update, EvaluationContext.NORMAL, expr -> forStmt.getUpdate().set(idx, expr)));
        }

        return checks;
    }

    private NodeList<Statement> rewriteExpression(
            Expression expression,
            EvaluationContext context,
            Consumer<Expression> expressionSetter
    ) {
        EvaluationPlan plan = analyzer.analyzeExpression(expression, context);
        EvaluationPlanRewriter.RewriteResult result = rewriter.rewrite(plan, nextTmpId);
        nextTmpId = result.nextTmpId();
        expressionSetter.accept(result.result());
        return result.statements();
    }

    private static NodeList<Statement> toLoopRefreshChecks(NodeList<Statement> checks) {
        NodeList<Statement> refreshChecks = new NodeList<>();
        for (Statement check : checks) {
            Statement cloned = check.clone();
            if (cloned instanceof ExpressionStmt expressionStmt
                    && expressionStmt.getExpression() instanceof VariableDeclarationExpr variableDeclarationExpr) {
                for (var variable : variableDeclarationExpr.getVariables()) {
                    if (variable.getInitializer().isPresent()) {
                        refreshChecks.add(parseStatement(
                                variable.getNameAsString() + " = " + variable.getInitializer().get() + ";"
                        ));
                    }
                }
                continue;
            }
            refreshChecks.add(cloned);
        }
        return refreshChecks;
    }
}
