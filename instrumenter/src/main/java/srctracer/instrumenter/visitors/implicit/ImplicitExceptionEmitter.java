package srctracer.instrumenter.visitors.implicit;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import srctracer.instrumenter.visitors.AbstractInstrumenterVisitor;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.resolution.declarations.ResolvedEnumConstantDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedType;
import srctracer.trace.TracerMethod;

import java.util.ArrayList;
import java.util.List;

import static srctracer.trace.TracerMethod.IMPLICIT_EXCEPTION;
import static srctracer.trace.TracerMethod.NO_IMPLICIT_EXCEPTION;
import static srctracer.util.JavaParserUtil.isSimpleExpression;
import static srctracer.util.JavaParserUtil.parseStatement;
import static srctracer.util.JavaParserUtil.parseTracerCall;
import static srctracer.util.JavaParserUtil.switchNeedsNullCheck;

/**
 * Recursively emits implicit-exception guards for a single expression.
 * Emits pre-requisite statements (temp-variable declarations and check
 * if-statements) into a caller-supplied list, and returns the replacement
 * expression. Evaluation order matches the JLS: all operands are extracted
 * left-to-right before any checks fire.
 *
 * <p>Use {@link #emit} for normal expressions, and the specialised top-level
 * methods for throw-expressions, switch selectors, and loop conditions.
 */
public final class ImplicitExceptionEmitter {

    private static final String TMP_PREFIX = "__srctracer_tmp$";

    /**
     * When {@code false} (set during loop-condition emission), temps are declared
     * {@code var} instead of {@code final var} so they can be reassigned in the
     * loop-body refresh.
     */
    private boolean emitFinalVars = true;

    private int nextTmpId;

    public ImplicitExceptionEmitter(int nextTmpId) {
        this.nextTmpId = nextTmpId;
    }

    public int getNextTmpId() {
        return nextTmpId;
    }

    /** Allocates a unique temp-variable name shared across this compilation unit. */
    public String freshTmp() {
        return TMP_PREFIX + nextTmpId++;
    }

    // ── Public entry points ──────────────────────────────────────────────────

    /**
     * Emits prerequisite statements for {@code expr} into {@code out} and returns
     * a replacement expression that is free of side effects. Sub-expressions are
     * evaluated left-to-right per JLS before any check fires.
     */
    public Expression emit(Expression expr, NodeList<Statement> out) {
        return switch (expr) {
            case MethodCallExpr mc          -> emitMethodCall(mc, out);
            case FieldAccessExpr fa         -> emitFieldAccess(fa, out);
            case ArrayAccessExpr aa         -> emitArrayAccess(aa, out);
            case AssignExpr ae              -> emitAssign(ae, out);
            case VariableDeclarationExpr vd -> emitVarDecl(vd, out);
            case CastExpr cast              -> emitCast(cast, out);
            case ArrayCreationExpr ac       -> emitArrayCreation(ac, out);
            case ArrayInitializerExpr ai    -> emitArrayInitializer(ai, out);
            case ObjectCreationExpr oc      -> emitObjectCreation(oc, out);
            case BinaryExpr be              -> emitBinary(be, out);
            case UnaryExpr ue               -> emitUnary(ue, out);
            case EnclosedExpr ee            -> emitEnclosed(ee, out);
            case ConditionalExpr ce         -> emitConditional(ce, out);
            case InstanceOfExpr ie          -> emitInstanceOf(ie, out);
            default                         -> expr.clone(); // names, literals, this, super
        };
    }

    /**
     * JLS §14.18: the expression is evaluated; if the result is {@code null},
     * a NullPointerException is thrown in place of the intended throwable.
     */
    public Expression emitThrowExpression(Expression expr, NodeList<Statement> out) {
        Expression val = extractToTemp(expr, out);
        out.add(nullCheck(val));
        return val;
    }

