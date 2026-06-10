class Demo {

    static int demo(int x) {
        if (x < 0) {
            return -1;
        }

        int sum = 0;
        for (int i = 0; i < x; i++) {
            if (i == 5) {
                break;
            }
            sum += i;
        }

        switch (x % 3) {
            case 0:
                sum *= 2;
                break;
            case 1:
                sum += 100;
                // fall-through to default
            default:
                sum -= 50;
        }

        return sum;
    }

    public static void main(String[] args) {
        int result = demo(args.length);
        System.out.println("result = " + result);
    }
}
