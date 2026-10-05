class Input {

    int m(short s) {
        return 1;
    }

    int m(int i) {
        return 2;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        short s = 10;
        int result = obj.m(true ? s : 20);
        if (result == 1) {
            int a = 1;
        }
    }
}
