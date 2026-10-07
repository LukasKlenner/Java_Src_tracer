class Input {

    int getLength(int i) {

        if (i == 1) {
            return 1;
        }

        return 2;
    }

    void m(int length1, int length2) {
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.m(obj.getLength(1), obj.getLength(2));
    }
}
