class Input {
    public static void main(String[] args) {
        int[] arr = new int[]{1, 2, 3};
        int sum = 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == 2) {
                break;
            }
            sum += arr[i];
        }
    }
}
