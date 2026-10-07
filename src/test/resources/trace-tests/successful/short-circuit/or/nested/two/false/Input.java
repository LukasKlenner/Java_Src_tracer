class Input {

    public boolean test(int i) {
        if (i == 1) {
            return false;
        }

        return true;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        if (obj.test(1) && obj.test(2)) {
            int x = 1;
        }
    }
}
