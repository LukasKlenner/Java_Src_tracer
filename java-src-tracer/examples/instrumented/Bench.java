class Bench {

    static int run(int n) {
        srctracer.Trace._FUNC(1);
        int sum = 0;
        for (int i = 0; i < n; i++) {
            srctracer.Trace._LOOP_BODY();
            if ((i & 1) == 0) {
                srctracer.Trace._IF();
                sum += i;
            } else {
                srctracer.Trace._ELSE();
                sum -= i;
            }
        }
        srctracer.Trace._LOOP_END();
        {
            int __srctracer_ret$0 = sum;
            srctracer.Trace._RETURN();
            return __srctracer_ret$0;
        }
    }

    public static void main(String[] args) {
        srctracer.Trace.trace_start("Bench");
        try {
            srctracer.Trace._FUNC(2);
            int n = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
            // JIT warmup
            run(10_000);
            long start = System.nanoTime();
            int result = run(n);
            long elapsed = System.nanoTime() - start;
            System.out.println("n=" + n + " result=" + result + " elapsed_ms=" + (elapsed / 1_000_000.0));
        } finally {
            srctracer.Trace.trace_end();
        }
    }
}
