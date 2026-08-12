package srctracer;

public class JmlJavadocCommentBuilder {

    private final StringBuilder contentBuilder = new StringBuilder();

    private boolean isNormalBehaviour = true;

    public JmlJavadocCommentBuilder setIsNormalBehaviour(boolean isNormalBehaviour) {
        this.isNormalBehaviour = isNormalBehaviour;
        return this;
    }

    public JmlJavadocCommentBuilder addRequires(String predicate) {
        if (predicate.isBlank()) {
            return this;
        }
        contentBuilder.append("requires ").append(predicate).append(";\n");
        return this;
    }

    public JmlJavadocCommentBuilder addEnsures(String predicate) {
        if (predicate.isBlank()) {
            return this;
        }
        contentBuilder.append("ensures ").append(predicate).append(";\n");
        return this;
    }

    public JmlJavadocCommentBuilder addAssignable(String assignable) {
        if (assignable.isBlank()) {
            return this;
        }
        contentBuilder.append("assignable ").append(assignable).append(";\n");
        return this;
    }

    public JmlJavadocComment build() {
        return new JmlJavadocComment(isNormalBehaviour, contentBuilder.toString());
    }
}
