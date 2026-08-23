class Input {
    int value;

    Input(int v) {
        this.value = v;
    }

    Input(int[] arr) {
        this(arr[0]);
    }

    public static void main(String[] args) {
        int[] data = new int[]{5, 10};
        Input obj = new Input(data);
    }
}
