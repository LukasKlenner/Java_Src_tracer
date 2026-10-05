class Input {
    String value = "hello";

    int getLength() {
        return value.length();
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int len = obj.getLength();
    }
}
