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
        Input obj1 = new Input();
        Input obj2 = null;
        try {
            obj1.m(obj2.getLength(1), obj2.getLength(2));
        } catch (NullPointerException e) {
        }
    }
}
