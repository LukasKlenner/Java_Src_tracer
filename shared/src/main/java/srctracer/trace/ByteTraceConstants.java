package srctracer.trace;

public class ByteTraceConstants {
    // Byte-level constants from trace_elem.h
    public static final int FUNC_4    = 0x00;
    public static final int FUNC_12   = 0x10;
    public static final int FUNC_20   = 0x20;
    public static final int FUNC_28   = 0x30;
    public static final int FUNC_32   = 0x43; // 'C'
    public static final int FUNC_ANON = 0x41; // 'A'
    public static final int END       = 0x45; // 'E'
    public static final int RETURN    = 0x52; // 'R'
    public static final int TRY       = 0x54; // 'T'
    public static final int CATCH_T   = 0x68; // ELEM2 CATCH
    public static final int LEN_16    = 0x02;
    public static final int IE_INIT   = 0xFE;
}
