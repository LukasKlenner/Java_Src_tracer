package srctracer.util;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ArrayType;

import java.util.stream.Collectors;

public class JavaParserUtil {

    public static boolean isMainMethod(MethodDeclaration md) {
        if (!md.getNameAsString().equals("main")) return false;
        if (!md.isStatic()) return false;
        if (!md.getType().toString().equals("void")) return false;
        if (md.getParameters().size() != 1) return false;
        String pt = md.getParameter(0).getType().toString();
        return pt.equals("String[]") || pt.equals("java.lang.String[]");
    }

    public static String getQualifiedClassName(MethodDeclaration method) {
        TypeDeclaration<?> ancestor = (TypeDeclaration<?>) method.findAncestor(TypeDeclaration.class).orElseThrow(() -> new RuntimeException("Cannot determine class name"));
        return ancestor.getFullyQualifiedName().get();
    }

    public static String getParamDescriptor(MethodDeclaration method) {
        return method.getParameters().stream()
                .map(param -> typeToDescriptor(param.getType()))
                .collect(Collectors.joining(";"));
    }

    public static String typeToDescriptor(com.github.javaparser.ast.type.Type type) {
        String baseType;
        int arrayDimensions = 0;

        if (type.isArrayType()) {
            ArrayType arrayType = type.asArrayType();
            arrayDimensions = arrayType.getArrayLevel();
            type = arrayType.getComponentType();
            // getComponentType strips ALL dimensions, so arrayLevel is correct
        }

        if (type.isPrimitiveType()) {
            baseType = switch (type.asPrimitiveType().getType()) {
                case INT -> "I";
                case BOOLEAN -> "Z";
                case BYTE -> "B";
                case CHAR -> "C";
                case DOUBLE -> "D";
                case FLOAT -> "F";
                case LONG -> "J";
                case SHORT -> "S";
            };
        } else {
            // Reference type — KeY uses dots, not slashes
            String name = type.asString();
            // Resolve common unqualified names
            if (name.equals("String")) name = "java.lang.String";
            if (name.equals("Object")) name = "java.lang.Object";
            baseType = "L" + name;
        }

        return "[".repeat(arrayDimensions) + baseType;
    }
}
