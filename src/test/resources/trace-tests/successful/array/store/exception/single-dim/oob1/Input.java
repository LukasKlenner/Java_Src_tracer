class Input {
    public static void main(String[] args) {
        int[] arr = new int[3];
        try {
            arr[-1] = 5;
        } catch (ArrayIndexOutOfBoundsException e) {
        }
    }
}
