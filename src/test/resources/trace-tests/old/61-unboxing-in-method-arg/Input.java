class Input {
    void process(int x) {
    }

    public static void main(String[] args) {
        Input obj = new Input();
        Integer boxed = Integer.valueOf(5);
        obj.process(boxed);
    }
}