    /**
     * JLS §14.11: the selector expression is evaluated. If the selector type is a
     * reference type and its value is {@code null}, a NullPointerException is thrown.
     * Primitive selectors always emit {@link TracerMethod#NO_IMPLICIT_EXCEPTION} to simplify retracing
     */
    public Expression emitSwitchSelector(Expression selector, SwitchStmt switchStmt,
                                          NodeList<Statement> out) {
        Expression val = extractToTemp(selector, out);
        if (switchNeedsNullCheck(switchStmt)) {
            out.add(nullCheck(val));
        } else {
            out.add(parseTracerCall(NO_IMPLICIT_EXCEPTION));
        }
        return val;
    }

    /**
     * JLS §14.12: the while-statement evaluates its expression before each iteration.
     * All temps are declared {@code var} (not {@code final var}) so the emitted
     * statements can be re-issued as assignments at the end of the loop body via
     * {@link #toLoopRefreshStatements}, reproducing the per-iteration re-evaluation.
     * If no checks were emitted and the result is already a simple expression, the
     * extra wrapper variable is omitted (no refresh needed).
     *
     * @return replacement condition expression
     */
    public Expression emitLoopCondition(Expression condition, NodeList<Statement> out) {
        emitFinalVars = false;
        try {
            int sizeBefore = out.size();
            Expression val = emit(condition, out);
            // If no checks were emitted and the result is already a simple expression,
            // there is nothing to refresh at the end of the loop body — skip the temp.
            if (out.size() == sizeBefore && isSimpleExpression(val)) {
                return val;
            }
            String tmp = freshTmp();
            out.add(parseStatement("var " + tmp + " = " + val + ";"));
            return new NameExpr(tmp);
        } finally {
            emitFinalVars = true;
        }
    }

    /**
     * Converts pre-loop check statements (from {@link #emitLoopCondition}) into
     * refresh statements for the end of the loop body. {@code var} declarations
     * become plain assignments; check if-statements are cloned as-is.
     */
    public static NodeList<Statement> toLoopRefreshStatements(NodeList<Statement> checks) {
        NodeList<Statement> refresh = new NodeList<>();
        for (Statement s : checks) {
            if (s instanceof ExpressionStmt es
                    && es.getExpression() instanceof VariableDeclarationExpr vde) {
                for (var v : vde.getVariables()) {
                    v.getInitializer().ifPresent(init ->
                            refresh.add(parseStatement(v.getNameAsString() + " = " + init + ";")));
                }
            } else {
                refresh.add(s.clone());
            }
        }
        return refresh;
    }

    // ── Private emitters ─────────────────────────────────────────────────────

    /**
     * JLS §15.12.4: Method invocation is evaluated in three steps.
     * §15.12.4.1 – compute target reference: the receiver expression is always evaluated
     * before arguments, even for static methods invoked via an expression (§15.12.4.1
     * step 1 says "evaluate the expression that is the receiver").
     * §15.12.4.2 – evaluate arguments left-to-right.
     * Invocation – if the method is an instance method and the target reference is null,
     * a NullPointerException is thrown after all arguments have been evaluated.
     *
     * <p>Exception: {@code srctracer.Trace} calls use an unresolvable qualified class
     * name as scope; scope processing is skipped for those calls only.
     */
    private Expression emitMethodCall(MethodCallExpr mc, NodeList<Statement> out) {
        MethodCallExpr rewritten = mc.clone();
        Expression scopeTemp = null;

        if (mc.getScope().isPresent()) {
            // srctracer.Trace is an unresolvable qualified class name — skip entirely.
            boolean isTracerScope = mc.getScope().get().toString().equals("srctracer.Trace");
            if (!isTracerScope) {
                // §15.12.4.1 – scope evaluated before arguments, even for static calls.
                // For a plain type name (e.g. Math), extractToTemp returns it as-is (simple);
                // for an expression (e.g. getObj().staticMethod()), a temp is created so the
                // call executes in `out` before argument temps.
                scopeTemp = extractToTemp(mc.getScope().get(), out);
                rewritten.setScope(scopeTemp);
            }
        }

        // §15.12.4.2 – arguments left-to-right. extractToTemp ensures complex arguments
        // (including inner method calls) are fully executed in `out` before the null check.
        for (int i = 0; i < mc.getArguments().size(); i++) {
            rewritten.setArgument(i, extractToTemp(mc.getArgument(i), out));
        }

        // NPE fires at invocation (after all operands); only for instance calls.
        if (scopeTemp != null && !isNullSafe(mc)) {
            out.add(nullCheck(scopeTemp));
        }

        return rewritten;
    }

