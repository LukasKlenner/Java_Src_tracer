package srctracer;

import srctracer.trace.TextTraceConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static srctracer.trace.TextTraceConstants.*;

public final class Trace {

    private static OutputStreamWriter out;
    private static boolean breakBefore = false;

    private static final String OUT_DIR = "trace-out/";

    private static void write(String s) {
        try {
            if (out != null) out.write(s);
        } catch (Exception e) {
            // swallow — tracing must never crash the host program
        }
    }

    public static void _FUNC(int id) { write(TEXT_CALL + Integer.toHexString(id)); }
    public static void _IF()         { write(TEXT_IF); }
    public static void _ELSE()       { write(TEXT_ELSE); }
    public static void _LOOP_BODY()  { if (!breakBefore) write(TEXT_IF); breakBefore = false; }
    public static void _LOOP_END()   { if (!breakBefore) write(TEXT_ELSE); breakBefore = false; }
    public static void _BREAK()      { breakBefore = true; }
    public static void _RETURN()     { write(TEXT_RETURN); }

    /**
     * Records which case of a switch was taken, as a fixed 6-bit value (MSB-first)
     * written as 'I'/'O' chars. Supports up to 64 cases per switch (supervisor constraint).
     */
    public static void _CASE(int caseId) {
        for (int i = 5; i >= 0; i--) {
            write(((caseId >> i) & 1) == 1 ? "I" : "O");
        }
    }

    public static void _TRY()             { write(TEXT_TRY); }
    public static void _TRY_END()         { write(TEXT_TRY_END); }
    public static void _CATCH(int idx)    { write(TEXT_CATCH + Integer.toHexString(idx)); }

    public static void trace_start(String programName) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.nnnnnnnnn");
        String fileName = programName + "_" + now.format(fmt) + ".trace.txt";

        try {
            new File(OUT_DIR).mkdirs();
            out = new OutputStreamWriter(
                    new FileOutputStream(OUT_DIR + fileName),
                    StandardCharsets.UTF_8);
            System.out.println("Trace to: " + OUT_DIR + fileName);
        } catch (Exception e) {
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(Trace::trace_end));
    }

    public static void trace_end() {
        write("E");
        try {
            if (out != null) {
                out.close();
                out = null;
            }
        } catch (Exception e) {
            // swallow
        }
    }
}
