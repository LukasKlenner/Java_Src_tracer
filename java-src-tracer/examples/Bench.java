class Bench {

    static int run(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            if ((i & 1) == 0) {
                sum += i;
            } else {
                sum -= i;
            }
        }
        return sum;
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        run(10_000); // JIT warmup
        long start = System.nanoTime();
        int result = run(n);
        long elapsed = System.nanoTime() - start;
        System.out.println("n=" + n
                + " result=" + result
                + " elapsed_ms=" + (elapsed / 1_000_000.0));
    }
}
