package srctracer.instrumenter.validation;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeArguments;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.WildcardType;

import java.util.concurrent.atomic.AtomicBoolean;

class SupportedFeatures {

    static final Feature[] SUPPORTED_FEATURES = {

            // Declarations
            new Feature(ClassOrInterfaceDeclaration.class),
            new Feature(RecordDeclaration.class),
            new Feature(CallableDeclaration.class),
            new Feature(FieldDeclaration.class),
            new Feature(VariableDeclarator.class),
            new Feature(InitializerDeclaration.class),
            new Feature("Non-Static imports", ImportDeclaration.class, i -> !i.isStatic()),

            // Standard nodes
            new Feature(CompilationUnit.class),
            new Feature(SimpleName.class),
            new Feature(Name.class),
            new Feature(BlockStmt.class),
            new Feature(Modifier.class),
            new Feature(Parameter.class),
            new Feature(Comment.class),

            // Method calls
            new Feature(MethodCallExpr.class),
            new Feature(ReturnStmt.class),

            // Loops
            new Feature(ForStmt.class),
            new Feature(WhileStmt.class),
            new Feature(DoStmt.class),
            new Feature(ForEachStmt.class),
            new Feature(BreakStmt.class),
            new Feature(ContinueStmt.class),

            // conditional statements
            new Feature(IfStmt.class),
            new Feature(SwitchStmt.class),
            new Feature("standard switch entry", SwitchEntry.class, e ->
                    e.getGuard().map(g -> g instanceof NullLiteralExpr).orElse(true) &&
                            e.getType() == SwitchEntry.Type.STATEMENT_GROUP
            ),

            // Exception handling
            new Feature(TryStmt.class),
            new Feature(CatchClause.class),
            new Feature(ThrowStmt.class),

            // normal expressions
            new Feature(ExpressionStmt.class),
            new Feature(NameExpr.class),
            new Feature(EnclosedExpr.class),
            new Feature(UnaryExpr.class),
            new Feature(BinaryExpr.class),
            new Feature(VariableDeclarationExpr.class),
            new Feature(LiteralExpr.class),
            new Feature(ThisExpr.class),
            new Feature(SuperExpr.class),
            new Feature(ObjectCreationExpr.class),
            new Feature(AssignExpr.class),
            new Feature(CastExpr.class),
            new Feature(InstanceOfExpr.class),
            new Feature(FieldAccessExpr.class),

            // array expressions
            new Feature(ArrayCreationExpr.class),
            new Feature(ArrayInitializerExpr.class),
            new Feature(ArrayAccessExpr.class),
            new Feature(ArrayCreationLevel.class),

            // other
            new Feature(ExplicitConstructorInvocationStmt.class),

            // conditional expressions with assignments would break definitive assignment guarantees
            new Feature("Conditional expression without assignments", ConditionalExpr.class, n -> {
                AtomicBoolean containsAssign = new AtomicBoolean(false);
                n.walk(AssignExpr.class, a -> containsAssign.set(true));
                return !containsAssign.get();
            }),

            // disallow generic and other complex types
            new Feature("Types", Type.class, t ->
                    !isGeneric(t) && !t.isVarType() && !t.isIntersectionType() &&  !t.isUnionType()),
    };

    private SupportedFeatures() {
    }

    private static boolean isGeneric(Type t) {
        // <T extends X> on classes, records, methods and constructors; ?, ? extends X, ? super X
        if (t instanceof TypeParameter || t instanceof WildcardType)
            return true;
        // List<String>, and the diamond in new ArrayList<>() (present but empty list)
        if (t instanceof ClassOrInterfaceType c && c.getTypeArguments().isPresent())
            return true;
        // explicit type arguments: obj.<String>foo(), new <T>Foo(), this.<T>super(...)
        return t.getParentNode()
                .filter(p -> p instanceof NodeWithTypeArguments<?> w
                        && w.getTypeArguments()
                        .map(args -> args.stream().anyMatch(a -> a == t))
                        .orElse(false))
                .isPresent();
    }

}
