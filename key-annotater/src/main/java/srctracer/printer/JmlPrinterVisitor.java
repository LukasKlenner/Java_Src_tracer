package srctracer.printer;

import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.printer.DefaultPrettyPrinterVisitor;
import com.github.javaparser.printer.SourcePrinter;
import com.github.javaparser.printer.configuration.PrinterConfiguration;
import com.github.javaparser.utils.LineSeparator;
import srctracer.JmlJavadocComment;

import java.util.ArrayList;
import java.util.List;

import static com.github.javaparser.utils.Utils.normalizeEolInTextBlock;
import static com.github.javaparser.utils.Utils.trimTrailingSpaces;

public class JmlPrinterVisitor extends DefaultPrettyPrinterVisitor {

    public JmlPrinterVisitor(PrinterConfiguration configuration, SourcePrinter printer) {
        super(configuration, printer);
    }

    @Override
    public void visit(JavadocComment n, Void arg) {
        if (n instanceof JmlJavadocComment) {
            printJmlJavadocComment((JmlJavadocComment) n);
        } else {
            super.visit(n, arg);
        }
    }

    private void printJmlJavadocComment(JmlJavadocComment n) {
        // TODO add?
        // printOrphanCommentsBeforeThisChildNode(n);
        printer.println(n.getHeader());
        final String commentContent = normalizeEolInTextBlock(
                n.getContent(),
                LineSeparator.SYSTEM.asRawString());
        String[] lines = commentContent.split("\\R");
        List<String> strippedLines = new ArrayList<>();
        for (String line : lines) {
            final String trimmedLine = line.trim();
            if (trimmedLine.startsWith("@")) {
                line = trimmedLine.substring(1);
            }
            line = trimTrailingSpaces(line);
            strippedLines.add(line);
        }
        boolean skippingLeadingEmptyLines = true;
        boolean prependEmptyLine = false;
        boolean prependSpace = strippedLines.stream().anyMatch(line -> !line.isEmpty() && !line.startsWith(" "));
        for (String line : strippedLines) {
            if (line.isEmpty()) {
                if (!skippingLeadingEmptyLines) {
                    prependEmptyLine = true;
                }
            } else {
                skippingLeadingEmptyLines = false;
                if (prependEmptyLine) {
                    printer.println("  @");
                    prependEmptyLine = false;
                }
                printer.print("  @");
                if (prependSpace) {
                    printer.print(" ");
                }
                printer.println(line);
            }
        }
        printer.println("  " + n.getFooter());

    }

}
