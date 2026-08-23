class Input {
    int getValue() {
        return 42;
    }

    void process(int x) {
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.process(obj.getValue());
    }
}
