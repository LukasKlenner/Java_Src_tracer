class Input {
    int value;

    void m() {
        this.value += 99;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.m();
    }
}
