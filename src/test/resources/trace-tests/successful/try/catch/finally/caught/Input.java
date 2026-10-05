class Input {
    public static void main(String[] args) {
        try {
            Object a = null;
            a.toString();
        } catch (ArithmeticException e) {
            int y = 1;
        } catch (Exception e) {
            int z = 2;
        } finally {
            int[] arr = new int[5];
            int x = arr[0];
        }
    }
}
