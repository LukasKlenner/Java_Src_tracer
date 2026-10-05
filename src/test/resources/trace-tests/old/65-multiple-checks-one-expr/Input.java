class Input {
    int[] arr;

    Input(int[] a) {
        this.arr = a;
    }

    public static void main(String[] args) {
        int[] data = new int[]{10, 20, 30};
        Input obj = new Input(data);
        int b = 2;
        int x = obj.arr[1] / b;
    }
}
