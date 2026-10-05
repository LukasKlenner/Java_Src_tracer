class Input {
    public static void main(String[] args) {
        int[] arr = new int[]{1, 2, 3};
        int i = 0;
        int sum = 0;
        while (true) {
            if (i == arr.length) {
                break;
            }

            sum += arr[0];
            i++;
        }
    }
}
