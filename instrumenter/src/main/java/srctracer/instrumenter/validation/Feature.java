package srctracer.instrumenter.validation;

import com.github.javaparser.ast.Node;

import java.util.function.Predicate;

public class Feature {

    final String description;
    final Class<? extends Node> nodeType;
    final Predicate<Node> matches;

    <N extends Node> Feature(String description, Class<N> type, Predicate<N> matches) {
        this.description = description;
        this.nodeType = type;
        this.matches = n -> type.isInstance(n) && matches.test(type.cast(n));
    }

    Feature(Class<? extends Node> type) { this(type.getSimpleName(), type, n -> true); }

    boolean handlesNode(Node n) { return nodeType.isInstance(n); }

    boolean matches(Node n) { return matches.test(n); }
}