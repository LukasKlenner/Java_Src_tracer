class Input {
    int value;

    Input() {
        value = 5 / 0;
    }

    public static void main(String[] args) {
        try {
            Input obj = new Input();
        } catch (Exception e) {
            int a = 1;
        }
    }
}
