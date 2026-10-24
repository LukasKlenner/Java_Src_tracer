class Input {
    public static void main(String[] args) {
        int[][] arr = new int[3][2];
        try {
            int x = arr[0][3];
        } catch (ArrayIndexOutOfBoundsException e) {
        }
    }
}
