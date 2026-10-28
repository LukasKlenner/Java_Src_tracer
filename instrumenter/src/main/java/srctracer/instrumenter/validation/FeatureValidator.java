package srctracer.instrumenter.validation;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.validator.ProblemReporter;
import com.github.javaparser.ast.validator.Validator;

import static srctracer.instrumenter.validation.SupportedFeatures.SUPPORTED_FEATURES;

public class FeatureValidator implements Validator {


    @Override
    public void accept(Node root, ProblemReporter problemReporter) {
        root.walk(node -> {
            boolean handled = false;
            for (Feature f : SUPPORTED_FEATURES) {
                if (f.handlesNode(node)) {
                    if (!f.matches(node)) {
                        problemReporter.report(node, "The feature '" + f.description + "' handles the given node type, but the node does not match the feature's criteria.");
                    }
                    handled = true;
                }
            }
            if (!handled) {
                problemReporter.report(node, "Node type not supported: " + node.getClass().getSimpleName());
            }
        });
    }
}