    /**
     * JLS §15.11: the Primary sub-expression is evaluated, then if its value is null
     * a NullPointerException is thrown before the field is accessed.
     */
    private Expression emitFieldAccess(FieldAccessExpr fa, NodeList<Statement> out) {
        if (isNullSafe(fa)) return fa.clone();
        FieldAccessExpr rewritten = fa.clone();
        Expression scope = extractToTemp(fa.getScope(), out);
        out.add(nullCheck(scope));
        rewritten.setScope(scope);
        return rewritten;
    }

    /**
     * JLS §15.13: the array reference expression is evaluated first, then the index
     * expression. A NullPointerException is thrown if the array reference is null;
     * an ArrayIndexOutOfBoundsException is thrown if the index is out of range.
     * Both checks fire after both operands are fully evaluated.
     */
    private Expression emitArrayAccess(ArrayAccessExpr aa, NodeList<Statement> out) {
        Expression arr = extractToTemp(aa.getName(), out);
        Expression idx = extractToTemp(aa.getIndex(), out);
        out.add(nullCheck(arr));
        out.add(boundsCheck(arr, idx));
        ArrayAccessExpr rewritten = aa.clone();
        rewritten.setName(arr);
        rewritten.setIndex(idx);
        return rewritten;
    }

    /**
     * JLS §15.26.1 (simple assignment) / §15.26.2 (compound assignment).
     * The left-hand side is evaluated before the right-hand side; checks fire only
     * after all operands are in hand.
     * <ul>
     *   <li>{@code arr[i] op= v} – array-ref (§15.13), index, RHS; then null-check
     *       array, bounds-check; ArrayStoreException check for plain {@code =} to a
     *       reference-component array; division-by-zero check for {@code /=} and
     *       {@code %=} with integer arithmetic.</li>
     *   <li>{@code obj.field op= v} – scope (§15.11); for simple {@code =}: RHS then
     *       null-check scope; for compound: null-check scope then RHS (current field
     *       value must be read first); division-by-zero check for {@code /=}/{@code %=}.</li>
     *   <li>plain {@code lhs op= v} – RHS only; division-by-zero check for
     *       {@code /=}/{@code %=}.</li>
     * </ul>
     */
    private Expression emitAssign(AssignExpr ae, NodeList<Statement> out) {
        AssignExpr rewritten = ae.clone();
        boolean isSimpleAssign    = ae.getOperator() == AssignExpr.Operator.ASSIGN;
        boolean needsDivisionCheck = isCompoundDivision(ae) && isIntegerCompoundDivision(ae);

        if (ae.getTarget() instanceof ArrayAccessExpr aa) {
            Expression arr = extractToTemp(aa.getName(), out);
            Expression idx = extractToTemp(aa.getIndex(), out);
            final Expression val;
            if (isSimpleAssign) {
                // §15.26.1: RHS evaluated before null/bounds checks (3 → 4 → 5).
                boolean needsStoreCheck = aa.getName().calculateResolvedType().asArrayType()
                        .getComponentType().isReferenceType();
                val = extractToTemp(ae.getValue(), out);
                out.add(nullCheck(arr));
                out.add(boundsCheck(arr, idx));
                if (needsStoreCheck) out.add(arrayStoreCheck(arr, val));
            } else {
                // §15.26.2: null/bounds checks fire before RHS (current element value is
                // read between the bounds check and the RHS evaluation).
                out.add(nullCheck(arr));
                out.add(boundsCheck(arr, idx));
                val = extractToTemp(ae.getValue(), out);
                if (needsDivisionCheck) out.add(divisionByZeroCheck(val));
            }
            ArrayAccessExpr rewrittenTarget = aa.clone();
            rewrittenTarget.setName(arr);
            rewrittenTarget.setIndex(idx);
            rewritten.setTarget(rewrittenTarget);
            rewritten.setValue(val);

        } else if (ae.getTarget() instanceof FieldAccessExpr fa && !isNullSafe(fa)) {
            Expression scope = extractToTemp(fa.getScope(), out);
            final Expression val;
            if (isSimpleAssign) {
                // §15.26.1: RHS evaluated before the null check on scope.
                val = extractToTemp(ae.getValue(), out);
                out.add(nullCheck(scope));
            } else {
                // §15.26.2: null check fires before RHS (current field value is read first).
                out.add(nullCheck(scope));
                val = extractToTemp(ae.getValue(), out);
                if (needsDivisionCheck) out.add(divisionByZeroCheck(val));
            }
            FieldAccessExpr rewrittenTarget = fa.clone();
            rewrittenTarget.setScope(scope);
            rewritten.setTarget(rewrittenTarget);
            rewritten.setValue(val);

        } else {
            Expression val = needsDivisionCheck
                    ? extractToTemp(ae.getValue(), out)
                    : emit(ae.getValue(), out);
            if (needsDivisionCheck) out.add(divisionByZeroCheck(val));
            rewritten.setValue(val);
        }

        return rewritten;
    }

