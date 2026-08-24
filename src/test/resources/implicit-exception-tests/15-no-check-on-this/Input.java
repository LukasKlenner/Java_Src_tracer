class Input {
    int value = 42;

    int getValue() {
        return this.getValue2();
    }

    int getValue2() {
        int value = this.value;
        return this.value;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int x = obj.getValue();
    }
}
