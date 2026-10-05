class Input {
    private int value;

    public void fieldWrite() {
        value = 1;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.fieldWrite();
    }
}
