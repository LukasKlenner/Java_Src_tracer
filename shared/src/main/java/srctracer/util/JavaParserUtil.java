package srctracer.util;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedType;
import srctracer.trace.TracerField;
import srctracer.trace.TracerMethod;

import java.util.Optional;
import java.util.stream.Collectors;

public class JavaParserUtil {

    public static boolean switchNeedsNullCheck(SwitchStmt switchStmt) {
        Expression selector = switchStmt.getSelector();
        ResolvedType resolvedType = selector.calculateResolvedType();
        if (resolvedType.isPrimitive()) {
            return false; // Primitive types cannot be null
        }
        // For reference types, we need to check if any case is a null literal
        boolean hasNullCase = switchStmt.getEntries().stream()
                .flatMap(entry -> entry.getLabels().stream())
                .anyMatch(Expression::isNullLiteralExpr);

        if (hasNullCase) {
            throw new IllegalArgumentException("Switch statement with a null case label is not supported.");
        }

        return true; // Reference type without null case needs a null check
    }

    public static boolean isMainMethod(MethodDeclaration md) {
        if (!md.getNameAsString().equals("main")) return false;
        if (!md.isStatic()) return false;
        if (!md.getType().toString().equals("void")) return false;
        if (md.getParameters().size() != 1) return false;
        String pt = md.getParameter(0).getType().toString();
        return pt.equals("String[]") || pt.equals("java.lang.String[]");
    }

    public static String getQualifiedClassName(MethodDeclaration method) {
        TypeDeclaration<?> ancestor = (TypeDeclaration<?>) method.findAncestor(TypeDeclaration.class)
                .orElseThrow(() -> new RuntimeException("Cannot determine class name"));
        return ancestor.getFullyQualifiedName()
                .orElseThrow(() -> new RuntimeException("Cannot determine qualified name for: " + ancestor.getNameAsString()));
    }

    public static String getParamDescriptor(MethodDeclaration method) {
        return method.getParameters().stream()
                .map(param -> typeToDescriptor(param.getType()))
                .collect(Collectors.joining(","));
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
            if (arrayDimensions == 0) {
                baseType = type.toString();
            } else {
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
            }
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
        return parseStatement(method.getMethodCallString(args) + ";");
    }

    public static Statement parseTracerFieldStore(TracerField field, Object value) {
        return parseStatement(field.getFieldAccessString() + " = " + value + ";");
    }

    public static Statement parseTracerFieldAdd(TracerField field, Object amount) {
        return parseStatement(field.getFieldAccessString() + " += " + amount + ";");
    }

    public static Statement parseStatement(String code) {
        return StaticJavaParser.parseStatement(code);
    }

    public static Expression wrapInSwitchExpression(Expression expression) {
        return wrapInSwitchExpression(new NodeList<>(), expression);
    }

    public static Expression wrapInSwitchExpression(NodeList<Statement> stmts, Expression yieldExpr) {
        NodeList<Statement> body = new NodeList<>(stmts);
        body.add(new YieldStmt(yieldExpr));
        return new SwitchExpr(
                new IntegerLiteralExpr("0"),
                new NodeList<>(
                        new SwitchEntry(
                                new NodeList<>(),
                                SwitchEntry.Type.BLOCK,
                                new NodeList<>(new BlockStmt(body)),
                                true,
                                null
                        )
                )
        );
    }

