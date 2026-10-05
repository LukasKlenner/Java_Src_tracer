class Input {
    int[] arr;

    Input(int[] a) {
        this.arr = a;
    }

    int getValue() {
        return 99;
    }

    public static void main(String[] args) {
        Input obj = new Input(new int[3]);
        Input other = new Input(null);
        obj.arr[0] = other.getValue();
    }
}
