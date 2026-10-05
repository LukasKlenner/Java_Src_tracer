class Input {
    private int value = 42;

    public void fieldAccess() {
        int a = value;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.fieldAccess();
    }
}
