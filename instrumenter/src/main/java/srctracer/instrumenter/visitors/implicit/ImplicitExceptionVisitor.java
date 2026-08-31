package srctracer.instrumenter.visitors.implicit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
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
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import srctracer.instrumenter.visitors.InstrumenterVisitor;

import java.util.function.Consumer;

import static com.github.javaparser.StaticJavaParser.parseStatement;
import static srctracer.instrumenter.Instrumenter.MAIN_LIFECYCLE_CATCH_PARAM;

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
    public Visitable visit(WhileStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    @Override
    public Visitable visit(ForStmt n, Void a) {
        WhileStmt whileStmt = rewriteForToWhile(n);
        replaceStatement(n, whileStmt);
        whileStmt.accept(this, a);
        return whileStmt;
    }

    @Override
    public Visitable visit(DoStmt n, Void a) {
        BlockStmt whileStmt = rewriteDoWhileToWhile(n);
        replaceStatement(n, whileStmt);
        whileStmt.accept(this, a);
        return whileStmt;
    }

    @Override
    public Visitable visit(ForEachStmt n, Void a) {
        BlockStmt whileStmt = rewriteForEachToWhile(n);
        replaceStatement(n, whileStmt);
        whileStmt.accept(this, a);
        return whileStmt;
    }


    @Override
    public Visitable visit(ExplicitConstructorInvocationStmt n, Void a) {
        super.visit(n, a);
        addImplicitExceptionChecks(n);
        return n;
    }

    private void addImplicitExceptionChecks(Statement stmt) {
        NodeList<Statement> checks = switch (stmt) {
            case ExpressionStmt es ->
                    rewriteExpression(es.getExpression(), EvaluationContext.NORMAL, es::setExpression);
            case ReturnStmt rs -> {
                if (rs.getExpression().isPresent()) {
                    yield rewriteExpression(rs.getExpression().get(), EvaluationContext.RETURN_VALUE, rs::setExpression);
                }
                yield new NodeList<>();
            }
            case YieldStmt ys ->
                    rewriteExpression(ys.getExpression(), EvaluationContext.RETURN_VALUE, ys::setExpression);
            case IfStmt is -> rewriteExpression(is.getCondition(), EvaluationContext.CONDITION, is::setCondition);
            case ThrowStmt ts -> {
                if (ts.getExpression().toString().equals(MAIN_LIFECYCLE_CATCH_PARAM)) {
                    // Don't instrument the main lifecycle catch parameter.
                    yield new NodeList<>();
                }
                yield rewriteExpression(ts.getExpression(), EvaluationContext.THROW_EXPRESSION, ts::setExpression);
            }
            case WhileStmt ws ->
                    rewriteExpression(ws.getCondition(), EvaluationContext.LOOP_CONDITION, ws::setCondition);
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

        addBeforeStatement(stmt, checks);

        if (stmt instanceof WhileStmt whileStmt) {
            whileStmt.getBody().asBlockStmt().getStatements().addAll(toLoopRefreshChecks(checks));
        }
    }

    private WhileStmt rewriteForToWhile(ForStmt forStmt) {
        addBeforeStatement(forStmt, new NodeList<>(forStmt.getInitialization().stream().map(ExpressionStmt::new).toList()));

        WhileStmt whileStmt = new WhileStmt();
        whileStmt.setCondition(forStmt.getCompare().orElse(new BooleanLiteralExpr(true)).clone());
        BlockStmt body = new BlockStmt();
        body.getStatements().add(forStmt.getBody().clone());
        body.getStatements().addAll(forStmt.getUpdate().stream().map(ExpressionStmt::new).map(Statement::clone).toList());
        whileStmt.setBody(body);
        return whileStmt;
    }

    private BlockStmt rewriteDoWhileToWhile(DoStmt doStmt) {
        WhileStmt whileStmt = new WhileStmt();
        whileStmt.setCondition(doStmt.getCondition().clone());
        whileStmt.setBody(doStmt.getBody().clone());

        BlockStmt outer = new BlockStmt();
        outer.getStatements().add(doStmt.getBody().clone());
        outer.getStatements().add(whileStmt);

        return outer;
    }

    private BlockStmt rewriteForEachToWhile(ForEachStmt forEachStmt) {
        if (forEachStmt.getIterable().calculateResolvedType().isArray()) {
            return rewriteForEachArrayToWhile(forEachStmt);
        } else {
            return rewriteForEachIterableToWhile(forEachStmt);
        }
    }

    private BlockStmt rewriteForEachArrayToWhile(ForEachStmt forEachStmt) {
        VariableDeclarator variable = forEachStmt.getVariable().getVariable(0);

        // int __srctracer_tmp$index = 0;
        VariableDeclarator index = new VariableDeclarator(
                new ClassOrInterfaceType(null, "int"),
                "__srctracer_tmp$" + nextTmpId++,
                new IntegerLiteralExpr("0")
        );

        // while (index < array.length) { ... }
        WhileStmt whileStmt = new WhileStmt();
        whileStmt.setCondition(
                new BinaryExpr(
                        new NameExpr(index.getNameAsString()),
                        new FieldAccessExpr(forEachStmt.getIterable().clone(), "length"),
                        BinaryExpr.Operator.LESS
                )
        );

        BlockStmt body = new BlockStmt();

        body.addStatement(forEachStmt.getBody().clone());

        // Type variable = array[index];
        VariableDeclarator element = new VariableDeclarator(
                variable.getType().clone(),
                variable.getNameAsString(),
                new ArrayAccessExpr(forEachStmt.getIterable().clone(), new NameExpr(index.getNameAsString()))
        );

        // add after loop enter recording
        body.getStatement(0).asBlockStmt().addStatement(1, new ExpressionStmt(
                new VariableDeclarationExpr(element)
        ));

        // index++;
        body.addStatement(new ExpressionStmt(
                new UnaryExpr(new NameExpr(index.getNameAsString()), UnaryExpr.Operator.POSTFIX_INCREMENT)
        ));

        whileStmt.setBody(body);

        BlockStmt outer = new BlockStmt();
        outer.addStatement(new ExpressionStmt(new VariableDeclarationExpr(index)));
        outer.addStatement(whileStmt);

        return outer;
    }

    private BlockStmt rewriteForEachIterableToWhile(ForEachStmt forEachStmt) {
        VariableDeclarator variable = forEachStmt.getVariable().getVariable(0);

        // Iterator<Type> iterator = iterable.iterator();
        VariableDeclarator iterator = new VariableDeclarator(
                new ClassOrInterfaceType(null, "Iterator")
                        .setTypeArguments(variable.getType().clone()),
                "__srctracer_tmp$" + nextTmpId++,
                new MethodCallExpr(
                        forEachStmt.getIterable().clone(),
                        "iterator"
                )
        );

        // while (iterator.hasNext()) { ... }
        WhileStmt whileStmt = new WhileStmt();
        whileStmt.setCondition(
                new MethodCallExpr(new NameExpr("iterator"), "hasNext")
        );

        BlockStmt body = new BlockStmt();


        body.addStatement(forEachStmt.getBody().clone());

        // Type variable = iterator.next();
        VariableDeclarator element = new VariableDeclarator(
                variable.getType().clone(),
                variable.getNameAsString(),
                new MethodCallExpr(new NameExpr("iterator"), "next")
        );

        // add after loop enter recording
        body.getStatement(0).asBlockStmt().addStatement(1, new ExpressionStmt(
                new VariableDeclarationExpr(element)
        ));

        whileStmt.setBody(body);

        BlockStmt outer = new BlockStmt();
        outer.addStatement(new ExpressionStmt(new VariableDeclarationExpr(iterator)));
        outer.addStatement(whileStmt);

        return outer;
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


    private static void addBeforeStatement(Statement stmt, NodeList<? extends Statement> statements) {
        Node parent = stmt.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            int index = block.getStatements().indexOf(stmt);
            block.getStatements().addAll(index, statements);
        } else {
            throw new IllegalStateException("statement is not inside a block: " + stmt);
        }
    }

    private static void replaceStatement(Statement toReplace, Statement replacement) {
        Node parent = toReplace.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            int index = block.getStatements().indexOf(toReplace);
            block.getStatements().set(index, replacement);
        } else {
            throw new IllegalStateException("statement is not inside a block: " + toReplace);
        }
    }
}
