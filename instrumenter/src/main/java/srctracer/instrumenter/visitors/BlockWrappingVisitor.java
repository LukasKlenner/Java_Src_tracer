package srctracer.instrumenter.visitors;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

/**
 * Ensures every if-branch, loop body etc. is a BlockStmt rather than a bare statement.
 */
public class BlockWrappingVisitor extends ModifierVisitor<Void> {

    @Override
    public Visitable visit(IfStmt n, Void a) {
        super.visit(n, a);
        n.setThenStmt(ensureBlock(n.getThenStmt()));
        n.getElseStmt().ifPresent(e -> n.setElseStmt(ensureBlock(e)));
        return n;
    }

    @Override
    public Visitable visit(WhileStmt n, Void a) {
        super.visit(n, a);
        n.setBody(ensureBlock(n.getBody()));
        return n;
    }

    @Override
    public Visitable visit(DoStmt n, Void a) {
        super.visit(n, a);
        n.setBody(ensureBlock(n.getBody()));
        return n;
    }

    @Override
    public Visitable visit(ForStmt n, Void a) {
        super.visit(n, a);
        n.setBody(ensureBlock(n.getBody()));
        return n;
    }

    @Override
    public Visitable visit(ForEachStmt n, Void a) {
        super.visit(n, a);
        n.setBody(ensureBlock(n.getBody()));
        return n;
    }

    private static BlockStmt ensureBlock(Statement s) {
        if (s instanceof BlockStmt b) return b;
        BlockStmt block = new BlockStmt();
        block.addStatement(s);
        return block;
    }
}
