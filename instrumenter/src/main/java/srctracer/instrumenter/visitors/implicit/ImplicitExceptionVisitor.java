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
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.Visitable;
import srctracer.instrumenter.visitors.AbstractInstrumenterVisitor;
import srctracer.instrumenter.visitors.InstrumenterVisitor;

import static srctracer.instrumenter.Instrumenter.MAIN_LIFECYCLE_CATCH_PARAM;
import static srctracer.util.JavaParserUtil.alwaysExitsLoop;
import static srctracer.util.JavaParserUtil.resolvedToAstType;
import static srctracer.util.JavaParserUtil.wrapInSwitchExpression;

/**
 * Further instruments the given {@link CompilationUnit} to trace implicit
 * exceptions such as those thrown by arithmetic operations or null dereferences.
 *
 * <p>This visitor must be applied after the {@link InstrumenterVisitor} has run,
 * so that the statements added here are not instrumented again.
 *
 * <p>For each statement, the {@link ImplicitExceptionEmitter} walks the
 * statement's expression recursively, extracting sub-expressions into temporary
 * variables and inserting check if-statements before the statement. Evaluation
 * order strictly follows the JLS.
 */
public class ImplicitExceptionVisitor extends AbstractInstrumenterVisitor {

    private final ImplicitExceptionEmitter emitter;

    public ImplicitExceptionVisitor(String lifecycleMethodName) {
        super(lifecycleMethodName);
        this.emitter = new ImplicitExceptionEmitter(0);
    }

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
    public Visitable visit(SwitchStmt n, Void a) {
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
        BlockStmt block = rewriteDoWhileToWhile(n);
        replaceStatement(n, block);
        block.accept(this, a);
        return block;
    }

    @Override
    public Visitable visit(ForEachStmt n, Void a) {
        BlockStmt block = rewriteForEachToWhile(n);
        replaceStatement(n, block);
        block.accept(this, a);
        return block;
    }

    @Override
    public Visitable visit(ExplicitConstructorInvocationStmt n, Void a) {
        super.visit(n, a);
        // super()/this() must be the first statement in a constructor – we cannot
        // insert checks before it. Instead, each argument that needs checks is
        // wrapped in a switch expression whose default block provides the required
        // statement context: switch(0) { default: { checks...; yield arg; } }
        for (int i = 0; i < n.getArguments().size(); i++) {
            NodeList<Statement> checks = new NodeList<>();
            Expression newArg = emitter.emit(n.getArgument(i), checks);
            if (!checks.isEmpty()) {
                n.setArgument(i, wrapInSwitchExpression(checks, newArg));
            } else {
                n.setArgument(i, newArg);
            }
        }
        return n;
    }

    // ── Statement-level dispatch ─────────────────────────────────────────────

    private void addImplicitExceptionChecks(Statement stmt) {
        NodeList<Statement> checks = switch (stmt) {
            case ExpressionStmt es -> {
                NodeList<Statement> out = new NodeList<>();
                es.setExpression(emitter.emit(es.getExpression(), out));
                yield out;
            }
            case ReturnStmt rs -> {
                if (rs.getExpression().isEmpty()) yield new NodeList<>();
                NodeList<Statement> out = new NodeList<>();
                rs.setExpression(emitter.emit(rs.getExpression().get(), out));
                yield out;
            }
            case YieldStmt ys -> {
                NodeList<Statement> out = new NodeList<>();
                ys.setExpression(emitter.emit(ys.getExpression(), out));
                yield out;
            }
            case IfStmt is -> {
                NodeList<Statement> out = new NodeList<>();
                is.setCondition(emitter.emit(is.getCondition(), out));
                yield out;
            }
            case ThrowStmt ts -> {
                // Skip the main-lifecycle rethrow – that variable is never null
                if (ts.getExpression().toString().equals(MAIN_LIFECYCLE_CATCH_PARAM))
                    yield new NodeList<>();
                NodeList<Statement> out = new NodeList<>();
                ts.setExpression(emitter.emitThrowExpression(ts.getExpression(), out));
                yield out;
            }
            case WhileStmt ws -> {
                NodeList<Statement> out = new NodeList<>();
                ws.setCondition(emitter.emitLoopCondition(ws.getCondition(), out));
                yield out;
            }
            case SwitchStmt ss -> {
                NodeList<Statement> out = new NodeList<>();
                ss.setSelector(emitter.emitSwitchSelector(ss.getSelector(), ss, out));
                yield out;
            }
            default -> throw new IllegalArgumentException("Unsupported statement type: " + stmt);
        };

        if (checks.isEmpty()) return;

        addBeforeStatement(stmt, checks);

        // Loop conditions must be re-evaluated at the end of the loop body
        if (stmt instanceof WhileStmt ws && !alwaysExitsLoop(ws.getBody())) {
            ws.getBody().asBlockStmt().getStatements()
                    .addAll(ImplicitExceptionEmitter.toLoopRefreshStatements(checks));
        }
    }