    /**
     * JLS §14.4.2: variable declarators are executed left-to-right; for each
     * declarator that has an initializer, the initializer expression is evaluated
     * and the resulting value is assigned to the variable.
     */
    private Expression emitVarDecl(VariableDeclarationExpr vd, NodeList<Statement> out) {
        VariableDeclarationExpr rewritten = vd.clone();
        boolean multiDeclarator = vd.getVariables().size() > 1;
        for (int i = 0; i < vd.getVariables().size(); i++) {
            if (vd.getVariable(i).getInitializer().isPresent()) {
                Expression init = vd.getVariable(i).getInitializer().get();
                if (!multiDeclarator) {
                    // Single declarator: no interleaving issue; emit inline.
                    rewritten.getVariable(i).setInitializer(emit(init, out));
                } else if (init instanceof ArrayInitializerExpr) {
                    // `var` cannot infer the type of `{...}` — use the declared type.
                    Expression emitted = emit(init, out);
                    String keyword = emitFinalVars ? "final " : "";
                    String type = vd.getVariable(i).getType().asString();
                    String tmp = freshTmp();
                    out.add(parseStatement(keyword + type + " " + tmp + " = " + emitted + ";"));
                    rewritten.getVariable(i).setInitializer(new NameExpr(tmp));
                } else {
                    rewritten.getVariable(i).setInitializer(extractToTemp(init, out));
                }
            }
        }
        return rewritten;
    }

    /**
     * JLS §15.16: the expression is evaluated, then the cast is performed.
     * Two implicit exceptions are possible:
     * <ul>
     *   <li>Cast to reference type (§5.1.6): ClassCastException if the value is
     *       non-null and not an instance of the target type.</li>
     *   <li>Cast to primitive type from a reference type (unboxing, §5.1.8):
     *       NullPointerException if the value is null (e.g. {@code (int) Integer}).</li>
     * </ul>
     * Primitive-to-primitive casts (widening/narrowing, §5.1.2–§5.1.3) never throw.
     */
    private Expression emitCast(CastExpr cast, NodeList<Statement> out) {
        CastExpr rewritten = cast.clone();
        Expression val = extractToTemp(cast.getExpression(), out);
        rewritten.setExpression(val);
        if (cast.getType().isPrimitiveType()) {
            // Unboxing: null reference → NullPointerException (§5.1.8).
            // Skip when InstrumenterVisitor injected this cast for ternary-branch
            // narrowing — the operand is always primitive, so unboxing cannot occur.
            if (!cast.containsData(AbstractInstrumenterVisitor.TERNARY_REWRITE_CAST)
                    && cast.getExpression().calculateResolvedType().isReferenceType()) {
                out.add(nullCheck(val));
            }
        } else {
            out.add(castCheck(val, cast.getType().asString()));
        }
        return rewritten;
    }

