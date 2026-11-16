package srctracer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Binary-mode trace runtime.  Same public API as the text-mode {@code srctracer.Trace};
 * differs only in what bytes are written.  Picked by which jar is on the classpath.
 *
 * <p>Format mirrors {@code src-tracer/include/src_tracer/trace_mode.h} and
 * {@code trace_elem.h}.
 */
public final class Trace {

    // Byte-level constants from trace_elem.h
    private static final int FUNC_4    = 0x00;
    private static final int FUNC_12   = 0x10;
    private static final int FUNC_20   = 0x20;
    private static final int FUNC_28   = 0x30;
    private static final int FUNC_32   = 0x43; // 'C'
    private static final int FUNC_ANON = 0x41; // 'A'
    private static final int END       = 0x45; // 'E'
    private static final int RETURN    = 0x52; // 'R'
    private static final int TRY       = 0x54; // 'T'
    private static final int CATCH_T   = 0x68; // ELEM2 CATCH
    private static final int DATA      = 0x70; // ELEM2 DATA
    private static final int LEN_0     = 0x00;
    private static final int LEN_8     = 0x01;
    private static final int LEN_16    = 0x02;
    private static final int LEN_32    = 0x03;
    private static final int IE_INIT   = 0xFE;

    private static final int IE_FLUSH_THRESHOLD = 0xC0;

    private static final int BUF_SIZE = 4096;
    private static final String OUT_DIR = "trace-out/";

    private static final byte[] buf = new byte[BUF_SIZE];
    private static int bufPos = 0;
    private static int ieByte = IE_INIT;
    private static boolean breakBefore = false;

    public static long totalCatchCount = 0;

    // Invocations of the start (lifecycle) method that have not returned yet. Only the first invocation is
    // recorded: its recursive calls do not end the trace, and later invocations record nothing.
    private static int lifecycleDepth = 0;
    private static boolean lifecycleEntered = false;
    // whether the recorded invocation of the start method threw (then it has no U)
    private static boolean lifecycleThrew = false;
    // output that recording starts with once the start method is entered (see trace_start_on_entry)
    private static OutputStream pendingOut;

    private static OutputStream out;

    // ---- low-level byte buffer ----

    private static void putByte(int b) {
        if (out == null) return;
        buf[bufPos++] = (byte) b;
        if (bufPos == BUF_SIZE) flush();
    }

    private static void flush() {
        if (out == null || bufPos == 0) return;
        try {
            out.write(buf, 0, bufPos);
            bufPos = 0;
        } catch (Exception e) {
            // swallow — tracing must never crash the host program
        }
    }

    // ---- IE byte bit-packing (matches trace_mode.h _TRACE_IE_PREPARE_NEXT / _IF / _ELSE) ----

    private static void prepareNext() {
        if (ieByte < IE_FLUSH_THRESHOLD) {
            putByte(ieByte);
            ieByte = IE_INIT;
        }
    }

    private static void ieFinish() {
        if (ieByte != IE_INIT) {
            putByte(ieByte);
            ieByte = IE_INIT;
        }
    }

    public static void _IF() {
        // rotate left by 1
        ieByte = ((ieByte << 1) | (ieByte >>> 7)) & 0xFF;
        prepareNext();
    }

    public static void _ELSE() {
        ieByte = (ieByte << 1) & 0xFF;
        prepareNext();
    }

    public static void _LOOP_BODY() {
        if (!breakBefore) _IF();
        breakBefore = false;
    }

