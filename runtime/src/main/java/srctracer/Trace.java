package srctracer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Trace {

    private static Writer out;
    private static boolean breakBefore = false;

    public static long totalCatchCount = 0;

    // Invocations of the start (lifecycle) method that have not returned yet. Only the first invocation is
    // recorded: its recursive calls do not end the trace, and later invocations record nothing.
    private static int lifecycleDepth = 0;
    private static boolean lifecycleEntered = false;
    // whether the recorded invocation of the start method threw (then it has no U)
    private static boolean lifecycleThrew = false;
    // output that recording starts with once the start method is entered (see trace_start_on_entry)
    private static Writer pendingOut;
    // events recorded since recording started (the end marker E is not counted)
    private static long eventCount = 0;

    private static final String OUT_DIR = "trace-out/";

    /** Writes one event. */
    private static void write(String s) {
        if (out != null) eventCount++;
        writeRaw(s);
    }

    private static void writeRaw(String s) {
        try {
            if (out != null) out.write(s);
        } catch (Exception e) {
            // swallow — tracing must never crash the host program
        }
    }

    public static void _FUNC(int id) { write("C" + Integer.toHexString(id)); }
    public static void _IF()         { write("I"); }
    public static void _ELSE()       { write("O"); }
    public static void _LOOP_BODY()  { if (!breakBefore) _IF(); breakBefore = false; }
    public static void _LOOP_END()   { if (!breakBefore) _ELSE(); breakBefore = false; }
    public static void _BREAK()      { breakBefore = true; }
    public static void _RETURN()     { write("R"); }

    /**
     * Records which case of a switch was taken, as a fixed 6-bit value (MSB-first)
     * written as 'I'/'O' chars. Supports up to 64 cases per switch (supervisor constraint).
     */
    public static void _CASE(int caseId) {
        StringBuilder bits = new StringBuilder(6);
        for (int i = 5; i >= 0; i--) {
            bits.append(((caseId >> i) & 1) == 1 ? 'I' : 'O');
        }
        write(bits.toString());
    }

    public static void _IMPLICIT_EXCEPTION()    { _IF(); }
    public static void _NO_IMPLICIT_EXCEPTION() { _ELSE(); }

    public static boolean _COND(boolean condition) {
        if (condition) {
            _IF();
        } else {
            _ELSE();
        }
        return condition;
    }

    public static int _DIM(int dimension) {
        if (dimension < 0) {
            _IF();
            throw new NegativeArraySizeException("Negative array size: " + dimension);
        } else {
            write("D" + Integer.toHexString(dimension));
        }
        return dimension;
    }

    public static int _LAST_DIM(int dimension) {
        if (dimension < 0) {
            _IF();
            throw new NegativeArraySizeException("Negative array size: " + dimension);
        } else {
            _ELSE();
        }
        return dimension;
    }

    public static void _TRY() {
        write("T");
    }

    public static void _TRY_END() {
        write("U");
    }

    public static void _CATCH(long catchIndex) {
        write("J" + Long.toHexString(catchIndex));
    }

    // try/catch the instrumenter wraps around the start method; only recorded for its outermost invocation.
    // _LIFECYCLE_TRY_END is called in the finally block, so it is skipped if the method threw.
    public static void _LIFECYCLE_TRY()     { if (lifecycleDepth == 1) _TRY(); }
    public static void _LIFECYCLE_TRY_END() { if (lifecycleDepth == 1 && !lifecycleThrew) _TRY_END(); }
    public static void _LIFECYCLE_CATCH(long catchIndex) {
        if (lifecycleDepth == 1) {
            lifecycleThrew = true;
            _CATCH(catchIndex);
        }
    }

    public static void trace_start(Writer writer) {
        out = writer;
        eventCount = 0;
    }

    /**
     * Records into {@code writer}, but only from the first entry of the start method on: code that runs before
     * (e.g. an uninstrumented fuzz harness calling other instrumented methods first) is not recorded.
     */
    public static void trace_start_on_entry(Writer writer) {
        pendingOut = writer;
    }

    /** Called on entry of the start method. */
    public static void trace_start(String programName) {
        lifecycleDepth++;
        if (lifecycleDepth > 1 || lifecycleEntered) return;
        lifecycleEntered = true;
        if (pendingOut != null) {
            // forget the state left by instrumented code that ran before (it was not recorded)
            breakBefore = false;
            totalCatchCount = 0;
            eventCount = 0;
            out = pendingOut;
            pendingOut = null;
            return;
        }
        if (out != null) return;
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

    /** Called when the start method returns (and by the tool / shutdown hook after the run). */
    public static void trace_end() {
        if (lifecycleDepth > 0 && --lifecycleDepth > 0) return; // return of a recursive call
        writeRaw("E");
        try {
            if (out != null) {
                out.close();
                out = null;
            }
        } catch (Exception e) {
            // swallow
        }
    }

    /**
     * Number of events recorded so far: branch decisions, calls, returns, try/try-end/catch markers, switch cases
     * and array dimensions (a switch case counts once). The same count as the binary runtime.
     */
    public static long trace_event_count() {
        return eventCount;
    }

    /** Whether the start method was entered, i.e. whether a trace was recorded. */
    public static boolean trace_recorded() {
        return lifecycleEntered;
    }
}
