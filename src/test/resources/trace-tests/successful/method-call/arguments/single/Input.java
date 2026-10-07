class Input {

    int getLength(int i) {
        if (i == 1) {
            return 1;
        }

        return 2;
    }

    void m(int length) {
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.m(obj.getLength(1));
    }
}
