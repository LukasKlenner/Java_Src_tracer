class Demo {

    static int demo(int x) {
        srctracer.Trace._FUNC(1);
        if (x < 0) {
            srctracer.Trace._IF();
            {
                int __srctracer_ret$0 = -1;
                srctracer.Trace._RETURN();
                return __srctracer_ret$0;
            }
        } else {
            srctracer.Trace._ELSE();
        }
        int sum = 0;
        for (int i = 0; i < x; i++) {
            srctracer.Trace._LOOP_BODY();
            if (i == 5) {
                srctracer.Trace._IF();
                srctracer.Trace._BREAK();
                break;
            } else {
                srctracer.Trace._ELSE();
            }
            sum += i;
        }
        srctracer.Trace._LOOP_END();
        boolean __srctracer_switch$0 = true;
        switch(x % 3) {
            case 0:
                if (__srctracer_switch$0) {
                    srctracer.Trace._CASE(0);
                    __srctracer_switch$0 = false;
                }
                sum *= 2;
                break;
            case 1:
                if (__srctracer_switch$0) {
                    srctracer.Trace._CASE(1);
                    __srctracer_switch$0 = false;
                }
                sum += 100;
            // fall-through to default
            default:
                if (__srctracer_switch$0) {
                    srctracer.Trace._CASE(2);
                    __srctracer_switch$0 = false;
                }
                sum -= 50;
        }
        {
            int __srctracer_ret$1 = sum;
            srctracer.Trace._RETURN();
            return __srctracer_ret$1;
        }
    }

    public static void main(String[] args) {
        srctracer.Trace.trace_start("Demo");
        try {
            srctracer.Trace._FUNC(2);
            int result = demo(args.length);
            System.out.println("result = " + result);
        } finally {
            srctracer.Trace.trace_end();
        }
    }
}
