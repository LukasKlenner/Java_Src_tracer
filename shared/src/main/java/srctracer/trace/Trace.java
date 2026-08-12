package srctracer.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static srctracer.trace.TextTraceConstants.TEXT_CALL;
import static srctracer.trace.TextTraceConstants.TEXT_CATCH;
import static srctracer.trace.TextTraceConstants.TEXT_ELSE;
import static srctracer.trace.TextTraceConstants.TEXT_END;
import static srctracer.trace.TextTraceConstants.TEXT_IF;
import static srctracer.trace.TextTraceConstants.TEXT_RETURN;
import static srctracer.trace.TextTraceConstants.TEXT_TRY;
import static srctracer.trace.TextTraceConstants.TEXT_TRY_END;

public class Trace {

    private final TraceElement[] elements;

    public Trace(TraceElement[] elements) {
        this.elements = elements;
    }

    public TraceElement[] getElements() {
        return elements;
    }

    public static Trace parseTrace(Path traceFile) throws IOException {
        String text = Files.readString(traceFile);
        return parseTraceFromString(text);
    }

    public static Trace parseTraceFromString(String text) {
        StringReader reader = new StringReader(text);
        List<TraceElement> elements = new ArrayList<>();

        while (reader.hasNext()) {
            char c = reader.read();
            switch (c) {
                case TEXT_CALL -> {
                    int id = readHexNumber(reader);
                    elements.add(new TraceElement.Call(id));
                }
                case TEXT_CATCH -> {
                    int id = readHexNumber(reader);
                    elements.add(new TraceElement.Catch(id));
                }
                case TEXT_IF -> elements.add(new TraceElement.If());
                case TEXT_ELSE -> elements.add(new TraceElement.Else());
                case TEXT_RETURN -> elements.add(new TraceElement.Return());
                case TEXT_TRY -> elements.add(new TraceElement.Try());
                case TEXT_TRY_END -> elements.add(new TraceElement.TryEnd());
                case TEXT_END -> elements.add(new TraceElement.END());
                default -> throw new IllegalArgumentException("Unexpected character in trace file: " + c);
            }
        }

        return new Trace(elements.toArray(new TraceElement[0]));
    }

    private static int readHexNumber(StringReader reader) {

        StringBuilder sb = new StringBuilder();
        while (reader.hasNext() && isHexDigit(reader.string.charAt(reader.index))) {
            sb.append(reader.read());
        }
        return Integer.parseInt(sb.toString(), 16);

    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static class StringReader {

        private final String string;

        private int index = 0;

        private StringReader(String string) {
            this.string = string;
        }

        public char read() {
            return string.charAt(index++);
        }

        public boolean hasNext() {
            return index < string.length();
        }

    }
}
