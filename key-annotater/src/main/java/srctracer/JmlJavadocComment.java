package srctracer;

import com.github.javaparser.ast.comments.JavadocComment;

public class JmlJavadocComment extends JavadocComment {

    public final boolean isNormalBehaviour;

    public JmlJavadocComment(boolean isNormalBehaviour, String content) {
        super(content);
        this.isNormalBehaviour = isNormalBehaviour;
    }

    @Override
    public String getHeader() {

        String behaviour = isNormalBehaviour ? "normal_behaviour" : "exceptional_behaviour";

        return "/*@ " + behaviour;
    }

    @Override
    public String getFooter() {
        return "@*/";
    }

    public static JmlJavadocCommentBuilder builder() {
        return new JmlJavadocCommentBuilder();
    }
}
