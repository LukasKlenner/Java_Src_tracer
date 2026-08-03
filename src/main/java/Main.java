import srctracer.KeyAnnotator;
import srctracer.instrumenter.Instrumenter;

import java.nio.file.Path;

public class Main {

    private static final KeyAnnotator annotator = new KeyAnnotator();
    private static final Instrumenter instrumenter = new Instrumenter();

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: instrumenter <input.java> <output.java>");
            System.exit(1);
        }

        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);


    }
}
