class Input {
    static int compute(int x) {
        return x * 2;
    }

    Input getInstance() {
        return this;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int result = obj.getInstance().compute(5);
    }
}
