class Input {

    int getValue(int i) {
        if (i == 1) {
            return 1;
        }
        return 2;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int result = obj.getValue(1) + obj.getValue(2);
    }
}
