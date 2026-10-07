class Input {

    public boolean test(int i) {
        if (i == 1) {
            return true;
        }

        if (i == 2) {
            return true;
        }

        return false;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        if (obj.test(1) && obj.test(2) && obj.test(3)) {
            int x = 1;
        }
    }
}
