class Input {
    int value;

    Input() {
        this.value = 42;
    }

    Input(int v) {
        this();
        this.value = v;
    }

    public static void main(String[] args) {
        Input obj = new Input(43);
    }
}
