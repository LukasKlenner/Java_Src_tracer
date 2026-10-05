package srctracer.instrumenter.visitors;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedType;
import srctracer.database.FunctionDatabaseWriter;
import srctracer.trace.TracerField;
import srctracer.trace.TracerMethod;
import srctracer.util.FunctionSignature;
import srctracer.util.JavaParserUtil;

import java.util.Optional;

import static com.github.javaparser.StaticJavaParser.parseType;
import static srctracer.instrumenter.Instrumenter.MAIN_LIFECYCLE_CATCH_PARAM;
import static srctracer.util.JavaParserUtil.alwaysExits;
import static srctracer.util.JavaParserUtil.findEnclosingReturnType;
import static srctracer.util.JavaParserUtil.insertAfter;
import static srctracer.util.JavaParserUtil.insertBefore;
import static srctracer.util.JavaParserUtil.isBreakForSwitch;
import static srctracer.util.JavaParserUtil.isInsideLambda;
import static srctracer.util.JavaParserUtil.isMainMethod;
import static srctracer.util.JavaParserUtil.isNarrowPrimitive;
import static srctracer.util.JavaParserUtil.parseStatement;
import static srctracer.util.JavaParserUtil.parseTracerCall;
import static srctracer.util.JavaParserUtil.parseTracerCallExpr;
import static srctracer.util.JavaParserUtil.resolvedToAstType;
import static srctracer.util.JavaParserUtil.unwrapEnclosed;
import static srctracer.util.JavaParserUtil.wrapInSwitchExpression;

public class InstrumenterVisitor extends ModifierVisitor<Void> {

    private final FunctionDatabaseWriter functionDatabaseWriter;
    private final String lifecycleMethodName;

    int nextFuncId = 1;
    int nextSwitchId = 0;
    int nextTmpId = 0;

    private final InstrumenterStats stats = new InstrumenterStats();

    public InstrumenterVisitor(FunctionDatabaseWriter functionDatabaseWriter) {
        this(functionDatabaseWriter, null);
    }

    public InstrumenterVisitor(FunctionDatabaseWriter functionDatabaseWriter, String lifecycleMethodName) {
        this.functionDatabaseWriter = functionDatabaseWriter;
        this.lifecycleMethodName = lifecycleMethodName;
    }

    // ---- Method / constructor entry ----

    @Override
    public Visitable visit(MethodDeclaration md, Void a) {
        boolean isLifecycleMethod = isLifecycleMethod(md);
        boolean isMainMethod = isMainMethod(md);

        if (isMainMethod && !isLifecycleMethod) {
            // Do not instrument the main method if it's a lifecycle method
            return md;
        }
        super.visit(md, a);

        if (md.getBody().isEmpty()) return md;
        BlockStmt body = md.getBody().get();

        if (!md.isPrivate() && !md.isStatic()) {
            FunctionSignature signature = new FunctionSignature(
                    (TypeDeclaration<?>) md.getParentNode().get(),
                    md.getNameAsString(),
                    md.getParameters(),
                    md.getType()
            );

            insertFuncCall(
                    body,
                    signature,
                    0
            );
        }

        // record implicit return in void methods
        if (!alwaysExits(body)) {
            body.addStatement(parseTracerCall(TracerMethod.RETURN));
        }

        if (isLifecycleMethod) {
            wrapMainWithLifecycle(md);
            stats.incrementMainCount();
        }

        return md;
    }

