package srctracer.instrumenter.visitors;

import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

import static srctracer.util.JavaParserUtil.isMainMethod;

/**
 * Shared base for the two instrumentation passes. Provides the
 * lifecycle-method concept, the {@link MethodDeclaration} guard that skips
 * non-lifecycle main methods, and the {@link #TERNARY_REWRITE_CAST} data key
 * used to communicate between passes.
 */
public abstract class AbstractInstrumenterVisitor extends ModifierVisitor<Void> {

    /**
     * Marks a {@link com.github.javaparser.ast.expr.CastExpr} injected by
     * {@link InstrumenterVisitor} to preserve the narrowed result type of a
     * ternary expression whose branches were wrapped in switch expressions.
     * The operand of such a cast is always primitive-typed, so unboxing NPE
     * cannot occur. {@link srctracer.instrumenter.visitors.implicit.ImplicitExceptionEmitter}
     * checks this marker to skip the unboxing null-check instead of inferring
     * it from the presence of a nested SwitchExpr.
     */
    public static final DataKey<Boolean> TERNARY_REWRITE_CAST = new DataKey<Boolean>() {};

    protected final String lifecycleMethodName;

    protected AbstractInstrumenterVisitor(String lifecycleMethodName) {
        this.lifecycleMethodName = lifecycleMethodName;
    }

    protected boolean isLifecycleMethod(MethodDeclaration md) {
        if (lifecycleMethodName != null)
            return md.getNameAsString().equals(lifecycleMethodName);
        return isMainMethod(md);
    }

    /**
     * Filters out non-lifecycle main methods, visits children, then calls
     * {@link #postVisitMethod} for subclass-specific post-visit logic.
     */
    @Override
    public Visitable visit(MethodDeclaration md, Void a) {
        if (isMainMethod(md) && !isLifecycleMethod(md)) return md;
        super.visit(md, a);
        postVisitMethod(md);
        return md;
    }

    /**
     * Called after children of {@code md} have been visited, provided the
     * lifecycle guard did not skip it. Override to inject post-visit
     * instrumentation.
     */
    protected void postVisitMethod(MethodDeclaration md) {}
}
