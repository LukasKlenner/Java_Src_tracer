class Input {
    int getValue() {
        return 42;
    }

    public static void main(String[] args) {
        Input[] objs = new Input[]{new Input(), new Input()};
        int x = objs[0].getValue();
    }
}
