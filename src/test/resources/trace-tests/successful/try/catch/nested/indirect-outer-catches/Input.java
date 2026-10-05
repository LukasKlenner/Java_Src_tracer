class Input {

    public void tryCatch() {
        try {
            int[] arr = new int[5];
            int x = arr[10];
        } catch (ArithmeticException e) {
            int y = 1;
        } catch (NullPointerException e) {
            int z = 2;
        }
    }

    public static void main(String[] args) {
        try {
            Input obj = new Input();
            obj.tryCatch();
        } catch (Exception e) {
            int w = 3;
        }
    }
}