    // ── Loop rewriting (for/do-while/for-each → while) ───────────────────────

    private WhileStmt rewriteForToWhile(ForStmt forStmt) {
        addBeforeStatement(forStmt,
                new NodeList<>(forStmt.getInitialization().stream()
                        .map(ExpressionStmt::new).toList()));

        WhileStmt whileStmt = new WhileStmt();
        whileStmt.setCondition(forStmt.getCompare()
                .orElse(new BooleanLiteralExpr(true)).clone());
        BlockStmt body = new BlockStmt();
        body.getStatements().add(forStmt.getBody().clone());
        body.getStatements().addAll(forStmt.getUpdate().stream()
                .map(ExpressionStmt::new).map(Statement::clone).toList());
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

        // Store array in a temp to avoid re-evaluating the iterable each iteration
        String arrayTmp = emitter.freshTmp();
        VariableDeclarator arrayTemp = new VariableDeclarator(
                resolvedToAstType(forEachStmt.getIterable().calculateResolvedType()),
                arrayTmp,
                forEachStmt.getIterable().clone()
        );

        // int __srctracer_tmp$N = 0;
        String indexTmp = emitter.freshTmp();
        VariableDeclarator index = new VariableDeclarator(
                new ClassOrInterfaceType(null, "int"),
                indexTmp,
                new IntegerLiteralExpr("0")
        );

        // while (index < array.length) { ... }
        WhileStmt whileStmt = new WhileStmt();
        whileStmt.setCondition(new BinaryExpr(
                new NameExpr(indexTmp),
                new FieldAccessExpr(new NameExpr(arrayTmp), "length"),
                BinaryExpr.Operator.LESS
        ));

        BlockStmt body = new BlockStmt();
        body.addStatement(forEachStmt.getBody().clone());

        // Type variable = array[index];  (inserted after _LOOP_BODY at position 0)
        VariableDeclarator element = new VariableDeclarator(
                variable.getType().clone(),
                variable.getNameAsString(),
                new ArrayAccessExpr(new NameExpr(arrayTmp), new NameExpr(indexTmp))
        );
        body.getStatement(0).asBlockStmt().addStatement(1,
                new ExpressionStmt(new VariableDeclarationExpr(element)));

        // index++;
        body.addStatement(new ExpressionStmt(
                new UnaryExpr(new NameExpr(indexTmp), UnaryExpr.Operator.POSTFIX_INCREMENT)));

        whileStmt.setBody(body);

        BlockStmt outer = new BlockStmt();
        outer.addStatement(new ExpressionStmt(new VariableDeclarationExpr(arrayTemp)));
        outer.addStatement(new ExpressionStmt(new VariableDeclarationExpr(index)));
        outer.addStatement(whileStmt);
        return outer;
    }

    private BlockStmt rewriteForEachIterableToWhile(ForEachStmt forEachStmt) {
        VariableDeclarator variable = forEachStmt.getVariable().getVariable(0);

        // Iterator<Type> iterator = iterable.iterator();
        String iteratorTmp = emitter.freshTmp();
        VariableDeclarator iterator = new VariableDeclarator(
                new ClassOrInterfaceType(null, "java.util.Iterator")
                        .setTypeArguments(variable.getType().clone()),
                iteratorTmp,
                new MethodCallExpr(forEachStmt.getIterable().clone(), "iterator")
        );

        // while (iterator.hasNext()) { ... }
        WhileStmt whileStmt = new WhileStmt();
        whileStmt.setCondition(new MethodCallExpr(new NameExpr(iteratorTmp), "hasNext"));

        BlockStmt body = new BlockStmt();
        body.addStatement(forEachStmt.getBody().clone());

        // Type variable = iterator.next();  (inserted after _LOOP_BODY at position 0)
        VariableDeclarator element = new VariableDeclarator(
                variable.getType().clone(),
                variable.getNameAsString(),
                new MethodCallExpr(new NameExpr(iteratorTmp), "next")
        );
        body.getStatement(0).asBlockStmt().addStatement(1,
                new ExpressionStmt(new VariableDeclarationExpr(element)));

        whileStmt.setBody(body);

        BlockStmt outer = new BlockStmt();
        outer.addStatement(new ExpressionStmt(new VariableDeclarationExpr(iterator)));
        outer.addStatement(whileStmt);
        return outer;
    }

    // ── AST manipulation helpers ─────────────────────────────────────────────

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
