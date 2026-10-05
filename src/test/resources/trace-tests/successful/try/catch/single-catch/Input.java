class Input {

    public void m() {
    }

    public static void main(String[] args) {
        try {
            int x = 10 / 0;
        } catch (ArithmeticException e) {
            Input obj = new Input();
            obj.m();
        }
    }
}
