class Input {
    public static void main(String[] args) {
        int[] arr = new int[3];
        try {
            int x = arr[-1];
        } catch (ArrayIndexOutOfBoundsException e) {
        }
    }
}
