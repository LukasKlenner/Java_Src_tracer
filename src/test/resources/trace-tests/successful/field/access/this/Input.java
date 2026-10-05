class Input {
    public int value = 42;

    public void fieldAccess() {
        int a = this.value;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.fieldAccess();
    }
}
