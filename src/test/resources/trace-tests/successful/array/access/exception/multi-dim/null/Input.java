class Input {
    public static void main(String[] args) {
        int[][] arr = new int[][] {null, null};
        try {
            int x = arr[1][0];
        } catch (NullPointerException e) {

        }
    }
}