    /**
     * Replaces main's body with:
     * trace_start("<ClassName>");
     * try { _FUNC(id); <original statements...> }
     * finally { trace_end(); }
     */
    private void wrapMainWithLifecycle(MethodDeclaration md) {
        BlockStmt original = md.getBody().get();
        String name = enclosingTypeName(md);

        BlockStmt tryBlock = new BlockStmt();
        // TODO this (and the catch) is only needed for key retracing and do not atually exists in the original method
        tryBlock.addStatement(parseTracerCall(TracerMethod.TRY));
        for (Statement s : original.getStatements()) {
            tryBlock.addStatement(s.clone());
        }

        BlockStmt finallyBlock = new BlockStmt();
        finallyBlock.addStatement(parseTracerCall(TracerMethod.TRACE_END));

        TryStmt tryStmt = new TryStmt();
        tryStmt.setTryBlock(tryBlock);
        tryStmt.setFinallyBlock(finallyBlock);

        CatchClause catchClause = new CatchClause();
        catchClause.setParameter(new Parameter(parseType("java.lang.Throwable"), MAIN_LIFECYCLE_CATCH_PARAM));
        BlockStmt catchBody = new BlockStmt();
        catchBody.addStatement(parseTracerCall(TracerMethod.CATCH, TracerField.TOTAL_CATCH_COUNT.getFieldAccessString()));
        catchBody.addStatement(parseStatement("throw " + MAIN_LIFECYCLE_CATCH_PARAM + ";"));
        catchClause.setBody(catchBody);
        tryStmt.setCatchClauses(new NodeList<>(catchClause));

        BlockStmt newBody = new BlockStmt();
        newBody.addStatement(parseTracerCall(TracerMethod.TRACE_START, '"' + name + '"'));
        newBody.addStatement(tryStmt);

        md.setBody(newBody);
    }

    private boolean isLifecycleMethod(MethodDeclaration md) {
        if (lifecycleMethodName != null) {
            return md.getNameAsString().equals(lifecycleMethodName);
        }
        return isMainMethod(md);
    }

    private static String enclosingTypeName(MethodDeclaration md) {
        Node cur = md.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof TypeDeclaration<?> td) return td.getNameAsString();
            cur = cur.getParentNode().orElse(null);
        }
        return "instrumented";
    }

//    @Override
//    public Visitable visit(ConstructorDeclaration cd, Void a) {
//        super.visit(cd, a);
//
//        // TODO eigentlich nicht tracen, oder?
//        BlockStmt body = cd.getBody();
//        FunctionSignature signature = new FunctionSignature(
//                (TypeDeclaration<?>) cd.getParentNode().get(),
//                cd.getNameAsString(),
//                cd.getParameters(),
//                new VoidType()
//        );
//        int idx = !body.getStatements().isEmpty()
//                && body.getStatement(0) instanceof ExplicitConstructorInvocationStmt
//                ? 1 : 0;
//
//        insertFuncCall(
//                body,
//                signature,
//                idx
//        );
//        return cd;
//    }

    // ---- Initializer blocks ----

