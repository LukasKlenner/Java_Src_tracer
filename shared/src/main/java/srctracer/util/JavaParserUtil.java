package srctracer.util;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.Type;
import srctracer.trace.TracerMethod;

import java.util.Optional;
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

    public static String typeToDescriptor(Type type) {
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

    public static Statement parseTracerCall(TracerMethod method, Object... args) {
        return parseStatement(method.getMethodCallString(args));
    }

    public static Statement parseStatement(String code) {
        return StaticJavaParser.parseStatement(code);
    }

    public static void insertBefore(Node n, Statement newStmt) {
        Node parent = n.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            int idx = block.getStatements().indexOf(n);
            if (idx >= 0) block.addStatement(idx, newStmt);
        } else if (parent instanceof SwitchEntry entry) {
            int idx = entry.getStatements().indexOf(n);
            if (idx >= 0) entry.getStatements().add(idx, newStmt);
        }
    }

    public static void insertAfter(Node n, Statement newStmt) {
        Node parent = n.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            int idx = block.getStatements().indexOf(n);
            if (idx >= 0) block.addStatement(idx + 1, newStmt);
        } else if (parent instanceof SwitchEntry entry) {
            int idx = entry.getStatements().indexOf(n);
            if (idx >= 0) entry.getStatements().add(idx + 1, newStmt);
        }
    }

    public static boolean isInsideLambda(Node n) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof LambdaExpr) return true;
            if (cur instanceof MethodDeclaration) return false;
            if (cur instanceof ConstructorDeclaration) return false;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    public static boolean isBreakForSwitch(BreakStmt n) {
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

    public static Optional<Type> findEnclosingReturnType(Node n) {
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
