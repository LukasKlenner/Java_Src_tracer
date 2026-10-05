package srctracer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaParserAdapter;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public abstract class SourceTransformer {

    static {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(Config.DEFAULT_SOURCE_LANGUAGE_LEVEL);
    }

    private final JavaParserAdapter javaParser;

    public SourceTransformer(JavaParser javaParser) {
        this.javaParser = JavaParserAdapter.of(javaParser);
    }

    public SourceTransformer() {
        this(new JavaParser(StaticJavaParser.getParserConfiguration()));
    }

    public void transform(Path input, Path output) throws IOException {
        String result = transformToString(input);
        Files.writeString(output, result);
    }

    public String transformToString(Path input) throws IOException {
        CompilationUnit cu = javaParser.parse(input);

        performTransformation(cu);
        return cu.toString();
    }

    protected abstract void performTransformation(CompilationUnit compilationUnit);

}
