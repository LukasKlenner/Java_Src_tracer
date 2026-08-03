class HelloBranch {

    public static void main(String[] args) {
        srctracer.Trace.trace_start("HelloBranch");
        try {
            srctracer.Trace._FUNC(1);
            if (args.length > 0) {
                srctracer.Trace._IF();
                System.out.println("got " + args.length + " args");
            } else {
                srctracer.Trace._ELSE();
                System.out.println("no args");
            }
        } finally {
            srctracer.Trace.trace_end();
        }
    }
}