    public static void _LOOP_END() {
        if (!breakBefore) _ELSE();
        breakBefore = false;
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

    public static void _BREAK() {
        breakBefore = true;
    }

    // ---- function entry (variable-length, big-endian) ----

    public static void _FUNC(int num) {
        ieFinish();
        if (num == 0) {
            putByte(FUNC_ANON);
        } else if ((num & ~0xF) == 0) {
            putByte(FUNC_4 | (num & 0xFF));
        } else if ((num & ~0xFFF) == 0) {
            putByte(FUNC_12 | ((num >> 8) & 0xFF));
            putByte(num & 0xFF);
        } else if ((num & ~0xFFFFF) == 0) {
            putByte(FUNC_20 | ((num >> 16) & 0xFF));
            putByte((num >> 8) & 0xFF);
            putByte(num & 0xFF);
        } else if ((num & ~0xFFFFFFF) == 0) {
            putByte(FUNC_28 | ((num >> 24) & 0xFF));
            putByte((num >> 16) & 0xFF);
            putByte((num >> 8) & 0xFF);
            putByte(num & 0xFF);
        } else {
            putByte(FUNC_32);
            putByte((num >> 24) & 0xFF);
            putByte((num >> 16) & 0xFF);
            putByte((num >> 8) & 0xFF);
            putByte(num & 0xFF);
        }
    }

    // ---- element markers ----

    public static void _RETURN() {
        ieFinish();
        putByte(RETURN);
    }

    public static void _TRY() {
        ieFinish();
        putByte(TRY);
    }

    /** No-op in binary mode (matches C #else branch in trace_mode.h). */
    public static void _TRY_END() {
        // intentionally empty
    }

    public static void _CATCH(long idx) {
        ieFinish();
        // NUM_16: type byte (CATCH | LEN_16), then 2 bytes little-endian
        putByte(CATCH_T | LEN_16);
        putByte((int) (idx & 0xFF));
        putByte((int) ((idx >> 8) & 0xFF));
    }

    /**
     * Records a switch case index in 6 bits as a single byte (0x80 | caseId).
     * Reader treats this identically to a flushed IE byte holding 6 decisions —
     * which is exactly the C version's behavior for 6-bit cases.
     */
    public static void _CASE(int caseId) {
        ieFinish();
        putByte(0x80 | (caseId & 0x3F));
    }

    /**
     * Records a non-negative array dimension as a DATA element with the shortest length that fits (as C's
     * _TRACE_NUM, little-endian). A negative dimension is recorded as an IF and throws.
     */
    public static int _DIM(int dimension) {
        if (dimension < 0) {
            _IF();
            throw new NegativeArraySizeException("Negative array size: " + dimension);
        }
        ieFinish();
        if (dimension == 0) {
            putByte(DATA | LEN_0);
        } else if ((dimension & ~0xFF) == 0) {
            putByte(DATA | LEN_8);
            putByte(dimension);
        } else if ((dimension & ~0xFFFF) == 0) {
            putByte(DATA | LEN_16);
            putByte(dimension & 0xFF);
            putByte((dimension >> 8) & 0xFF);
        } else {
            putByte(DATA | LEN_32);
            putByte(dimension & 0xFF);
            putByte((dimension >> 8) & 0xFF);
            putByte((dimension >> 16) & 0xFF);
            putByte((dimension >> 24) & 0xFF);
        }
        return dimension;
    }

    public static int _LAST_DIM(int dimension) {
        if (dimension < 0) {
            _IF();
            throw new NegativeArraySizeException("Negative array size: " + dimension);
        }
        _ELSE();
        return dimension;
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

    // ---- file lifecycle ----

    public static void trace_start(OutputStream stream) {
        out = stream;
    }

    /**
     * Records into {@code stream}, but only from the first entry of the start method on: code that runs before
     * (e.g. an uninstrumented fuzz harness calling other instrumented methods first) is not recorded.
     */
    public static void trace_start_on_entry(OutputStream stream) {
        pendingOut = stream;
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
            ieByte = IE_INIT;
            bufPos = 0;
            out = pendingOut;
            pendingOut = null;
            return;
        }
        if (out != null) return;

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.nnnnnnnnn");
        String fileName = programName + "_" + now.format(fmt) + ".trace";

        try {
            new File(OUT_DIR).mkdirs();
            out = new FileOutputStream(OUT_DIR + fileName);
            System.out.println("Trace (binary) to: " + OUT_DIR + fileName);
        } catch (Exception e) {
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(Trace::trace_end));
    }

    /** Called when the start method returns (and by the tool / shutdown hook after the run). */
    public static void trace_end() {
        if (lifecycleDepth > 0 && --lifecycleDepth > 0) return; // return of a recursive call
        ieFinish();
        putByte(END);
        flush();
        try {
            if (out != null) {
                out.close();
                out = null;
            }
        } catch (Exception e) {
            // swallow
        }
    }

    /** Whether the start method was entered, i.e. whether a trace was recorded. */
    public static boolean trace_recorded() {
        return lifecycleEntered;
    }
}
