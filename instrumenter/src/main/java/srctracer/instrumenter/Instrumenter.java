package srctracer.instrumenter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Processor;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
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
import com.github.javaparser.ast.validator.ProblemReporter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import srctracer.Config;
import srctracer.SourceTransformer;
import srctracer.database.FunctionDatabaseWriter;
import srctracer.instrumenter.validation.FeatureValidator;
import srctracer.instrumenter.visitors.BlockWrappingVisitor;
import srctracer.instrumenter.visitors.implicit.ImplicitExceptionVisitor;
import srctracer.instrumenter.visitors.InstrumenterVisitor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Instrumenter extends SourceTransformer {

    public static final String MAIN_LIFECYCLE_CATCH_PARAM = "__srctracer_main_lifecycle_catch_param";

    private final FunctionDatabaseWriter functionDatabaseWriter;
    private final String lifecycleMethodName;

    public Instrumenter(FunctionDatabaseWriter functionDatabaseWriter, List<Path> sourceRoots, List<Path> jars) throws IOException {
        this(functionDatabaseWriter, sourceRoots, jars, null);
    }

    public Instrumenter(FunctionDatabaseWriter functionDatabaseWriter, List<Path> sourceRoots, List<Path> jars, String lifecycleMethodName) throws IOException {
        super(createJavaParser(sourceRoots, jars));
        this.functionDatabaseWriter = functionDatabaseWriter;
        this.lifecycleMethodName = lifecycleMethodName;
    }

    private static JavaParser createJavaParser(List<Path> sourceRoots, List<Path> jars) throws IOException {
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(Config.DEFAULT_SOURCE_LANGUAGE_LEVEL);

        // Feature validation
        FeatureValidator fv = new FeatureValidator();
        cfg.getProcessors().add(() -> new Processor() {
            @Override
            public void postProcess(ParseResult<? extends Node> r, ParserConfiguration c) {
                r.getResult().ifPresent(n ->
                        fv.accept(n, new ProblemReporter(r.getProblems()::add)));
            }
        });

        // type solver
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        for (Path src : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(src));
        }
        for (Path jar : jars) {
            typeSolver.add(new JarTypeSolver(jar));
        }
        cfg.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        return new JavaParser(cfg);
    }

    @Override
    protected void performTransformation(CompilationUnit cu) {
        cu.accept(new BlockWrappingVisitor(), null);

        // TODO clenaup als visitor?
        extractFieldInitializers(cu);

        InstrumenterVisitor v = new InstrumenterVisitor(functionDatabaseWriter, lifecycleMethodName);
        cu.accept(v, null);
        cu.accept(new ImplicitExceptionVisitor(lifecycleMethodName), null);

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
