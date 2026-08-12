package srctracer.util;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.Type;

import java.util.List;
import java.util.stream.Collectors;

public record FunctionSignature(
        String signatureString
) {

    public FunctionSignature(
            TypeDeclaration<?> declaringType,
            String methodName,
            List<Parameter> parameters,
            Type returnType
    ) {
        this(String.format(
                "%s#%s(%s):%s",
                declaringType.getFullyQualifiedName().get(),
                methodName,
                parameters.stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(",")),
                returnType.asString()
        ));
    }

    @Override
    public String toString() {
        return signatureString;
    }

}
