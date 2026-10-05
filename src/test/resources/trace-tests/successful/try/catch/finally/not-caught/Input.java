class Input {
    public static void main(String[] args) {
        try {
            try {
                Object a = null;
                a.toString();
            } catch (ArithmeticException e) {
                int y = 1;
            } finally {
                int[] arr = new int[5];
                int x = arr[0];
            }
        } catch (Exception e) {
            int z = 2;
        }
    }
}
