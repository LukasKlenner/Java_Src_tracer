package srctracer.instrumenter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import srctracer.SourceTransformer;
import srctracer.database.FunctionDatabaseWriter;
import srctracer.instrumenter.visitors.BlockWrappingVisitor;
import srctracer.instrumenter.visitors.InstrumenterVisitor;

import java.util.ArrayList;
import java.util.List;

public class Instrumenter extends SourceTransformer {

    private final FunctionDatabaseWriter functionDatabaseWriter;

    public Instrumenter(FunctionDatabaseWriter functionDatabaseWriter) {
        this.functionDatabaseWriter = functionDatabaseWriter;
    }

    @Override
    protected void performTransformation(CompilationUnit cu) {
        cu.accept(new BlockWrappingVisitor(), null);
        extractFieldInitializers(cu);

        InstrumenterVisitor v = new InstrumenterVisitor(functionDatabaseWriter);
        cu.accept(v, null);

        System.out.println(v.getStats().getStatsSummary());
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

}
