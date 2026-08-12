package srctracer.printer;

import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.SourcePrinter;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.PrinterConfiguration;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class JmlPrinter extends DefaultPrettyPrinter {

    public JmlPrinter() {
        super(config -> new JmlPrinterVisitor(config, createSourcePrinter(config)),
                new DefaultPrinterConfiguration());
    }

    private static SourcePrinter createSourcePrinter(PrinterConfiguration configuration) {
        Constructor<SourcePrinter> constructor = null;
        try {
            constructor = SourcePrinter.class.getDeclaredConstructor(PrinterConfiguration.class);
            constructor.setAccessible(true);
            return constructor.newInstance(configuration);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

}
