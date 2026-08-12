package srctracer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public abstract class SourceTransformer {

    public void transform(Path input, Path output) throws IOException {
        String result = transformToString(input);
        Files.createDirectories(output.getParent());
        Files.writeString(output, result);
    }

    public String transformToString(Path input) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(input);

        performTransformation(cu);
        System.out.println("Transformed " + input);
        return cu.toString();
    }

    protected abstract void performTransformation(CompilationUnit compilationUnit);

}