    public static void insertBefore(Node n, Statement newStmt) {
        Node parent = n.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            int idx = block.getStatements().indexOf(n);
            if (idx >= 0) block.addStatement(idx, newStmt);
        } else if (parent instanceof SwitchEntry entry) {
            int idx = entry.getStatements().indexOf(n);
            if (idx >= 0) entry.getStatements().add(idx, newStmt);
        } else {
            throw new IllegalArgumentException("Node is not inside a BlockStmt or SwitchEntry");
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
        } else {
            throw new IllegalArgumentException("Node is not inside a BlockStmt or SwitchEntry");
        }
    }

    public static boolean isSimpleExpression(Expression expression) {

        if (expression instanceof EnclosedExpr enclosedExpr) {
            return isSimpleExpression(enclosedExpr.getInner());
        }

        if (expression instanceof UnaryExpr u
                && (u.getOperator() == UnaryExpr.Operator.MINUS
                || u.getOperator() == UnaryExpr.Operator.PLUS)
                && u.getExpression() instanceof LiteralExpr) {
            return true;
        }

        return expression instanceof NameExpr
                || expression instanceof ThisExpr
                || expression instanceof SuperExpr
                || expression instanceof LiteralExpr;
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

    public static boolean isNarrowPrimitive(ResolvedType t) {
        if (!t.isPrimitive()) return false;
        return switch (t.asPrimitive()) {
            case BYTE, SHORT, CHAR -> true;
            default -> false;
        };
    }

    public static boolean isNarrowPrimitive(Type t) {
        if (!(t instanceof PrimitiveType pt)) return false;
        return switch (pt.getType()) {
            case BYTE, SHORT, CHAR -> true;
            default -> false;
        };
    }

    public static Node unwrapEnclosed(Node n) {
        while (n instanceof EnclosedExpr e) n = e.getParentNode().orElse(null);
        return n;
    }

    public static Type resolvedToAstType(ResolvedType t) {
        if (t.isPrimitive()) {
            return switch (t.asPrimitive()) {
                case BYTE -> PrimitiveType.byteType();
                case SHORT -> PrimitiveType.shortType();
                case CHAR -> PrimitiveType.charType();
                case INT -> PrimitiveType.intType();
                case LONG -> PrimitiveType.longType();
                case FLOAT -> PrimitiveType.floatType();
                case DOUBLE -> PrimitiveType.doubleType();
                case BOOLEAN -> PrimitiveType.booleanType();
            };
        }

        if (t.isArray()) {
            return new ArrayType(resolvedToAstType(t.asArrayType().getComponentType()));
        }
        if (t.isReferenceType()) {
            return new ClassOrInterfaceType(null, t.asReferenceType().getQualifiedName());
        }
        throw new UnsupportedOperationException("Cannot convert type: " + t);
    }

    /** Best-effort check: does control flow always leave {@code s} via return/throw? */
    public static boolean alwaysExits(Statement s) {
        return alwaysExitsImpl(s, false);
    }

    /** Like {@link #alwaysExits} but also treats {@link BreakStmt} as an exit (loop context). */
    public static boolean alwaysExitsLoop(Statement s) {
        return alwaysExitsImpl(s, true);
    }

    private static boolean alwaysExitsImpl(Statement s, boolean breakExits) {
        if (s instanceof ReturnStmt) return true;
        if (s instanceof ThrowStmt) return true;
        if (breakExits && s instanceof BreakStmt) return true;
        if (s instanceof BlockStmt b) {
            if (b.getStatements().isEmpty()) return false;
            return alwaysExitsImpl(b.getStatement(b.getStatements().size() - 1), breakExits);
        }
        if (s instanceof IfStmt i) {
            return i.getElseStmt().isPresent()
                    && alwaysExitsImpl(i.getThenStmt(), breakExits)
                    && alwaysExitsImpl(i.getElseStmt().get(), breakExits);
        }
        if (s instanceof SwitchStmt sw) {
            NodeList<SwitchEntry> entries = sw.getEntries();
            if (entries.stream().noneMatch(e -> e.getLabels().isEmpty())) return false; // no default

            boolean currentGroupExits = false;
            for (int i = entries.size() - 1; i >= 0; i--) {
                NodeList<Statement> stmts = entries.get(i).getStatements();
                if (!stmts.isEmpty()) {
                    currentGroupExits = alwaysExitsImpl(stmts.getLast().get(), breakExits);
                }
                if (!currentGroupExits) return false;
            }
            return true;
        }
        return false;
    }
}