    /**
     * JLS §15.10.1: dimension expressions are evaluated left-to-right. After all
     * dimensions have been evaluated, a NegativeArraySizeException is thrown if any
     * value is negative.
     */
    private Expression emitArrayCreation(ArrayCreationExpr ac, NodeList<Statement> out) {
        ArrayCreationExpr rewritten = ac.clone();

        if (ac.getInitializer().isPresent()) {
            rewritten.setInitializer((ArrayInitializerExpr) emitArrayInitializer(ac.getInitializer().get(), out));
            return rewritten;
        }

        List<Expression> dimTemps = new ArrayList<>();
        for (int i = 0; i < ac.getLevels().size(); i++) {
            if (ac.getLevels().get(i).getDimension().isPresent()) {
                Expression dim = extractToTemp(ac.getLevels().get(i).getDimension().get(), out);
                rewritten.getLevels().get(i).setDimension(dim);
                dimTemps.add(dim);
            }
        }
        dimTemps.forEach(dim -> out.add(negativeSizeCheck(dim)));
        return rewritten;
    }

    /**
     * JLS §10.6: the component expressions of an array initializer are evaluated
     * left-to-right. Nested array initializers are themselves array initializers
     * and follow the same rule recursively.
     */
    private Expression emitArrayInitializer(ArrayInitializerExpr ai, NodeList<Statement> out) {
        ArrayInitializerExpr rewritten = ai.clone();
        for (int i = 0; i < ai.getValues().size(); i++) {
            Expression elem = ai.getValues().get(i);
            rewritten.getValues().set(i,
                    elem instanceof ArrayInitializerExpr nested
                            ? emitArrayInitializer(nested, out)
                            : extractToTemp(elem, out));
        }
        return rewritten;
    }

    /**
     * JLS §15.9.4: for a qualified class instance creation expression
     * ({@code outerObj.new Inner(...)}), the qualifying Primary is evaluated first;
     * if its value is null a NullPointerException is thrown <em>before</em> the
     * constructor arguments are evaluated. Arguments are then evaluated left-to-right.
     */
    private Expression emitObjectCreation(ObjectCreationExpr oc, NodeList<Statement> out) {
        ObjectCreationExpr rewritten = oc.clone();

        // §15.9.4 – qualifier evaluated and null-checked before args
        if (oc.getScope().isPresent() && !isNullSafe(oc.getScope().get())) {
            Expression scope = extractToTemp(oc.getScope().get(), out);
            rewritten.setScope(scope);
            out.add(nullCheck(scope));
        }

        // §15.9.4: arguments evaluated left-to-right. extractToTemp ensures each arg
        // (including inner method calls) is materialized in `out` in evaluation order,
        // consistent with the same fix applied to emitMethodCall.
        for (int i = 0; i < oc.getArguments().size(); i++) {
            rewritten.setArgument(i, extractToTemp(oc.getArgument(i), out));
        }
        return rewritten;
    }

    private Expression emitBinary(BinaryExpr be, NodeList<Statement> out) {
        return switch (be.getOperator()) {
            case AND, OR           -> emitShortCircuit(be, out);
            case DIVIDE, REMAINDER -> emitDivision(be, out);
            default                -> emitRegularBinary(be, out);
        };
    }

