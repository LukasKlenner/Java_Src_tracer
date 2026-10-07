class Input {

    public boolean test(int i) {
        if (i == 1) {
            return false;
        }

        if (i == 2) {
            return false;
        }

        return false;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        if (obj.test(1) || obj.test(2) || obj.test(3)) {
            int x = 1;
        }
    }
}
