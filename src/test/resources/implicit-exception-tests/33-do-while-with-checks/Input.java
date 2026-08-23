class Input {
    public static void main(String[] args) {
        int[] arr = new int[]{1, 2, 3};
        int i = 0;
        int sum = 0;
        do {
            sum += arr[i];
            i++;
        } while (i < arr.length);
    }
}
