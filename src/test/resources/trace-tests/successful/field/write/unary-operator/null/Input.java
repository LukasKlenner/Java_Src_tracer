class Input {
    int value;

    public static void main(String[] args) {
        Input obj = null;
        try {
            obj.value++;
        } catch (NullPointerException e) {
        }
    }
}
