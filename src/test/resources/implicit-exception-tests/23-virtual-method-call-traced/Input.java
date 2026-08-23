class Input {
    int value;

    Input(int v) {
        this.value = v;
    }

    int getValue() {
        return this.value;
    }

    public static void main(String[] args) {
        Input obj = new Input(42);
        int x = obj.getValue();
    }
}
