class Input {

    int a;

    Input(int i) {
        this.a = 1;
    }

    Input(short s) {
        this.a = 2;
    }

    Input(boolean b, short s) {
        this(b ? s : 0);
    }

    public static void main(String[] args) {
        Input obj = new Input(true, (short) 10);
        if (obj.a == 1) {
            int a = 1;
        }
    }
}
