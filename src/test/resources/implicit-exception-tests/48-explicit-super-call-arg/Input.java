class Base {
    int value;
    Base(int v) {
        this.value = v;
    }
}

class Input extends Base {
    Input(int[] arr) {
        super(arr[0]);
    }

    public static void main(String[] args) {
        int[] data = new int[]{5, 10};
        Input obj = new Input(data);
    }
}
