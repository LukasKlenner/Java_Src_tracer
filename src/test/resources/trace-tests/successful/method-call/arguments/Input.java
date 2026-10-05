class Input {

    int getLength() {
        return 2;
    }

    void m(int length) {
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.m(obj.getLength());
    }
}
