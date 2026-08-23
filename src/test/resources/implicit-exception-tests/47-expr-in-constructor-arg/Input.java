class Input {
    int value;

    Input(int v) {
        this.value = v;
    }

    public static void main(String[] args) {
        int[] arr = new int[]{10, 20};
        Input obj = new Input(arr[0]);
    }
}
