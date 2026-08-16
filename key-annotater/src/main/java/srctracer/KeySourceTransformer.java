package srctracer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import srctracer.database.FunctionDatabaseReader;
import srctracer.printer.JmlPrinter;
import srctracer.trace.Trace;
import srctracer.trace.TraceElement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static srctracer.util.JavaParserUtil.isMainMethod;

public class KeySourceTransformer extends SourceTransformer {

    private MethodDeclaration tracedMethod;

    @Override
    protected void performTransformation(CompilationUnit compilationUnit) {
        setJmlPrinter(compilationUnit);
        KeyAnnotaterVisitor visitor = new KeyAnnotaterVisitor();
        visitor.visit(compilationUnit, null);
    }

    public MethodDeclaration getTracedMethod() {
        return tracedMethod;
    }

    private void setJmlPrinter(CompilationUnit compilationUnit) {
        compilationUnit.printer(new JmlPrinter());
    }

    private class KeyAnnotaterVisitor extends VoidVisitorAdapter<Void> {

        @Override
        public void visit(MethodDeclaration md, Void arg) {
            super.visit(md, arg);

            if (!isMainMethod(md)) {
                return;
            }

            tracedMethod = md;

            JmlJavadocCommentBuilder builder = new JmlJavadocCommentBuilder();
            builder.setIsNormalBehaviour(true);

            builder.addAssignable("\\everything");

            md.setJavadocComment(builder.build());
        }
    }

}
