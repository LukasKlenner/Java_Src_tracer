package srctracer.instrumenter.visitors;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.visitor.Visitable;
import srctracer.trace.TracerMethod;

import static com.github.javaparser.StaticJavaParser.parseExpression;

/**
 * Records each array-creation dimension via {@code _DIM}, inline.
 */
public class ArrayDimVisitor extends AbstractInstrumenterVisitor {

    public ArrayDimVisitor(String lifecycleMethodName) {
        super(lifecycleMethodName);
    }

    @Override
    public Visitable visit(ArrayCreationExpr n, Void a) {
        super.visit(n, a);
        for (int i = 0; i < n.getLevels().size(); i++) {
            ArrayCreationLevel dim = n.getLevels().get(i);
            if (dim.getDimension().isPresent()) {
                Expression dimExpr = dim.getDimension().get();

                TracerMethod tracerMethod = i == n.getLevels().size() - 1 ||
                                n.getLevels().get(i + 1).getDimension().isEmpty() ?
                        TracerMethod.LAST_DIM :
                        TracerMethod.DIM;

                n.getLevels().set(i, new ArrayCreationLevel(
                        parseExpression(tracerMethod.getMethodCallString(dimExpr.toString()))));
            }
        }
        return n;
    }
}
