class Input {
    public static void main(String[] args) {
        try {
            try {
                int x = 10 / 0;
            } catch (ArithmeticException e) {
                int y = 1;
            }
            int z = 5 / 1;
        } catch (Exception e) {
            int w = 2;
        }
    }
}
