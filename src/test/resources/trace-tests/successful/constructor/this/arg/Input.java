class Input {
    int value;

    Input() {
        this(new int[]{10, 20}[0]);
    }

    Input(int v) {
        this.value = v;
    }

    public static void main(String[] args) {
        Input obj = new Input();
    }
}
