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

    private final String targetMethodName;
    private MethodDeclaration tracedMethod;

    public KeySourceTransformer() {
        this(null);
    }

    public KeySourceTransformer(String targetMethodName) {
        this.targetMethodName = targetMethodName;
    }

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

    private boolean isTargetMethod(MethodDeclaration md) {
        if (targetMethodName != null) {
            return md.getNameAsString().equals(targetMethodName);
        }
        return isMainMethod(md);
    }

    private class KeyAnnotaterVisitor extends VoidVisitorAdapter<Void> {

        @Override
        public void visit(MethodDeclaration md, Void arg) {
            super.visit(md, arg);

            if (!isTargetMethod(md)) {
                return;
            }

            tracedMethod = md;

            JmlJavadocCommentBuilder builder = new JmlJavadocCommentBuilder();
            builder.setIsNormalBehaviour(true);

            builder.addAssignable("\\everything");


//            builder.addEnsures("(\\forall int j; 0<=j && j < array.length;" +
//                    "             (\\num_of int i; 0<=i && i < array.length; \\old(array[i]) == array[j])" +
//                    "          == (\\num_of int i; 0<=i && i < array.length;      array[i]  == array[j]))");
//
//            builder.addEnsures("(\\forall int i; 0<=i && i<array.length-1; array[i] <= array[i+1])");

            md.setJavadocComment(builder.build());
        }
    }

}
