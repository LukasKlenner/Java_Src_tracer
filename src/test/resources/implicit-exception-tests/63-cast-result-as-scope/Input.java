class Input {
    int getValue() {
        return 42;
    }

    public static void main(String[] args) {
        Object obj = new Input();
        int x = ((Input) obj).getValue();
    }
}
