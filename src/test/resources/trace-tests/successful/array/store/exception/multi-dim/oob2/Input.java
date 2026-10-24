class Input {
    public static void main(String[] args) {
        int[][] arr = new int[3][2];
        try {
            arr[0][3] = 5;
        } catch (ArrayIndexOutOfBoundsException e) {
        }
    }
}