    /**
     * JLS §15.23 ({@code &&}) / §15.24 ({@code ||}): the left operand is always
     * evaluated. The right operand is evaluated only when the left does not
     * short-circuit the result. Both operators use {@code if (left)} as the branch
     * condition, matching the {@code _IF}/{@code _ELSE} convention:
     * <ul>
     *   <li>{@code &&}: {@code _IF} fires when left is {@code true} (right evaluated);
     *       {@code _ELSE} fires when left is {@code false} (short-circuit).</li>
     *   <li>{@code ||}: {@code _IF} fires when left is {@code true} (short-circuit);
     *       {@code _ELSE} fires when left is {@code false} (right evaluated).</li>
     * </ul>
     */
    private Expression emitShortCircuit(BinaryExpr be, NodeList<Statement> out) {
        boolean isAnd = be.getOperator() == BinaryExpr.Operator.AND;

        Expression leftVal = emit(be.getLeft(), out);
        String tmp = freshTmp();
        out.add(parseStatement("var " + tmp + " = " + leftVal + ";"));

        // Both && and || use `if (tmp)` — left value as condition, matching the
        // `if (left)` convention used by InstrumenterVisitor.
        // &&: left=true  → IF  branch: evaluate right; left=false → ELSE: short-circuit
        // ||: left=true  → IF  branch: short-circuit;  left=false → ELSE: evaluate right
        NodeList<Statement> takenOut = NodeList.nodeList(parseTracerCall(TracerMethod.IF));
        NodeList<Statement> skippedOut = NodeList.nodeList(parseTracerCall(TracerMethod.ELSE));

        if (isAnd) {
            Expression rightVal = emit(be.getRight(), takenOut);
            takenOut.add(parseStatement(tmp + " = " + rightVal + ";"));
        } else {
            Expression rightVal = emit(be.getRight(), skippedOut);
            skippedOut.add(parseStatement(tmp + " = " + rightVal + ";"));
        }

        out.add(new IfStmt(new NameExpr(tmp), new BlockStmt(takenOut), new BlockStmt(skippedOut)));
        return new NameExpr(tmp);
    }

    /**
     * JLS §15.17.2 ({@code /}) / §15.17.3 ({@code %}): left operand is evaluated
     * first, then right. An ArithmeticException is thrown if the divisor is zero,
     * but only for integer arithmetic — floating-point division produces
     * {@code Infinity}/{@code NaN} instead and never throws (§15.17.2).
     * The result type (not the operand types) determines whether integer arithmetic
     * is in effect, e.g. {@code int / double} promotes to {@code double}.
     */
    private Expression emitDivision(BinaryExpr be, NodeList<Statement> out) {
        BinaryExpr rewritten = be.clone();
        Expression leftVal  = extractToTemp(be.getLeft(), out);
        boolean needsCheck  = isIntegerDivision(be);
        // right side needs a temp when a check is needed so the same value
        // appears in both the check condition and the result expression
        Expression rightVal = needsCheck
                ? extractToTemp(be.getRight(), out)
                : emit(be.getRight(), out);
        if (needsCheck) {
            out.add(divisionByZeroCheck(rightVal));
        }
        rewritten.setLeft(leftVal);
        rewritten.setRight(rightVal);
        return rewritten;
    }

    private Expression emitRegularBinary(BinaryExpr be, NodeList<Statement> out) {
        BinaryExpr rewritten = be.clone();
        rewritten.setLeft(extractToTemp(be.getLeft(), out));
        rewritten.setRight(extractToTemp(be.getRight(), out));
        return rewritten;
    }

    /**
     * JLS §15.14 (postfix {@code ++}/{ @code --}) / §15.15 (prefix and other unary
     * operators): the operand expression is evaluated and then the unary operation is
     * applied. No implicit exception is introduced by the operator itself. For
     * {@code ++}/{@code --} on an array element or field, the necessary null and
     * bounds checks are emitted by the recursive {@link #emit} call on the operand
     * (handled by {@link #emitArrayAccess} or {@link #emitFieldAccess}).
     */
    private Expression emitUnary(UnaryExpr ue, NodeList<Statement> out) {
        UnaryExpr rewritten = ue.clone();
        rewritten.setExpression(emit(ue.getExpression(), out));
        return rewritten;
    }

