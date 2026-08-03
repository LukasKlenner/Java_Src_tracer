package srctracer.instrumenter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
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
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: instrumenter <input.java> <output.java>");
            System.exit(1);
        }

        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);

        CompilationUnit cu = StaticJavaParser.parse(input);

        // Pass 1: wrap single-statement loop/conditional bodies in BlockStmt so
        // sibling-insertion (needed for _LOOP_END) works in pass 2.
        cu.accept(new BlockWrappingVisitor(), null);

        // Pass 1.5: extract field initializers with method calls into
        // initializer blocks so they get instrumented in pass 2.
        extractFieldInitializers(cu);

        // Pass 2: actual trace-call insertion.
        InstrumenterVisitor v = new InstrumenterVisitor();
        cu.accept(v, null);

        Files.writeString(output, cu.toString());
        System.out.println("Wrote: " + output
                + " (" + v.methods + " methods, "
                + v.initializers + " initializers, "
                + v.ifs + " if, "
                + v.returns + " return, "
                + v.loops + " loop, "
                + v.switches + " switch, "
                + v.tries + " try, "
                + v.mains + " main wrapped)");
    }

    /**
     * Ensures every if-branch, loop body etc. is a BlockStmt rather than a bare statement.
     */
    private static class BlockWrappingVisitor extends ModifierVisitor<Void> {
        @Override
        public Visitable visit(IfStmt n, Void a) {
            super.visit(n, a);
            n.setThenStmt(ensureBlock(n.getThenStmt()));
            n.getElseStmt().ifPresent(e -> n.setElseStmt(ensureBlock(e)));
            return n;
        }

        @Override
        public Visitable visit(WhileStmt n, Void a) {
            super.visit(n, a);
            n.setBody(ensureBlock(n.getBody()));
            return n;
        }

        @Override
        public Visitable visit(DoStmt n, Void a) {
            super.visit(n, a);
            n.setBody(ensureBlock(n.getBody()));
            return n;
        }

        @Override
        public Visitable visit(ForStmt n, Void a) {
            super.visit(n, a);
            n.setBody(ensureBlock(n.getBody()));
            return n;
        }

        @Override
        public Visitable visit(ForEachStmt n, Void a) {
            super.visit(n, a);
            n.setBody(ensureBlock(n.getBody()));
            return n;
        }

        private static BlockStmt ensureBlock(Statement s) {
            if (s instanceof BlockStmt b) return b;
            BlockStmt block = new BlockStmt();
            block.addStatement(s);
            return block;
        }
    }

    private static void extractFieldInitializers(CompilationUnit cu) {
        @SuppressWarnings("unchecked")
        List<TypeDeclaration<?>> types =
                (List<TypeDeclaration<?>>) (List<?>) cu.findAll(TypeDeclaration.class);

        for (TypeDeclaration<?> td : types) {
            if (td instanceof ClassOrInterfaceDeclaration coi && coi.isInterface()) continue;

            List<BodyDeclaration<?>> snapshot = new ArrayList<>(td.getMembers());
            for (BodyDeclaration<?> member : snapshot) {
                if (!(member instanceof FieldDeclaration fd)) continue;

                List<VariableDeclarator> toExtract = new ArrayList<>();
                for (VariableDeclarator vd : fd.getVariables()) {
                    if (vd.getInitializer().isEmpty()) continue;
                    Expression init = vd.getInitializer().get();
                    if (!init.findAll(MethodCallExpr.class).isEmpty()
                            || !init.findAll(ObjectCreationExpr.class).isEmpty()) {
                        toExtract.add(vd);
                    }
                }
                if (toExtract.isEmpty()) continue;

                boolean isStatic = fd.isStatic();
                BlockStmt blockBody = new BlockStmt();

                for (VariableDeclarator vd : toExtract) {
                    Expression init = vd.getInitializer().get();
                    vd.removeInitializer();
                    blockBody.addStatement(StaticJavaParser.parseStatement(
                            vd.getNameAsString() + " = " + init + ";"));
                }

                InitializerDeclaration initBlock =
                        new InitializerDeclaration(isStatic, blockBody);

                NodeList<BodyDeclaration<?>> members = td.getMembers();
                int fdIdx = members.indexOf(fd);
                members.add(fdIdx + 1, initBlock);
            }
        }
    }

    private static class InstrumenterVisitor extends ModifierVisitor<Void> {
        int nextFuncId = 1;
        int nextSwitchId = 0;
        int nextTmpId = 0;

        int methods = 0;
        int ifs = 0;
        int returns = 0;
        int loops = 0;
        int switches = 0;
        int tries = 0;
        int mains = 0;
        int initializers = 0;

        // ---- Method / constructor entry ----

        @Override
        public Visitable visit(MethodDeclaration md, Void a) {
            super.visit(md, a);
            if (md.getBody().isEmpty()) return md;

            int funcId = nextFuncId++;
            methods++;
            Statement funcCall = parseStatement(
                    "srctracer.Trace._FUNC(" + funcId + ");");

            if (isMainMethod(md)) {
                wrapMainWithLifecycle(md, funcCall);
                mains++;
            } else {
                md.getBody().get().addStatement(0, funcCall);
            }
            return md;
        }

        /**
         * Replaces main's body with:
         * trace_start("<ClassName>");
         * try { _FUNC(id); <original statements...> }
         * finally { trace_end(); }
         */
        private void wrapMainWithLifecycle(MethodDeclaration md, Statement funcCall) {
            BlockStmt original = md.getBody().get();
            String name = enclosingTypeName(md);

            BlockStmt tryBlock = new BlockStmt();
            tryBlock.addStatement(funcCall);
            for (Statement s : original.getStatements()) {
                tryBlock.addStatement(s.clone());
            }

            BlockStmt finallyBlock = new BlockStmt();
            finallyBlock.addStatement(parseStatement("srctracer.Trace.trace_end();"));

            TryStmt tryStmt = new TryStmt();
            tryStmt.setTryBlock(tryBlock);
            tryStmt.setFinallyBlock(finallyBlock);

            BlockStmt newBody = new BlockStmt();
            newBody.addStatement(parseStatement(
                    "srctracer.Trace.trace_start(\"" + name + "\");"));
            newBody.addStatement(tryStmt);

            md.setBody(newBody);
        }

        private static boolean isMainMethod(MethodDeclaration md) {
            if (!md.getNameAsString().equals("main")) return false;
            if (!md.isStatic()) return false;
            if (!md.getType().toString().equals("void")) return false;
            if (md.getParameters().size() != 1) return false;
            String pt = md.getParameter(0).getType().toString();
            return pt.equals("String[]") || pt.equals("java.lang.String[]");
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
            BlockStmt body = cd.getBody();
            int idx = !body.getStatements().isEmpty()
                    && body.getStatement(0) instanceof ExplicitConstructorInvocationStmt
                    ? 1 : 0;
            insertFuncCall(body, idx);
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
            ((BlockStmt) n.getThenStmt()).addStatement(0, parseCall("_IF"));

            BlockStmt elseBlock = n.getElseStmt()
                    .map(s -> (BlockStmt) s)
                    .orElseGet(() -> {
                        BlockStmt b = new BlockStmt();
                        n.setElseStmt(b);
                        return b;
                    });
            elseBlock.addStatement(0, parseCall("_ELSE"));

            ifs++;
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
                replacement.addStatement(parseCall("_RETURN"));
                replacement.addStatement(new ReturnStmt());
            } else {
                // return EXPR; -> { Type tmp = EXPR; _RETURN(); return tmp; }
                Optional<Type> retType = findEnclosingReturnType(n);
                if (retType.isEmpty()) return n; // bail safely

                String tmp = "__srctracer_ret$" + nextTmpId++;
                replacement.addStatement(parseStatement(
                        retType.get().toString() + " " + tmp + " = " + expr.get().toString() + ";"));
                replacement.addStatement(parseCall("_RETURN"));
                replacement.addStatement(parseStatement("return " + tmp + ";"));
            }
            returns++;
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
            body.addStatement(0, parseCall("_LOOP_BODY"));
            insertAfter(loopStmt, parseCall("_LOOP_END"));
            loops++;
        }

        // ---- Break ----

        @Override
        public Visitable visit(BreakStmt n, Void a) {
            super.visit(n, a);
            if (n.getLabel().isPresent()) return n;     // labeled break — skip for now
            if (isBreakForSwitch(n)) return n;          // switch break — not a loop exit
            insertBefore(n, parseCall("_BREAK"));
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
                        "if (" + flag + ") { srctracer.Trace._CASE(" + i + "); "
                                + flag + " = false; }");
                entry.getStatements().add(0, caseRecord);
            }
            switches++;
            return n;
        }

        // ---- Try / catch ----

        @Override
        public Visitable visit(TryStmt n, Void a) {
            super.visit(n, a);

            BlockStmt tryBlock = n.getTryBlock();
            tryBlock.addStatement(0, parseCall("_TRY"));

            // Append _TRY_END only if the body can fall through; otherwise Java
            // would reject the trailing call as unreachable code.
            if (!alwaysExits(tryBlock)) {
                tryBlock.addStatement(parseCall("_TRY_END"));
            }

            NodeList<CatchClause> catches = n.getCatchClauses();
            for (int i = 0; i < catches.size(); i++) {
                BlockStmt catchBody = catches.get(i).getBody();
                catchBody.addStatement(0, parseStatement(
                        "srctracer.Trace._CATCH(" + i + ");"));
            }

            tries++;
            return n;
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

        private void insertFuncCall(BlockStmt body, int index) {
            int id = nextFuncId++;
            methods++;
            body.addStatement(index, parseStatement(
                    "srctracer.Trace._FUNC(" + id + ");"));
        }

        private static Statement parseCall(String method) {
            return parseStatement("srctracer.Trace." + method + "();");
        }

        private static Statement parseStatement(String code) {
            return StaticJavaParser.parseStatement(code);
        }

        private static void insertBefore(Node n, Statement newStmt) {
            Node parent = n.getParentNode().orElse(null);
            if (parent instanceof BlockStmt block) {
                int idx = block.getStatements().indexOf(n);
                if (idx >= 0) block.addStatement(idx, newStmt);
            } else if (parent instanceof SwitchEntry entry) {
                int idx = entry.getStatements().indexOf(n);
                if (idx >= 0) entry.getStatements().add(idx, newStmt);
            }
        }

        private static void insertAfter(Node n, Statement newStmt) {
            Node parent = n.getParentNode().orElse(null);
            if (parent instanceof BlockStmt block) {
                int idx = block.getStatements().indexOf(n);
                if (idx >= 0) block.addStatement(idx + 1, newStmt);
            } else if (parent instanceof SwitchEntry entry) {
                int idx = entry.getStatements().indexOf(n);
                if (idx >= 0) entry.getStatements().add(idx + 1, newStmt);
            }
        }

        private static boolean isInsideLambda(Node n) {
            Node cur = n.getParentNode().orElse(null);
            while (cur != null) {
                if (cur instanceof LambdaExpr) return true;
                if (cur instanceof MethodDeclaration) return false;
                if (cur instanceof ConstructorDeclaration) return false;
                cur = cur.getParentNode().orElse(null);
            }
            return false;
        }

        private static boolean isBreakForSwitch(BreakStmt n) {
            Node cur = n.getParentNode().orElse(null);
            while (cur != null) {
                if (cur instanceof SwitchStmt) return true;
                if (cur instanceof WhileStmt
                        || cur instanceof DoStmt
                        || cur instanceof ForStmt
                        || cur instanceof ForEachStmt) return false;
                cur = cur.getParentNode().orElse(null);
            }
            return false;
        }

        private static Optional<Type> findEnclosingReturnType(Node n) {
            Node cur = n.getParentNode().orElse(null);
            while (cur != null) {
                if (cur instanceof MethodDeclaration md) return Optional.of(md.getType());
                if (cur instanceof ConstructorDeclaration) return Optional.empty();
                if (cur instanceof LambdaExpr) return Optional.empty();
                cur = cur.getParentNode().orElse(null);
            }
            return Optional.empty();
        }
    }
}
