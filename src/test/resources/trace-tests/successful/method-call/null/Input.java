class Input {
    String value = "hello";

    int getLength() {
        return value.length();
    }

    public static void main(String[] args) {
        Input obj = null;
        try {
            obj.getLength();
        } catch (NullPointerException e) {
        }
    }
}