//        @Override
//        public Visitable visit(InitializerDeclaration n, Void a) {
//            super.visit(n, a);
//            int id = nextFuncId++;
//            initializers++;
//            n.getBody().addStatement(0, parseStatement(
//                    "srctracer.Trace._FUNC(" + id + ");"));
//            return n;
//        }

    // ---- If / else ----

    @Override
    public Visitable visit(IfStmt n, Void a) {
        super.visit(n, a);

        // Pre-pass guarantees thenStmt is a BlockStmt.
        ((BlockStmt) n.getThenStmt()).addStatement(0, parseTracerCall(TracerMethod.IF));

        BlockStmt elseBlock = n.getElseStmt()
                .map(s -> (BlockStmt) s)
                .orElseGet(() -> {
                    BlockStmt b = new BlockStmt();
                    n.setElseStmt(b);
                    return b;
                });
        elseBlock.addStatement(0, parseTracerCall(TracerMethod.ELSE));

        stats.incrementIfCount();
        return n;
    }

    // ---- Ternary operator ----

    @Override
    public Visitable visit(ConditionalExpr n, Void a) {
        n.setCondition((Expression) n.getCondition().accept(this, a));

        Optional<Type> castType = computePreservationCast(n);

        Expression thenExpr = n.getThenExpr().clone();
        Expression elseExpr = n.getElseExpr().clone();

        n.setThenExpr(wrapInSwitchExpression(thenExpr));
        n.setElseExpr(wrapInSwitchExpression(elseExpr));

        insertBefore(thenExpr.getParentNode().get(), parseTracerCall(TracerMethod.IF));
        insertBefore(elseExpr.getParentNode().get(), parseTracerCall(TracerMethod.ELSE));

        thenExpr.accept(this, a);
        elseExpr.accept(this, a);

        stats.incrementTernaryCount();
        return castType.<Visitable>map(t -> new CastExpr(t, new EnclosedExpr(n))).orElse(n);
    }

    private Optional<Type> computePreservationCast(ConditionalExpr n) {
        ResolvedType ternaryType = n.calculateResolvedType();

        // Issue 1: original type is char/byte/short because constant-fitting rule applied.
        // After wrapping (no longer constant), binary promotion gives int instead.
        if (isNarrowPrimitive(ternaryType)) {
            ResolvedType thenType = n.getThenExpr().calculateResolvedType();
            ResolvedType elseType = n.getElseExpr().calculateResolvedType();
            if (thenType.equals(ResolvedPrimitiveType.INT) || elseType.equals(ResolvedPrimitiveType.INT))
                return Optional.of(resolvedToAstType(ternaryType));
        }

        // Issue 2: ternary type is int assigned to byte/short/char.
        // Original compiled → was a constant expression; wrapping loses constantness.
        if (ternaryType.isPrimitive() && ternaryType.asPrimitive() == ResolvedPrimitiveType.INT)
            return getAssignmentTargetType(n).filter(JavaParserUtil::isNarrowPrimitive);

        return Optional.empty();
    }

    private Optional<Type> getAssignmentTargetType(ConditionalExpr n) {
        Node parent = unwrapEnclosed(n.getParentNode().orElse(null));
        if (parent instanceof VariableDeclarator vd)
            return Optional.of(vd.getType());
        if (parent instanceof AssignExpr ae) {
            try { return Optional.of(resolvedToAstType(ae.getTarget().calculateResolvedType())); }
            catch (Exception ignored) {}
        }
        if (parent instanceof ReturnStmt)
            return n.findAncestor(MethodDeclaration.class).map(MethodDeclaration::getType);
        return Optional.empty();
    }


    // ---- Return ----

    @Override
    public Visitable visit(ReturnStmt n, Void a) {
        super.visit(n, a);

        // Returns inside lambdas exit the lambda, not the enclosing method. Skip.
        if (isInsideLambda(n)) return n;

        BlockStmt replacement = new BlockStmt();
        Optional<Expression> expr = n.getExpression();

        if (expr.isEmpty()) {
            // void return: { _RETURN(); return; }
            replacement.addStatement(parseTracerCall(TracerMethod.RETURN));
            replacement.addStatement(new ReturnStmt());
        } else {
            // return EXPR; -> { Type tmp = EXPR; _RETURN(); return tmp; }
            Optional<Type> retType = findEnclosingReturnType(n);
            if (retType.isEmpty()) return n; // bail safely

            String tmp = "__srctracer_ret$" + nextTmpId++;
            replacement.addStatement(parseStatement(retType.get() + " " + tmp + " = " + expr.get() + ";"));
            replacement.addStatement(parseTracerCall(TracerMethod.RETURN));
            replacement.addStatement(parseStatement("return " + tmp + ";"));
        }
        stats.incrementReturnCount();
        return replacement;
    }

    // ---- Loops ----

    @Override
    public Visitable visit(WhileStmt n, Void a) {
        super.visit(n, a);
        instrumentLoop(n, (BlockStmt) n.getBody());
        return n;
    }

    @Override
    public Visitable visit(DoStmt n, Void a) {
        super.visit(n, a);
        instrumentLoop(n, (BlockStmt) n.getBody());
        return n;
    }

    @Override
    public Visitable visit(ForStmt n, Void a) {
        super.visit(n, a);
        instrumentLoop(n, (BlockStmt) n.getBody());
        return n;
    }

    @Override
    public Visitable visit(ForEachStmt n, Void a) {
        super.visit(n, a);
        instrumentLoop(n, (BlockStmt) n.getBody());
        return n;
    }

    private void instrumentLoop(Statement loopStmt, BlockStmt body) {
        body.addStatement(0, parseTracerCall(TracerMethod.LOOP_BODY));
        insertAfter(loopStmt, parseTracerCall(TracerMethod.LOOP_END));
        stats.incrementLoopCount();
    }

    // ---- Break ----

    @Override
    public Visitable visit(BreakStmt n, Void a) {
        super.visit(n, a);
        if (n.getLabel().isPresent()) return n;     // labeled break — skip for now
        if (isBreakForSwitch(n)) return n;          // switch break — not a loop exit
        insertBefore(n, parseTracerCall(TracerMethod.BREAK));
        return n;
    }

    // ---- Switch (fall-through-safe, 6-bit case-id encoding) ----

    @Override
    public Visitable visit(SwitchStmt n, Void a) {
        super.visit(n, a);

        int switchId = nextSwitchId++;
        String flag = "__srctracer_switch$" + switchId;

        insertBefore(n, parseStatement("boolean " + flag + " = true;"));

        NodeList<SwitchEntry> entries = n.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            SwitchEntry entry = entries.get(i);
            Statement caseRecord = parseStatement(
                    "if (" + flag + ") { " +
                            TracerMethod.CASE.getMethodCallString(i) +
                            flag + " = false; }");
            entry.getStatements().add(0, caseRecord);
        }
        stats.incrementSwitchCount();
        return n;
    }

    // ---- Try / catch ----

    @Override
    public Visitable visit(TryStmt n, Void a) {
        super.visit(n, a);

        BlockStmt tryBlock = n.getTryBlock();
        NodeList<CatchClause> catches = n.getCatchClauses();
        int catchCount = catches.size();

        String catchedExceptionVar = "__srctracer_catched_exception_$" + nextTmpId++;
        insertBefore(n, parseStatement("boolean " + catchedExceptionVar + " = false;"));
        tryBlock.addStatement(0, parseTracerCall(TracerMethod.TRY));

        // Append _TRY_END only if the body can fall through; otherwise Java
        // would reject the trailing call as unreachable code.
        if (!alwaysExits(tryBlock)) {
            tryBlock.addStatement(
                    parseTracerCall(TracerMethod.TRY_END)
            );
        }

        // instrument catch blocks
        for (int i = 0; i < catches.size(); i++) {
            BlockStmt catchBody = catches.get(i).getBody();

            catchBody.addStatement(
                    0,
                    parseTracerCall(TracerMethod.CATCH, TracerField.TOTAL_CATCH_COUNT.getFieldAccessString() + " + " + i));
            catchBody.addStatement(
                    1,
                    parseStatement(TracerField.TOTAL_CATCH_COUNT.getFieldAccessString() + " += " + catchCount + ";")
            );
            catchBody.addStatement(
                    2,
                    parseStatement(catchedExceptionVar + " = true;")
            );
        }

        // instrument finally block
        BlockStmt finallyBlock = n.getFinallyBlock()
                .orElseGet(() -> {
                    BlockStmt block = new BlockStmt();
                    n.setFinallyBlock(block);
                    return block;
                });

        // only increment catch count if the exception was not catched by any of the catch blocks
        finallyBlock.addStatement(
                0,
                parseStatement(
                        "if (!" + catchedExceptionVar + ") {" +
                                TracerField.TOTAL_CATCH_COUNT.getFieldAccessString() + " += " + catchCount + ";" +
                                "}"
                )
        );

        stats.incrementTryCount();
        return n;
    }

    // ---- Implicit Exceptions ----

    @Override
    public Visitable visit(ArrayAccessExpr n, Void a) {
        super.visit(n, a);

        return n;
    }

    public InstrumenterStats getStats() {
        return stats;
    }

    // ---- Helpers ----

    private void insertFuncCall(
            BlockStmt body,
            FunctionSignature signature,
            int index
    ) {
        int id = createNewFunctionId(signature);
        body.addStatement(index, parseTracerCall(TracerMethod.FUNCTION_CALL, id));
        stats.incrementMethodCount();
    }

    private int createNewFunctionId(FunctionSignature signature) {
        int id = nextFuncId++;
        functionDatabaseWriter.storeFunctionId(id, signature);
        return id;
    }

}
