package srctracer.instrumenter.visitors;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import srctracer.trace.TracerMethod;

import java.util.ArrayList;
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
        List<Statement> prefix = new ArrayList<>();
        Expression rewritten = rewriteExpr(n.getExpression(), prefix);
        if (prefix.isEmpty()) return n;
        BlockStmt block = new BlockStmt();
        prefix.forEach(block::addStatement);
        block.addStatement(new ExpressionStmt(rewritten));
        return block;
    }

    @Override
    public Visitable visit(ReturnStmt n, Void a) {
        super.visit(n, a);
        if (n.getExpression().isEmpty()) return n;
        List<Statement> prefix = new ArrayList<>();
        Expression rewritten = rewriteExpr(n.getExpression().get(), prefix);
        if (prefix.isEmpty()) return n;
        BlockStmt block = new BlockStmt();
        prefix.forEach(block::addStatement);
        block.addStatement(new ReturnStmt(rewritten));
        return block;
    }

    // TODO ifs are also emitted by the InstrumentedVisitor. Make sure it is not recorded twice. Maybe add comment when
    // emitting the if in the InstrumentedVisitor and check for that comment here.
    @Override
    public Visitable visit(IfStmt n, Void a) {
        super.visit(n, a);
        List<Statement> prefix = new ArrayList<>();
        n.setCondition(rewriteExpr(n.getCondition(), prefix));
        if (prefix.isEmpty()) return n;
        BlockStmt block = new BlockStmt();
        prefix.forEach(block::addStatement);
        block.addStatement(n);
        return block;
    }

    @Override
    public Visitable visit(ThrowStmt n, Void a) {
        super.visit(n, a);
        List<Statement> prefix = new ArrayList<>();
        n.setExpression(rewriteExpr(n.getExpression(), prefix));
        if (prefix.isEmpty()) return n;
        BlockStmt block = new BlockStmt();
        prefix.forEach(block::addStatement);
        block.addStatement(n);
        return block;
    }

    @Override
    public Visitable visit(ForEachStmt n, Void a) {
        super.visit(n, a);
        List<Statement> prefix = new ArrayList<>();
        Expression iterable = extractToTmp(rewriteExpr(n.getIterable(), prefix), prefix);
        emitNullGuard(iterable, prefix);
        n.setIterable(iterable);
        BlockStmt block = new BlockStmt();
        prefix.forEach(block::addStatement);
        block.addStatement(n);
        return block;
    }

    // TODO loop conditions

    private Expression rewriteExpr(Expression expr, List<Statement> prefix) {
        if (expr instanceof MethodCallExpr mce) {
            // Rewrite arguments first (left-to-right evaluation order)
            mce.setArguments(rewriteExprList(mce.getArguments(), prefix));

            if (mce.getScope().isPresent()) {
                Expression scope = rewriteExpr(mce.getScope().get(), prefix);
                scope = extractToTmp(scope, prefix);  // only if complex
                if (!isNullSafe(scope)) {
                    emitNullGuard(scope, prefix);
                }
                mce.setScope(scope);
            }
            return mce;
        }

        if (expr instanceof FieldAccessExpr fae) {
            Expression scope = rewriteExpr(fae.getScope(), prefix);
            scope = extractToTmp(scope, prefix);
            if (!isNullSafe(scope)) {
                emitNullGuard(scope, prefix);
            }
            fae.setScope(scope);
            return fae;
        }

        if (expr instanceof ArrayAccessExpr aae) {
            Expression arr = rewriteExpr(aae.getName(), prefix);
            arr = extractToTmp(arr, prefix);
            Expression idx = rewriteExpr(aae.getIndex(), prefix);
            idx = extractToTmp(idx, prefix);
            // ordered: null check first, then bounds
            emitArrayGuard(arr, idx, prefix);
            aae.setName(arr);
            aae.setIndex(idx);
            return aae;
        }

        if (expr instanceof VariableDeclarationExpr vde) {
            for (VariableDeclarator vd : vde.getVariables()) {
                if (vd.getInitializer().isPresent()) {
                    vd.setInitializer(rewriteExpr(vd.getInitializer().get(), prefix));
                }
            }
            return vde;
        }

        if (expr instanceof AssignExpr ae) {
            Expression target = ae.getTarget();
            if (target instanceof FieldAccessExpr fae) {
                Expression scope = extractToTmp(rewriteExpr(fae.getScope(), prefix), prefix);
                if (!isNullSafe(scope)) emitNullGuard(scope, prefix);
                fae.setScope(scope);
                ae.setValue(rewriteExpr(ae.getValue(), prefix));
            } else if (target instanceof ArrayAccessExpr aae) {
                Expression arr = extractToTmp(rewriteExpr(aae.getName(), prefix), prefix);
                Expression idx = extractToTmp(rewriteExpr(aae.getIndex(), prefix), prefix);
                emitArrayGuard(arr, idx, prefix);
                aae.setName(arr);
                aae.setIndex(idx);
                ae.setValue(rewriteExpr(ae.getValue(), prefix));
            } else {
                ae.setValue(rewriteExpr(ae.getValue(), prefix));
            }
            return ae;
        }

        if (expr instanceof CastExpr ce) {
            Expression inner = extractToTmp(rewriteExpr(ce.getExpression(), prefix), prefix);
            String targetType = ce.getType().asString();
            emitCastGuard(inner, targetType, prefix);
            ce.setExpression(inner);
            return ce;
        }

        if (expr instanceof ArrayCreationExpr ace) {
            for (ArrayCreationLevel level : ace.getLevels()) {
                if (level.getDimension().isPresent()) {
                    Expression dim = extractToTmp(rewriteExpr(level.getDimension().get(), prefix), prefix);
                    emitNegArraySizeGuard(dim, prefix);
                    level.setDimension(dim);
                }
            }
            return ace;
        }

        if (expr instanceof BinaryExpr be
                && (be.getOperator() == BinaryExpr.Operator.DIVIDE
                || be.getOperator() == BinaryExpr.Operator.REMAINDER)) {
            be.setLeft(rewriteExpr(be.getLeft(), prefix));
            Expression right = rewriteExpr(be.getRight(), prefix);
            right = extractToTmp(right, prefix);
            emitDivGuard(right, prefix);
            be.setRight(right);
            return be;
        }

        if (expr instanceof BinaryExpr be) {
            // non-division case — division/remainder already handled above
            be.setLeft(rewriteExpr(be.getLeft(), prefix));
            be.setRight(rewriteExpr(be.getRight(), prefix));
            return be;
        }

        if (expr instanceof UnaryExpr ue) {
            ue.setExpression(rewriteExpr(ue.getExpression(), prefix));
            return ue;
        }

        if (expr instanceof EnclosedExpr ee) {
            ee.setInner(rewriteExpr(ee.getInner(), prefix));
            return ee;
        }

        if (expr instanceof ObjectCreationExpr oce) {
            oce.setArguments(rewriteExprList(oce.getArguments(), prefix));
            return oce;
        }

        return expr;
    }



    private NodeList<Expression> rewriteExprList(NodeList<Expression> exprs, List<Statement> prefix) {
        NodeList<Expression> result = new NodeList<>();
        for (Expression e : exprs) result.add(rewriteExpr(e, prefix));
        return result;
    }

    // Only extract to tmp if scope itself is complex (i.e., not already a simple name)
    private Expression extractToTmp(Expression scope, List<Statement> prefix) {
        if (scope instanceof NameExpr || scope instanceof ThisExpr || scope instanceof SuperExpr) {
            return scope;
        }
        String tmp = "__srctracer_tmp$" + nextTmpId++;
        prefix.add(parseStatement("var " + tmp + " = " + scope + ";"));
        return parseExpression(tmp);
    }

    // Static calls and this/super references can never NPE on the receiver
    private boolean isNullSafe(Expression scope) {
        return switch (scope) {
            case ThisExpr thisExpr -> true;
            case SuperExpr superExpr -> true;

            // TODO properly check for static references (e.g., ClassName.staticMethod())
            case NameExpr nameExpr -> Character.isUpperCase(nameExpr.getNameAsString().charAt(0));
            default -> false;
        };
    }

    private void emitNullGuard(Expression scope, List<Statement> prefix) {
        prefix.add(parseStatement(
                "if (" + scope + " == null) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " } " +
                        "else { " + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + "; }")
        );
    }

    private void emitArrayGuard(Expression arr, Expression idx, List<Statement> prefix) {
        prefix.add(parseStatement(
                "if (" + arr + " == null) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " } " +
                        "else if (" + idx + " < 0 || " + idx + " >= " + arr + ".length) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " } " +
                        "else { " + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + "; }"));
    }

    private void emitDivGuard(Expression divisor, List<Statement> prefix) {
        prefix.add(parseStatement(
                "if (" + divisor + " == 0) { " + TracerMethod.IMPLICIT_EXCEPTION.getMethodCallString() + " } " +
                        "else { " + TracerMethod.NO_IMPLICIT_EXCEPTION.getMethodCallString() + "; }"));
    }

    private void emitNegArraySizeGuard(Expression size, List<Statement> prefix) {
        prefix.add(parseStatement(
                "if (" + size + " < 0) { srctracer.Trace._EXCEPTION(\"NegativeArraySize\"); } " +
                        "else { srctracer.Trace._NO_EXCEPTION(); }"));
    }

    private static void emitCastGuard(Expression inner, String targetType, List<Statement> prefix) {
        prefix.add(parseStatement(
                "if (" + inner + " != null && !(" + inner + " instanceof " + targetType + ")) { " +
                        "srctracer.Trace._EXCEPTION(\"ClassCastException\"); } " +
                        "else { srctracer.Trace._NO_EXCEPTION(); }"));
    }
}