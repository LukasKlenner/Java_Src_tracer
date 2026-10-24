class Input {
    public static void main(String[] args) {
        int[][] arr = new int[][] {null, null};
        try {
            arr[1][0] = 5;
        } catch (NullPointerException e) {
        }
    }
}
