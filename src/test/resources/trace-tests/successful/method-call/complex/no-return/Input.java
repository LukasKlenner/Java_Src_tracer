class Input {

    int getLength(int i) {

        if (i == 1) {
            return 1;
        }

        return 2;
    }

    void m(int length1, int length2) {

        if (length1 != 1) {
            return;
        }

        if (length2 != 2) {
            return;
        }
    }

    Input getInstance() {
        return new Input();
    }

    public static void main(String[] args) {
        Input obj = new Input();
        obj.getInstance().m(obj.getLength(1), obj.getLength(2));
    }
}
