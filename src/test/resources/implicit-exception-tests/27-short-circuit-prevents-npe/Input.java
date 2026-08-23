class Input {
    int getValue() {
        return 42;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        boolean safe = obj != null && obj.getValue() > 0;
    }
}
