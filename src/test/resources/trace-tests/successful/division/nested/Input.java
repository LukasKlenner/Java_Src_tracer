class Input {

    int result;

    int getA() {
        return 10;
    }

    int getB() {
        return 2;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.result = obj.getA() / obj.getB();
    }
}
