class Input {

    int result = 20;

    int getB() {
        return 2;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.result /= obj.getB();
    }
}
