class Input {

    int getLength2() {
        return 2;
    }

    int getLength() {
        return getLength2();
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int len = obj.getLength();
    }
}
