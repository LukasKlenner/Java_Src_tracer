class Input {
    int value = 42;

    public static void main(String[] args) {
        Input obj = null;
        try {
            int x = obj.value;
        } catch (NullPointerException e) {

        }
    }
}