    private Expression emitEnclosed(EnclosedExpr ee, NodeList<Statement> out) {
        EnclosedExpr rewritten = ee.clone();
        rewritten.setInner(emit(ee.getInner(), out));
        return rewritten;
    }

    /**
     * JLS §15.20.2: the RelationalExpression (left operand) is evaluated. The
     * {@code instanceof} operator itself never throws — {@code null instanceof T}
     * evaluates to {@code false} — but the left operand may contain sub-expressions
     * that do (e.g. array access, method call).
     */
    private Expression emitInstanceOf(InstanceOfExpr ie, NodeList<Statement> out) {
        InstanceOfExpr rewritten = ie.clone();
        rewritten.setExpression(emit(ie.getExpression(), out));
        return rewritten;
    }

    /**
     * JLS §15.25: the condition is evaluated first; only the selected branch is then
     * evaluated. Branch sub-expressions are handled by
     * {@link ImplicitExceptionVisitor}'s {@code visit(YieldStmt)}, which fires
     * during {@code super.visit()} before this method is reached (InstrumenterVisitor
     * has already wrapped each branch in a switch expression with an explicit
     * {@code yield}).
     */
    private Expression emitConditional(ConditionalExpr ce, NodeList<Statement> out) {
        ConditionalExpr rewritten = ce.clone(); // clone captures already-instrumented branches
        rewritten.setCondition(emit(ce.getCondition(), out));
        return rewritten;
    }

    // ── Core helper ──────────────────────────────────────────────────────────

    /**
     * Returns a side-effect-free expression for {@code expr}. If the result of
     * {@link #emit} is already simple (name/literal), it is returned directly;
     * otherwise a fresh temp variable is declared and its name is returned.
     * This ensures the value can be referenced multiple times (e.g. in a check
     * condition and in the rewritten expression) without double evaluation.
     */
    private Expression extractToTemp(Expression expr, NodeList<Statement> out) {
        Expression val = emit(expr, out);
        if (isSimpleExpression(val)) return val;
        String tmp = freshTmp();
        String keyword = emitFinalVars ? "final var" : "var";
        out.add(parseStatement(keyword + " " + tmp + " = " + val + ";"));
        return new NameExpr(tmp);
    }

    // ── Check-statement builders ─────────────────────────────────────────────

    private static Statement nullCheck(Expression value) {
        return parseStatement(
                "if (" + value + " == null) { "
                + IMPLICIT_EXCEPTION.getMethodCallString() + "; throw new java.lang.NullPointerException(); "
                + "} else { " + NO_IMPLICIT_EXCEPTION.getMethodCallString() + "; }");
    }

    private static Statement boundsCheck(Expression array, Expression index) {
        return parseStatement(
                "if (" + index + " < 0 || " + index + " >= " + array + ".length) { "
                + IMPLICIT_EXCEPTION.getMethodCallString() + "; throw new java.lang.ArrayIndexOutOfBoundsException(); "
                + "} else { " + NO_IMPLICIT_EXCEPTION.getMethodCallString() + "; }");
    }

    private static Statement arrayStoreCheck(Expression array, Expression value) {
        return parseStatement(
                "if (!" + array + ".getClass().getComponentType().isInstance(" + value + ")) { "
                + IMPLICIT_EXCEPTION.getMethodCallString() + "; throw new java.lang.ArrayStoreException(); "
                + "} else { " + NO_IMPLICIT_EXCEPTION.getMethodCallString() + "; }");
    }

    private static Statement divisionByZeroCheck(Expression divisor) {
        return parseStatement(
                "if (" + divisor + " == 0) { "
                + IMPLICIT_EXCEPTION.getMethodCallString() + "; throw new java.lang.ArithmeticException(); "
                + "} else { " + NO_IMPLICIT_EXCEPTION.getMethodCallString() + "; }");
    }

