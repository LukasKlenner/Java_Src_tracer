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

    private final Trace trace;

    private final FunctionDatabaseReader functionDatabaseReader;

    private MethodDeclaration tracedMethod;

    public KeySourceTransformer(Path traceFile, FunctionDatabaseReader functionDatabaseReader) throws IOException {
        this.trace = Trace.parseTrace(traceFile);
        this.functionDatabaseReader = functionDatabaseReader;
    }

    public KeySourceTransformer(String traceContent, FunctionDatabaseReader functionDatabaseReader) {
        this.trace = Trace.parseTraceFromString(traceContent);
        this.functionDatabaseReader = functionDatabaseReader;
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

            builder.addRequires("tracer.Trace.index == 0");
            TraceElement[] elementsForRequired = Arrays.stream(trace.getElements())
                    .filter(TraceElement::createsRequiresString)
                    .filter(traceElement -> !(traceElement instanceof TraceElement.Call(int functionId) && functionDatabaseReader.isMainFunction(functionId)))
                    .toArray(TraceElement[]::new);

            for (int i = 0; i < elementsForRequired.length; i++) {
                builder.addRequires(elementsForRequired[i].asRequiresString(i, functionDatabaseReader));
            }


            builder.addEnsures("tracer.Trace.index == " + elementsForRequired.length);

            builder.addAssignable("\\everything");

            md.setJavadocComment(builder.build());
        }
    }

}
