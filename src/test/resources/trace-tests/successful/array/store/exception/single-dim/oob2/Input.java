class Input {
    public static void main(String[] args) {
        int[] arr = new int[3];
        try {
            arr[3] = 5;
        } catch (ArrayIndexOutOfBoundsException e) {
        }
    }
}