    private static Statement negativeSizeCheck(Expression size) {
        return parseStatement(
                "if (" + size + " < 0) { "
                + IMPLICIT_EXCEPTION.getMethodCallString() + "; throw new java.lang.NegativeArraySizeException(); "
                + "} else { " + NO_IMPLICIT_EXCEPTION.getMethodCallString() + "; }");
    }

    private static Statement castCheck(Expression value, String targetType) {
        return parseStatement(
                "if (" + value + " != null && !(" + value + " instanceof " + targetType + ")) { "
                + IMPLICIT_EXCEPTION.getMethodCallString() + "; throw new java.lang.ClassCastException(); "
                + "} else { " + NO_IMPLICIT_EXCEPTION.getMethodCallString() + "; }");
    }

    // ── Static predicates ────────────────────────────────────────────────────

    /**
     * True when this division/remainder expression uses integer arithmetic and can
     * therefore throw {@link ArithmeticException}. Float-point division never throws.
     */
    private static boolean isIntegerDivision(BinaryExpr be) {
        ResolvedType result = be.calculateResolvedType();
        return result.equals(ResolvedPrimitiveType.INT) || result.equals(ResolvedPrimitiveType.LONG);
    }

    private static boolean isCompoundDivision(AssignExpr ae) {
        return ae.getOperator() == AssignExpr.Operator.DIVIDE
                || ae.getOperator() == AssignExpr.Operator.REMAINDER;
    }

    /**
     * True when a {@code /=} or {@code %=} compound assignment uses integer arithmetic.
     * A float/double RHS promotes the entire operation to float arithmetic regardless
     * of the LHS type (e.g. {@code int x /= 2.0} never throws), so the RHS is checked
     * first; only an integral LHS with an integral RHS yields integer division.
     */
    private static boolean isIntegerCompoundDivision(AssignExpr ae) {
        ResolvedType rhsType = ae.getValue().calculateResolvedType();
        if (rhsType.equals(ResolvedPrimitiveType.FLOAT) || rhsType.equals(ResolvedPrimitiveType.DOUBLE)) {
            return false;
        }
        ResolvedType lhsType = ae.getTarget().calculateResolvedType();
        return lhsType.equals(ResolvedPrimitiveType.INT)   || lhsType.equals(ResolvedPrimitiveType.LONG)
            || lhsType.equals(ResolvedPrimitiveType.BYTE)  || lhsType.equals(ResolvedPrimitiveType.SHORT)
            || lhsType.equals(ResolvedPrimitiveType.CHAR);
    }

    /**
     * True when the expression's value can never be {@code null}: static fields,
     * enum constants, {@code this}/{@code super}, literals, or scopes that are
     * themselves null-safe.
     */
    static boolean isNullSafe(Expression expression) {
        return switch (expression) {
            case FieldAccessExpr fa -> {
                var resolved = fa.resolve();
                if (resolved instanceof ResolvedFieldDeclaration field)
                    yield field.isStatic() || isNullSafe(fa.getScope());
                if (resolved instanceof ResolvedEnumConstantDeclaration)
                    yield true;
                // array.length – array itself may be null
                yield false;
            }
            case MethodCallExpr mc ->
                    // srctracer.Trace calls are injected by InstrumenterVisitor; they are
                    // static and safe. Checked by string to avoid a compile-time dep on runtime.
                    (mc.getScope().isPresent()
                            && mc.getScope().get().toString().equals("srctracer.Trace"))
                    || mc.resolve().isStatic()
                    || mc.getScope().isEmpty()         // implicit this – never null
                    || isNullSafe(mc.getScope().get());
            case ThisExpr ignored   -> true;
            case SuperExpr ignored  -> true;
            case LiteralExpr ignored -> true;
            default                 -> false;
        };
    }
}
