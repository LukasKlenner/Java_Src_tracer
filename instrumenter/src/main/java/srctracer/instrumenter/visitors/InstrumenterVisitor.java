package srctracer.instrumenter.visitors;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
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
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import srctracer.database.FunctionDatabaseWriter;
import srctracer.trace.TracerMethod;
import srctracer.util.FunctionSignature;

import java.util.Optional;

import static srctracer.util.JavaParserUtil.findEnclosingReturnType;
import static srctracer.util.JavaParserUtil.insertAfter;
import static srctracer.util.JavaParserUtil.insertBefore;
import static srctracer.util.JavaParserUtil.isBreakForSwitch;
import static srctracer.util.JavaParserUtil.isInsideLambda;
import static srctracer.util.JavaParserUtil.isMainMethod;
import static srctracer.util.JavaParserUtil.parseStatement;
import static srctracer.util.JavaParserUtil.parseTracerCall;

public class InstrumenterVisitor extends ModifierVisitor<Void> {

    private final FunctionDatabaseWriter functionDatabaseWriter;

    int nextFuncId = 1;
    int nextSwitchId = 0;
    int nextTmpId = 0;

    private final InstrumenterStats stats = new InstrumenterStats();

    public InstrumenterVisitor(FunctionDatabaseWriter functionDatabaseWriter) {
        this.functionDatabaseWriter = functionDatabaseWriter;
    }

    // ---- Method / constructor entry ----

    @Override
    public Visitable visit(MethodDeclaration md, Void a) {
        super.visit(md, a);

        // TODO Methoden mit leerer Implementation machen es kaputt?
        if (md.getBody().isEmpty()) return md;

        // TODO keine private, static, etc Methoden tracen

        FunctionSignature signature = new FunctionSignature(
                (TypeDeclaration<?>) md.getParentNode().get(),
                md.getNameAsString(),
                md.getParameters(),
                md.getType()
        );

        insertFuncCall(
                md.getBody().get(),
                signature,
                0
        );

        if (isMainMethod(md)) {
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
        for (Statement s : original.getStatements()) {
            tryBlock.addStatement(s.clone());
        }

        BlockStmt finallyBlock = new BlockStmt();
        finallyBlock.addStatement(parseTracerCall(TracerMethod.TRACE_END));

        TryStmt tryStmt = new TryStmt();
        tryStmt.setTryBlock(tryBlock);
        tryStmt.setFinallyBlock(finallyBlock);

        BlockStmt newBody = new BlockStmt();
        newBody.addStatement(parseTracerCall(TracerMethod.TRACE_START, '"' + name + '"'));
        newBody.addStatement(tryStmt);

        md.setBody(newBody);
    }

    private static String enclosingTypeName(MethodDeclaration md) {
        Node cur = md.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof TypeDeclaration<?> td) return td.getNameAsString();
            cur = cur.getParentNode().orElse(null);
        }
        return "instrumented";
    }

    @Override
    public Visitable visit(ConstructorDeclaration cd, Void a) {
        super.visit(cd, a);

        // TODO eigentlich nicht tracen, oder?
        BlockStmt body = cd.getBody();
        FunctionSignature signature = new FunctionSignature(
                (TypeDeclaration<?>) cd.getParentNode().get(),
                cd.getNameAsString(),
                cd.getParameters(),
                new VoidType()
        );
        int idx = !body.getStatements().isEmpty()
                && body.getStatement(0) instanceof ExplicitConstructorInvocationStmt
                ? 1 : 0;

        insertFuncCall(
                body,
                signature,
                idx
        );
        return cd;
    }

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
        tryBlock.addStatement(0, parseTracerCall(TracerMethod.TRY));

        // Append _TRY_END only if the body can fall through; otherwise Java
        // would reject the trailing call as unreachable code.
        if (!alwaysExits(tryBlock)) {
            tryBlock.addStatement(parseTracerCall(TracerMethod.TRY_END));
        }

        NodeList<CatchClause> catches = n.getCatchClauses();
        for (int i = 0; i < catches.size(); i++) {
            BlockStmt catchBody = catches.get(i).getBody();
            catchBody.addStatement(0, parseTracerCall(TracerMethod.CATCH, i));
        }

        stats.incrementTryCount();
        return n;
    }

    public InstrumenterStats getStats() {
        return stats;
    }

    /**
     * Best-effort check: does control flow always leave {@code s} via return/throw?
     */
    private static boolean alwaysExits(Statement s) {
        if (s instanceof ReturnStmt) return true;
        if (s instanceof ThrowStmt) return true;
        if (s instanceof BlockStmt b) {
            if (b.getStatements().isEmpty()) return false;
            return alwaysExits(b.getStatement(b.getStatements().size() - 1));
        }
        if (s instanceof IfStmt i) {
            return i.getElseStmt().isPresent()
                    && alwaysExits(i.getThenStmt())
                    && alwaysExits(i.getElseStmt().get());
        }
        return false;
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
