class TryDemo {

    static int divide(int a, int b) {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    static int parseOr(String s, int dflt) {
        try {
            int value = Integer.parseInt(s);
            return value;
        } catch (NumberFormatException e) {
            System.out.println("not a number: " + s);
            return dflt;
        } catch (Exception e) {
            System.out.println("unexpected: " + e);
            return -1;
        }
    }

    static void logSafe(String msg) {
        try {
            System.out.println(msg);
        } catch (Exception e) {
            // swallow
        }
    }

    public static void main(String[] args) {
        int q = divide(10, args.length);
        int n = parseOr(args.length > 0 ? args[0] : "x", 99);
        logSafe("q=" + q + " n=" + n);
    }
}
